package id.tracive.sdk.oauth

import id.tracive.sdk.TraciveException
import id.tracive.sdk.client.ApiErrors
import id.tracive.sdk.client.HttpMethod
import id.tracive.sdk.client.HttpRequest
import id.tracive.sdk.client.HttpResponse
import id.tracive.sdk.client.HttpTransport
import id.tracive.sdk.client.KtorTransport
import id.tracive.sdk.client.SdkJson
import id.tracive.sdk.model.AuthResult
import id.tracive.sdk.model.ClientType
import id.tracive.sdk.model.TraciveEnvironment
import id.tracive.sdk.model.TraciveMode
import id.tracive.sdk.platform.AppPresenceChecker
import id.tracive.sdk.platform.AuthorizeLauncher
import id.tracive.sdk.platform.SecureTokenStore
import id.tracive.sdk.platform.defaultAppPresenceChecker
import id.tracive.sdk.platform.defaultAuthorizeLauncher
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The token endpoint's reply, in RFC 6749 §5.1's field names.
 *
 * Not a `data class`, and never logged: two of its five fields are live credentials. Everything but
 * `access_token` is optional, because a standards-compliant server may omit `scope` (meaning "what
 * you asked for"), `expires_in`, or `refresh_token`.
 */
@Serializable
private class TokenResponse(
  @SerialName("access_token") val accessToken: String,
  @SerialName("token_type") val tokenType: String = "Bearer",
  @SerialName("expires_in") val expiresIn: Long? = null,
  @SerialName("refresh_token") val refreshToken: String? = null,
  @SerialName("scope") val scope: String? = null,
)

/**
 * The OAuth 2.0 + PKCE flow against the Tracive app, and the grant it produces.
 *
 * The shape of the flow is not negotiable, and each step below carries its own reason:
 * 1. **Check the Tracive app is installed, before any redirect.** Without it there is nobody to
 *    authorize the grant, and an unclaimed App Link lands in a browser asking for Tracive
 *    credentials — which teaches users to type them into whatever a partner app opened.
 * 2. Not installed → open the *clean* install link (no OAuth parameters at all) and return
 *    [AuthResult.AppNotInstalled]. There is no deferred resume: the server-side request expires in
 *    ten minutes, which a download plus a registration will always outlast, so the user simply
 *    retries in the partner app afterwards.
 * 3. Installed → open the authorize URL in the **system browser**: Custom Tabs on Android,
 *    `UIApplication.open` on iOS. Never a WebView (RFC 8252 §8.12, and App Links do not fire from
 *    one anyway).
 * 4. On the callback, verify `state`, then either exchange the code (a [ClientType.PUBLIC] client)
 *    or hand it back for the partner's backend to exchange (a [ClientType.CONFIDENTIAL] one).
 *
 * ### The outcome arrives in exactly one place
 *
 * [authorize] starts the flow and returns; [handleCallback] says what happened. It is deliberately
 * not one awaitable call, because it is not one process. Consent happens inside the Tracive app, so
 * this app is in the background for the whole of it — and on Android that makes it an ordinary
 * candidate for being killed. A call parked on a continuation would simply be gone by the time the
 * redirect came back, and the record it wrote to disk would have nowhere to deliver itself. The
 * Flutter and React Native SDKs are shaped the same way for the same reason.
 *
 * ```
 * // In a coroutine, typically a viewModelScope:
 * when (val start = client.oauth!!.authorize()) {
 *   null -> Unit                                        // the page is open; the answer comes later
 *   AuthResult.AppNotInstalled -> showInstallMessage()  // the install page is already open
 *   is AuthResult.Failed -> showError(start.error)      // nothing was opened
 *   else -> Unit                                        // authorize() returns nothing else
 * }
 *
 * // Wherever the redirect arrives — Android onNewIntent, iOS onOpenURL — also in a coroutine:
 * when (val result = client.oauth!!.handleCallback(url)) {
 *   null -> Unit                                        // not a Tracive redirect; keep routing it
 *   is AuthResult.Authorized -> proceed(result.grantedScopes)
 *   is AuthResult.CodeReceived -> myBackend.exchange(result.code, result.codeVerifier, result.redirectUri)
 *   AuthResult.Denied -> showDeclinedMessage()
 *   is AuthResult.Failed -> showError(result.error)
 *   AuthResult.AppNotInstalled -> Unit                  // never returned here
 * }
 * ```
 *
 * **Never throw away what [handleCallback] returns.** It is the only place an authorization
 * succeeds, and one of the things it can hand back is a grant belonging to a process that no longer
 * exists.
 *
 * Two consequences worth stating plainly:
 * - If the user backs out of the browser without a redirect, nothing arrives. Call
 *   [cancelPendingAuthorization] when the browser closes, so a live verifier does not sit on the
 *   device until it ages out.
 * - **A callback that outlives the host process completes anyway.** The pending record — `state`,
 *   the verifier, the redirect URI and a timestamp — is written to the token store before the
 *   browser opens, and [handleCallback] reads it back from there when this process holds nothing.
 *   It is erased on every outcome that consumes the attempt, and refused once it is older than
 *   [pendingAuthorizationMaxAge] — see there for how long that is and why. A callback whose `state`
 *   does not match is not one of those outcomes; it leaves the record alone, so a URL from another
 *   app cannot end a consent the user is still giving.
 *
 * There is no API-key parameter here or anywhere else in this SDK, in any mode. That is a hard
 * design invariant, not an omission.
 */
class TraciveOAuth(
  /** The configured OAuth client. */
  val mode: TraciveMode.OAuth,

  /** Which deployment to authorize against. Also picks the default authorize endpoint. */
  val environment: TraciveEnvironment = TraciveEnvironment.Production,
  transport: HttpTransport? = null,
  tokenStore: TokenStore? = null,
  appPresenceChecker: AppPresenceChecker? = null,
  authorizeLauncher: AuthorizeLauncher? = null,

  /** Test seam for token-expiry arithmetic. */
  private val clock: Clock = Clock.System,

  /**
   * Where a user without the Tracive app is sent. Null uses the default.
   *
   * Must carry **no OAuth parameters** — see [INSTALL_URL]. Non-sensitive tags naming the client
   * and the SDK are fine, and are what the default adds.
   *
   * Null and blank are different answers, and this constructor treats them differently. Null means
   * "I am not replacing the link", so the default is used. A **blank** string — empty, or nothing
   * but whitespace — is a configuration mistake, and it is rejected here with
   * [REASON_BLANK_INSTALL_URL] in the message rather than quietly falling back to the default. See
   * [installUrl] for why silently substituting is the worst of the available answers.
   *
   * Any other value is used **verbatim**: not trimmed, not repaired, not checked for being a URL at
   * all. A wrong-but-non-blank link reaches the user exactly as written, because vetting a
   * partner's own link is not this SDK's job.
   *
   * @throws IllegalArgumentException if a blank string is passed. The token is the same in every
   *   Tracive SDK, so a cross-platform integration can match on one string.
   */
  installUrlOverride: String? = null,

  /** An explicit authorize endpoint, for staging or a test double. */
  authorizeEndpointOverride: String? = null,

  /**
   * How long a started authorization may sit before its callback is refused.
   *
   * Defaults to [DEFAULT_PENDING_AUTHORIZATION_MAX_AGE], which is where the reasoning for the value
   * lives.
   *
   * A parameter rather than a bare constant because the Flutter and React Native SDKs expose the
   * same knob, and an integration spanning more than one of them should be able to set the same
   * number in each. `private val` privatises the generated *property*, not the constructor
   * parameter — a caller can still pass `pendingAuthorizationMaxAge = …` by name — which is what
   * makes that possible, and which is also why the `private` here is not the access control it
   * looks like.
   *
   * A previous edit replaced this paragraph with a claim that no caller outside this class could
   * reach it. That was wrong, and it is recorded here because of how it happened: the check was a
   * grep of the klib API dump for the parameter's *name*, and that dump lists parameter *types*.
   * The search could not have found what it was asked about, and a contradiction it produced was
   * read as an error in this comment rather than in the search. Swift alone keeps its window
   * genuinely closed, by not taking a parameter at all.
   */
  private val pendingAuthorizationMaxAge: Duration = DEFAULT_PENDING_AUTHORIZATION_MAX_AGE,
) {

  /**
   * Lazy on purpose. These four defaults reach for the platform — an Android `Context`, the
   * Keystore, the Keychain — and evaluating them in the constructor would make simply *building* a
   * client fail in a unit test, or on a host that injects its own implementations anyway.
   */
  private val transportProvider = lazy { transport ?: KtorTransport() }
  private val tokenStoreProvider = lazy { tokenStore ?: SecureTokenStore() }
  private val presenceProvider = lazy {
    appPresenceChecker ?: defaultAppPresenceChecker(environment)
  }
  private val launcherProvider = lazy { authorizeLauncher ?: defaultAuthorizeLauncher() }

  private val ownsTransport: Boolean = transport == null

  private val urlBuilder = AuthorizeUrlBuilder(environment, authorizeEndpointOverride)

  /**
   * Serializes refreshes so a rotating refresh token is never spent twice. See [refreshedToken].
   */
  private val refreshMutex = Mutex()

  /**
   * The refresh tokens this flow has actually put on the wire, oldest first.
   *
   * A refresh token is single-use, and presenting one twice is exactly what the server's reuse
   * detection looks for: it answers by revoking the whole grant, not by failing one call. The mutex
   * above only makes refreshes *sequential*, and the hazard this list closes is sequential. A
   * refresh that fails with no reply at all — a socket timeout, a connection dropped mid-flight —
   * is indistinguishable from a success whose answer was lost, so the server may already have
   * rotated the token and be waiting to see the new one. The store still holds the old one, and
   * without this list the next 401 reads it back and sends it again. The guard has to be on the
   * token's identity, because nothing about the timing distinguishes the two cases.
   *
   * It is a short history rather than a single slot because a single slot cannot answer for a token
   * two rotations back: by the time such a token turns up the slot has moved on, and it goes
   * straight back on the wire. That was tried in the Flutter SDK and was wrong for that reason. How
   * far back this reaches is not safety-critical — every refresh re-reads the store under the mutex
   * and sends what it finds there, so a token too old to be recognised here is one the store has
   * already replaced.
   *
   * It is keyed on the token that was actually sent, which is safe because the Tracive API rotates
   * on every refresh. RFC 6749 §6 also allows a reply that carries no `refresh_token` at all, which
   * means "keep the one you have", and [tokenSetFrom] carries the previous token forward when that
   * happens. Such a token is one the server did not retire, so [refreshedToken] takes its mark off
   * again on that path. Leaving it marked would make the very next refresh refuse the only token
   * the grant has left, which is the opposite of what this history is for.
   *
   * Replaced wholesale rather than mutated in place, so a reader on another thread sees either the
   * old list or the new one and never a half-updated one. [markRefreshTokenSpent] and
   * [releaseRefreshToken] both run under [refreshMutex], which is what makes their
   * read-modify-writes safe against each other. [forgetSpentRefreshTokens] does not need that:
   * losing a concurrent append to a sign-out's clear is harmless, because the sign-out has just
   * emptied the store the appended token came from.
   *
   * **Deliberately in memory only: it dies with the process, and is not persisted.** What it would
   * buy is protection across a restart — a store rolled back by a device-backup restore holds a
   * refresh token this SDK already spent, and after a cold start there is nothing here to recognise
   * it. That case ends at the server, which sees the reuse and revokes the grant, so the user
   * re-consents once. Buying it back would mean writing dead refresh tokens to the secure store:
   * more credential-shaped strings on disk, permanently, to spare a rare and recoverable prompt.
   * Not worth it. All four SDKs make the same call.
   */
  @Volatile private var spentRefreshTokens: List<String> = emptyList()

  /**
   * The authorization *this* process started and has not yet resolved.
   *
   * A cache of what is on disk, never the authority. [handleCallback] prefers it and falls back to
   * the token store, which is what lets a redirect arriving in a new process finish a flow this
   * object never started — and what lets an in-process callback finish one whose store refused the
   * write. It also carries the attempt's id, which is not persisted and therefore only ever answers
   * for a flow this instance began; see [pendingAuthorizationId].
   *
   * Written by [authorize] on whatever coroutine the host used, and read by [handleCallback] and
   * [cancelPendingAuthorization] on a platform callback thread — Android's `onNewIntent`, iOS's
   * `onOpenURL`. Without `@Volatile` neither the JVM nor Kotlin/Native memory model gives the
   * reader a happens-before edge on the field write, so a callback could legitimately observe null
   * just after `authorize` installed an attempt, and would then treat a flow this process is in the
   * middle of as one recovered from a previous one.
   */
  @Volatile private var pending: AuthorizationAttempt? = null

  /** Hands out the ids in [AuthorizationAttempt]. Only ever touched under [authorize]. */
  private var authorizationCounter: Long = 0L

  /**
   * Moves every time the grant is deliberately thrown away — [signOut] and [clearTokens].
   *
   * Every write of a token this class performs is conditional on it not having moved since that
   * round trip began. Discarding credentials is instantaneous and obtaining them is not, so a
   * refresh or a code exchange that was already on the wire when the user signed out still
   * completes — and its write would refill a store the user just emptied, leaving a live grant on a
   * device they may be about to hand to someone else. Worse, on the refresh path the token it
   * writes back is the *rotated* one: the newest and longest-lived refresh token in the grant's
   * history, stored on a device that has been told it is signed out.
   *
   * Waiting for the in-flight work inside [signOut] is not the alternative it looks like. It would
   * make signing out depend on a network call finishing, which is exactly what [signOut] refuses to
   * do — a sign-out has to be immediate and final even offline.
   *
   * The counter is only ever incremented, so a lost increment under a race cannot restore an
   * earlier value: whatever a caller captured before a sign-out can never compare equal again. That
   * is the only property the guard needs, which is why this is a plain volatile field rather than
   * an atomic.
   */
  @Volatile private var credentialGeneration: Long = 0L

  init {
    // Refused here, at construction, because this is the loudest point available: it runs once, in
    // the host app's own code, on every run, during development, before any end user is involved.
    requireUsableInstallUrl(installUrlOverride)
  }

  /**
   * The install page for a device without the Tracive app. Never carries OAuth parameters.
   *
   * Either [installUrlOverride] exactly as it was given, or [defaultInstallUrl] when none was.
   *
   * A blank override never reaches this property; it is refused by the constructor. The rejected
   * alternative was to fall back to the default, and that is the most dangerous of the behaviours
   * available, because the link it produces *works*. A partner whose configuration pipeline yields
   * an empty string ships a build that silently sends their users to Tracive's own install page,
   * and nothing in their logs or ours ever says so. Failing loudly costs one build; failing quietly
   * costs a release nobody knows is wrong.
   */
  val installUrl: String = installUrlOverride ?: defaultInstallUrl(mode.clientId)

  /**
   * The authorize endpoint in use, with no query string. Useful when verifying an App Link setup.
   */
  val authorizeEndpoint: String
    get() = urlBuilder.endpoint

  // ------------------------------------------------------------------ flow ----

  /**
   * Starts one authorization and returns as soon as the page is open.
   *
   * There are exactly three answers, and only one of them is an outcome:
   * - **null** — the authorization page was opened. What the user decides arrives at
   *   [handleCallback], possibly in a different process from this one.
   * - [AuthResult.AppNotInstalled] — the Tracive app is missing, so nothing was started: no PKCE
   *   pair, no `state`, nothing written down. The clean install page has already been opened, so
   *   the user has somewhere to go, and there is no deferred resume — they install Tracive and try
   *   again here.
   * - [AuthResult.Failed] — the operating system refused to open the page. The pending record is
   *   rolled back, so a redirect turning up later cannot be matched against it.
   *
   * A grant, a denial and a `state` mismatch are deliberately not among them. Every one of those is
   * something the *redirect* says, and the redirect is delivered by the OS to whatever process is
   * alive when it arrives — so they all come back from [handleCallback], and there is one place to
   * handle an authorization ending rather than two.
   *
   * Calling it again while one is pending supersedes the first: only one `state` is outstanding at
   * a time, and the earlier attempt's redirect is then refused as a mismatch, because honouring a
   * stale callback is exactly the confusion `state` exists to prevent.
   *
   * The [PendingAuthorization] is written to the token store **before** the browser opens, and
   * erased again on every outcome that consumes it. A write that fails does not stop the
   * authorization: the in-memory attempt can still complete a callback that reaches this same
   * process, so the flow works and only its ability to survive a process death is lost. Refusing
   * here would be worse than that — a confidential client whose store deliberately keeps nothing
   * would be unable to authorize at all, even though it never asks this SDK to hold a token.
   *
   * @param extraAuthorizeParams extra query parameters for the authorize URL. OAuth parameters
   *   cannot be overridden.
   */
  suspend fun authorize(extraAuthorizeParams: Map<String, String> = emptyMap()): AuthResult? {
    if (!presenceProvider.value.isTraciveAppInstalled()) {
      // Fire-and-forget: the user is going to the store, and there is nothing to resume when they
      // come back. Failing to open the page is not worth turning into an error — the caller still
      // has to show its own "install Tracive first" message either way.
      runCatchingLaunch { launcherProvider.value.launchInstall(installUrl) }
      return AuthResult.AppNotInstalled
    }

    val pkce = PkceGenerator.generate()
    val record =
      PendingAuthorization(
        state = PkceGenerator.randomState(),
        codeVerifier = pkce.codeVerifier,
        redirectUri = mode.redirectUri,
        createdAt = clock.now(),
      )
    authorizationCounter += 1
    val request = AuthorizationAttempt("authorization-$authorizationCounter", record)
    pending = request

    // Persisted before the URL is opened, never after. Consent happens in the Tracive app, so this
    // process is in the background for the whole of it and may not be alive when the redirect
    // returns. A verifier that existed only in the field above would be gone by then.
    try {
      tokenStoreProvider.value.savePending(record)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Throwable) {
      // Non-fatal by design; see this method's documentation. A callback reaching this process can
      // still be completed from the attempt above.
    }

    val url = urlBuilder.build(mode, pkce, record.state, extraAuthorizeParams)
    try {
      launcherProvider.value.launchAuthorize(url)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Throwable) {
      forget(request)
      return AuthResult.Failed(
        TraciveException.Unknown(
          message = "No browser is available to open the Tracive authorization page."
        )
      )
    }

    return null
  }

  /**
   * Drops [request] from memory and from the store, whatever ended it.
   *
   * This is the single place an attempt that has been consumed is cleaned up — a completed
   * exchange, a code handed to a confidential client, a denial, an expired attempt, a cancel, or a
   * browser that would not open. Routing them all through here is what makes "a spent attempt is
   * never replayable" one fact rather than six.
   *
   * A `state` mismatch is deliberately not on that list. It says a URL arrived that this SDK did
   * not ask for, which is not an outcome of the attempt the user started — see [handleCallback].
   *
   * The identity check is a guard, not a formality. Two authorizations can overlap — a double tap
   * is enough — and the one that loses must not erase the record the winner has just written, or
   * the flow the user is actually looking at silently loses its ability to survive a process death.
   * Passing null means "the attempt recovered from the store", which owns the stored record only
   * while this process has none of its own; a concurrent [authorize] that has installed one wins,
   * and its record stays.
   *
   * [NonCancellable] because a caller may already be inside a cancelled coroutine, where every
   * suspending call throws — which would leave the spent verifier on disk precisely when the flow
   * was abandoned.
   */
  private suspend fun forget(request: AuthorizationAttempt?) {
    if (pending !== request) return
    pending = null
    withContext(NonCancellable) {
      try {
        tokenStoreProvider.value.savePending(null)
      } catch (_: Throwable) {
        // Nothing better is available here: the outcome is already decided and replacing it with a
        // storage error would hide it. The record ages out of usefulness on its own, and the next
        // authorize() overwrites it.
      }
    }
  }

  /**
   * Completes the authorization the redirect in [url] belongs to, and says how it ended.
   *
   * Call it from wherever the OS delivers a deep link — Android's `onNewIntent` and `onCreate`,
   * iOS's `onOpenURL` — inside a coroutine, and **act on what it returns**. This is the only place
   * an authorization succeeds. It suspends because completing one means reading the token store
   * and, for a [ClientType.PUBLIC] client, exchanging the code.
   *
   * The pending record is taken from memory when this process is the one that started the flow, and
   * from the token store when it is not. That fallback is the whole point of writing the record
   * down: consent happens in another app, so this one can be killed while the user is in the middle
   * of it, and the redirect then arrives in a process that has never heard of the attempt. It
   * finishes anyway, because `state`, the verifier and the redirect URI are all on disk. A store
   * that cannot be read answers "nothing pending" rather than throwing — a deep-link handler is not
   * a place to raise an exception from, and it is indistinguishable from a store holding nothing.
   *
   * The `state` check happens before the code is used for anything: a callback whose `state` does
   * not match the one this SDK generated did not come from the request this SDK started, so its
   * code must not be redeemed. It is refused and **nothing else happens** — in particular the
   * pending record stays. Any app on the device can hand this method a URL, so erasing on a
   * mismatch would hand any of them a one-call cancel button for a consent the user is still in the
   * middle of, and neither they nor the host would ever be told why the flow ended. Keeping it does
   * not leave the verifier on the device any longer: the age check retires it on the same schedule
   * regardless.
   *
   * "Not mine" and "mine, and wrong" are different answers and this method keeps them apart. A URL
   * that is not addressed to [TraciveMode.OAuth.redirectUri], or that carries none of `code`,
   * `error` or `state`, is left entirely alone: the pending authorization is untouched and the
   * answer is null. Only a URL that is genuinely this client's redirect is measured against
   * `state`, and a `state` that is present and different is then treated as the security event it
   * is.
   *
   * The distinction is not cosmetic. Hosts are encouraged to route every incoming deep link through
   * here, and a redirect on a private-use scheme is one any other installed app may declare and
   * fire. Without it, one empty deep link from any app on the device would abort a user's
   * authorization — repeatedly, and with nothing to show for it.
   *
   * An attempt older than [pendingAuthorizationMaxAge] is refused here too, after `state` has been
   * checked and before the code is used. [DEFAULT_PENDING_AUTHORIZATION_MAX_AGE] explains why the
   * age is worth checking at all.
   *
   * @return null when [url] is not this client's redirect or carries no OAuth parameter at all —
   *   "not mine", not an error, and nothing was touched. Otherwise the outcome:
   *   [AuthResult.Authorized], [AuthResult.CodeReceived], [AuthResult.Denied], or
   *   [AuthResult.Failed]. [AuthResult.AppNotInstalled] is never returned here. A redirect on this
   *   client's own URI with nothing left to complete — already spent, expired, or never started —
   *   is [AuthResult.Failed]`(InvalidRequest)`, so deliver each redirect once rather than replaying
   *   a stored intent on every recreation.
   */
  suspend fun handleCallback(url: String): AuthResult? {
    if (!isOurRedirect(url)) return null
    val parameters = parseQueryParameters(url)
    if (OAUTH_CALLBACK_PARAMS.none { it in parameters }) return null

    val request = pending
    val record =
      request?.record
        ?: recoveredRecord()
        ?: return AuthResult.Failed(
          TraciveException.InvalidRequest(
            "No authorization is in progress on this device. Call authorize() first."
          )
        )

    val state = parameters[AuthorizeUrlBuilder.PARAM_STATE]
    // Compared in constant time — see [constantTimeEquals] for what that is worth here, which is
    // less than it sounds, and why the four Tracive SDKs do it the same way regardless.
    if (state == null || !constantTimeEquals(state, record.state)) {
      // Refused, and the pending record is deliberately left where it is. Any app on the device can
      // hand this method a URL, so erasing here would give any of them a one-call cancel button for
      // a consent the user is in the middle of: they finish in the Tracive app, come back, and find
      // nothing left to complete — and nothing reports it, because from here the forged URL was
      // turned away exactly as it should have been. Keeping the record does not leave the verifier
      // on the device for any longer than it was already going to be there. The age check below is
      // what retires it, and a forged callback does not push that deadline out.
      return AuthResult.Failed(
        TraciveException.InvalidRequest(
          "The authorization callback did not match this request (state mismatch)."
        )
      )
    }

    // Only now, once the callback is known to be this request's own. A redirect whose `state` does
    // not match is not this attempt's, so "expired" would be the wrong thing to say about it — the
    // mismatch is the finding, and it is the one the host needs.
    if (record.isStaleAt(clock.now(), pendingAuthorizationMaxAge)) {
      forget(request)
      return AuthResult.Failed(
        TraciveException.InvalidRequest(
          "The authorization attempt expired before its callback arrived. Start it again."
        )
      )
    }

    val error = parameters[PARAM_ERROR]
    if (error != null) {
      forget(request)
      return if (error == ERROR_ACCESS_DENIED) AuthResult.Denied
      else
        AuthResult.Failed(
          TraciveException.fromApiError(
            status = 400,
            code = error,
            message = parameters["error_description"],
          )
        )
    }

    val code = parameters["code"]
    if (code.isNullOrEmpty()) {
      forget(request)
      return AuthResult.Failed(
        TraciveException.InvalidRequest("The authorization callback carried no code.")
      )
    }

    // Erased before the exchange goes out rather than after it. An authorization code is single-use
    // and the record that redeems it is spent the moment it is sent, whatever the server answers —
    // so a failed exchange must not leave a verifier behind for a replayed redirect to try again.
    forget(request)
    return complete(code, record)
  }

  /**
   * The record a previous process left behind, or null when there is none to be had.
   *
   * A store that refuses the read is reported as absence, on the same terms as the write failure
   * [authorize] tolerates: the alternative is throwing out of a deep-link handler, and the caller
   * could do nothing with the distinction anyway. The user is told to authorize again either way.
   */
  private suspend fun recoveredRecord(): PendingAuthorization? =
    try {
      tokenStoreProvider.value.loadPending()
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Throwable) {
      null
    }

  /**
   * The id of the authorization this process is waiting on, or null when there is none.
   *
   * Read it once the browser is open and pass it back to [cancelPendingAuthorization] later. That
   * is the only way a cancel can be sure of what it is cancelling — see that method.
   *
   * It is memory-only, deliberately: a cancel is a *this process saw the browser close* signal, and
   * a process that did not open a browser has no such signal to disambiguate. So this is null after
   * a process death even when a record is still on disk, and a cancel naming an id from a previous
   * process is refused. Cancelling with no id still erases whatever is stored.
   */
  val pendingAuthorizationId: String?
    get() = pending?.id

  /**
   * Abandons the pending authorization, so nothing can complete it later.
   *
   * Call this when the browser closes without a redirect — the user pressed back, or dismissed the
   * Custom Tab. Nothing will ever arrive in that case, and without this the record sits on the
   * device with a live verifier next to the `state` it answers to until it ages out.
   *
   * **Pass [authorizationId] whenever the caller can.** A browser-closed signal arrives from the
   * Activity or the scene, not from the code that started the flow, and it can arrive late.
   * Consider the double-tap: `authorize()` runs twice, the second call supersedes the first, and
   * *then* the first browser's close event turns up. Cancelling unconditionally at that point kills
   * the second request — the one the user is actually looking at. With the id, a stale signal is
   * recognised and ignored.
   *
   * @param authorizationId the value [pendingAuthorizationId] held when the browser was opened.
   *   Null cancels whatever is pending, which is correct for a host that never has two
   *   authorizations in flight and is the hazard above for one that does.
   * @return whether a pending authorization was abandoned. False means there was nothing pending,
   *   or what was pending is not what [authorizationId] named.
   */
  suspend fun cancelPendingAuthorization(authorizationId: String? = null): Boolean {
    val request = pending
    if (authorizationId != null && authorizationId != request?.id) return false
    // The store is only consulted when this process has nothing of its own, which is the case where
    // the answer cannot be known without looking: a record left behind by an earlier process.
    if (request == null && recoveredRecord() == null) return false
    forget(request)
    return true
  }

  /**
   * Whether [url] is addressed to this client's own redirect URI.
   *
   * Compared as everything before the query and the fragment, which is scheme, authority and path —
   * the parts a redirect registration fixes. Case-insensitively, because scheme and host are
   * case-insensitive (RFC 3986 §3.1 and §3.2.2), and with a trailing slash ignored, because a
   * browser adds one to a bare-authority URL and a registration usually does not carry it.
   *
   * Getting this wrong in the permissive direction costs nothing: `state` is still checked
   * afterwards, and that is what actually decides whether a code may be redeemed. Getting it wrong
   * in the restrictive direction would drop a real redirect, so it errs towards accepting.
   */
  private fun isOurRedirect(url: String): Boolean =
    redirectTarget(url).equals(redirectTarget(mode.redirectUri), ignoreCase = true)

  private fun redirectTarget(url: String): String =
    url.substringBefore('#').substringBefore('?').trimEnd('/')

  // ---------------------------------------------------------------- tokens ----

  /** Whether a grant is stored. Not proof it is still valid — only the server can say that. */
  suspend fun isAuthorized(): Boolean = tokenStoreProvider.value.load() != null

  /** The scopes the user actually granted, or an empty list when there is no grant. */
  suspend fun grantedScopes(): List<String> =
    tokenStoreProvider.value.load()?.grantedScopes ?: emptyList()

  /**
   * Stores a grant obtained elsewhere, for a [ClientType.CONFIDENTIAL] integration.
   *
   * A confidential client exchanges the code on its own backend, so the SDK never sees the token
   * endpoint's reply. This is how those tokens get back to the SDK so that `checkScammer` can use
   * them. Pass the refresh token too when the backend is willing to delegate refreshing; without
   * one the SDK cannot refresh and a 401 surfaces as `TraciveException.InvalidToken`.
   *
   * @param expiresIn seconds until [accessToken] expires, as the token endpoint reported it.
   */
  suspend fun adoptTokens(
    accessToken: String,
    refreshToken: String? = null,
    expiresIn: Long? = null,
    grantedScopes: List<String> = emptyList(),
    tokenType: String = "Bearer",
  ) {
    tokenStoreProvider.value.save(
      TokenSet(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = expiresIn?.let { clock.now() + it.seconds },
        grantedScopes = grantedScopes,
        tokenType = tokenType,
      )
    )
  }

  /**
   * Revokes the grant server-side, best effort, then clears it locally whatever happened.
   *
   * The local clear is unconditional on purpose. A revoke that fails because the device is offline
   * must still end with the user signed out on this device; leaving a live refresh token behind
   * because a network call failed is the wrong side to err on.
   */
  suspend fun signOut() {
    val store = tokenStoreProvider.value
    val tokens = store.load()
    // Before the revoke round trip, not after it: from here on, anything already in flight that
    // comes back holding a token finds the generation moved and abandons it rather than writing it
    // to a store this method is about to empty. See [credentialGeneration].
    credentialGeneration += 1
    // The spent history describes the grant being thrown away, and nothing about the next one. A
    // fresh authorization starts from a clean slate.
    forgetSpentRefreshTokens()
    if (tokens != null) {
      // Revoking the refresh token is what actually ends the grant (RFC 7009 §2.1): the server
      // invalidates it and every access token issued under it. Revoking an access token alone would
      // leave the refresh token alive, and the "signed out" device could mint a new session.
      val revokingRefreshToken = tokens.refreshToken != null
      try {
        post(
          url = environment.baseUrl + PATH_REVOKE,
          form =
            formBody(
              listOf(
                "token" to (tokens.refreshToken ?: tokens.accessToken),
                "token_type_hint" to
                  if (revokingRefreshToken) TOKEN_TYPE_REFRESH else TOKEN_TYPE_ACCESS,
                AuthorizeUrlBuilder.PARAM_CLIENT_ID to mode.clientId,
              )
            ),
        )
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Throwable) {
        // Best effort. The local clear below is what the user asked for.
      }
    }
    cancelPendingAuthorization()
    store.clear()
  }

  /**
   * The access token to send, or a typed failure when there is no grant.
   *
   * A stored grant whose access token is **blank** is not the same thing as no grant. The refresh
   * token beside the empty field is a live credential, and it is the grant; the empty field is
   * damage, which a partial write, a save interrupted by a kill, or a restored backup can all leave
   * behind. So the token is renewed here, through [refreshedToken] and its single flight, and the
   * caller never learns anything happened.
   *
   * Renewing rather than re-consenting is the whole point. Asking the user to authorize again
   * throws away a grant that still works, to repair one empty field they cannot see and did not
   * cause.
   *
   * The 401 path cannot do this job. An empty bearer is not an *expired* token — a malformed one is
   * answered with `invalid_token`, which this SDK treats as terminal — so the damaged record would
   * never heal itself, and would spend a round trip failing permanently. The check therefore
   * belongs here, before the request is built. It costs the healthy path one blank test.
   *
   * @throws TraciveException.InvalidToken when nothing is stored, and when the stored access token
   *   is blank with no refresh token to renew it from. Both are terminal for the same reason: there
   *   is no credential left to spend. The caller has to authorize first; there is no anonymous
   *   mode.
   */
  internal suspend fun accessToken(): String {
    val stored =
      tokenStoreProvider.value.load()
        ?: throw TraciveException.InvalidToken(
          "No Tracive grant is stored on this device. Call authorize() first."
        )
    // The blank token is passed as the stale one, so a caller that arrives while another coroutine
    // is already renewing sees a stored token that differs from it and takes that instead of
    // starting a second refresh.
    if (stored.accessToken.isBlank()) return refreshedToken(stored.accessToken)
    return stored.accessToken
  }

  /**
   * Refreshes the access token, **single-flight**, and returns the new one.
   *
   * Exactly one refresh happens no matter how many callers arrive at once. That is not an
   * optimisation. The refresh token rotates, and the server treats a *reused* one as evidence that
   * it was stolen — so two concurrent refreshes with the same token look like theft and revoke the
   * whole grant, signing the user out of an app that did nothing wrong.
   *
   * The mechanism: whoever holds [refreshMutex] performs the refresh; everyone else waits on the
   * lock and, on getting it, sees that the stored access token is no longer the one they failed
   * with and returns it without a second network call.
   *
   * That covers concurrent callers and nothing else. The second guard, [spentRefreshTokens], covers
   * the sequential case the lock cannot see: a refresh that got no answer at all leaves the store
   * holding a token that may already have been spent, and the next 401 minutes later would read it
   * back and send it. See the comment on the request below for what is released and what is not.
   *
   * @param staleAccessToken the token whose 401 triggered this, so a caller that lost the race can
   *   recognise that someone else already fixed it.
   * @throws TraciveException.GrantRevoked when the server rejects the refresh token. The stored
   *   grant is cleared first: it is worthless, and sending it again looks like a replay.
   * @throws TraciveException.InvalidToken when the stored refresh token has already gone out and
   *   the SDK never learned what became of it. Terminal on purpose — the user authorizes again
   *   rather than the SDK risking the grant on a second presentation.
   */
  internal suspend fun refreshedToken(staleAccessToken: String?): String {
    val store = tokenStoreProvider.value
    return refreshMutex.withLock {
      val current = store.load()
      if (current != null && staleAccessToken != null && current.accessToken != staleAccessToken) {
        // Another coroutine refreshed while this one waited for the lock.
        return@withLock current.accessToken
      }
      val refreshToken =
        current?.refreshToken
          ?: throw TraciveException.InvalidToken(
            "The access token was rejected and no refresh token is stored. Authorize again."
          )

      if (refreshToken in spentRefreshTokens) {
        // This exact token has already been handed to the server by an attempt that never learned
        // what the server did with it. Presenting it a second time is the reuse that costs the
        // grant, so it is refused here instead of on the wire.
        throw TraciveException.InvalidToken(
          "The stored refresh token has already been presented to the Tracive API and no answer " +
            "came back, so it will not be presented again. Authorize again."
        )
      }

      // Read before the request goes out, checked again before the reply is stored.
      val generation = credentialGeneration

      // Marked before the request leaves rather than after it returns, because a refresh that gets
      // no answer is precisely the case that has to be remembered, and by then there is nothing
      // left to remember it from.
      //
      // Whether the mark may come off again depends entirely on what kind of failure follows, and
      // there are only two kinds worth telling apart.
      //
      // A complete HTTP reply, whatever its status, settles the question. A token endpoint that
      // answers with an error has issued no tokens (RFC 6749 §5.2), so the refresh token was not
      // consumed and the mark is released below — without that, one 500 would end the grant for
      // good.
      //
      // No reply at all does not settle it. A socket timeout or a dropped connection is
      // indistinguishable from a success whose answer was lost, and in the second case the server
      // has already rotated the token and is waiting to see the new one. So the mark stays and the
      // caller is told to authorize again. That is the better trade rather than merely the more
      // cautious one: if the token really was consumed, the grant needs re-authorization anyway and
      // this avoids adding a revocation to it; if it was not, the whole cost is one consent the
      // user did not strictly need. Retrying loses outright in the first case and only draws in the
      // second.
      //
      // A connect-phase or DNS failure would in principle prove the request never arrived, and its
      // mark could safely be released. It is deliberately not detected: [HttpTransport] is
      // injectable, so the error is whatever some other library chose to throw, and matching on its
      // shape would be a guess about the exact ambiguous-versus-safe distinction this rule turns
      // on. The Flutter SDK declined the same shortcut for the same reason.
      markRefreshTokenSpent(refreshToken)

      val response =
        try {
          post(
            url = environment.baseUrl + PATH_TOKEN,
            form =
              formBody(
                listOf(
                  PARAM_GRANT_TYPE to GRANT_REFRESH_TOKEN,
                  PARAM_REFRESH_TOKEN to refreshToken,
                  AuthorizeUrlBuilder.PARAM_CLIENT_ID to mode.clientId,
                )
              ),
          )
        } catch (_: TraciveException) {
          // The transport's own error is not carried through. It is produced by whichever transport
          // the host installed, and this SDK does not put text it did not write into an exception
          // raised from a frame whose neighbouring values are a refresh token. Cancellation is not
          // caught here and propagates untouched, leaving the mark in place, which is the same safe
          // side of the same ambiguity.
          throw TraciveException.InvalidToken(
            "The refresh request failed before any answer arrived, so whether the refresh token " +
              "was consumed is unknown. It will not be presented again. Authorize again."
          )
        }

      if (!response.isSuccessful) {
        releaseRefreshToken(refreshToken)
        throw revocationAware(response, store)
      }

      val refreshed =
        tokenSetFrom(
          decodeTokenResponse(response),
          fallbackScopes = current.grantedScopes,
          previousRefreshToken = refreshToken,
        )
      if (refreshed.refreshToken == refreshToken) {
        // The reply carried no `refresh_token`, so the one just sent was carried forward and is
        // what the next refresh will present. The server answered, and an answer that issues no
        // refresh token has not rotated the one it was given — so the mark set before the request
        // comes off. Leaving it on would make the next refresh refuse the grant's only token, which
        // is the failure this whole history exists to avoid rather than to cause.
        releaseRefreshToken(refreshToken)
      }
      // The grant may have been discarded while this round trip was in flight. Writing now would
      // undo a sign-out the user has already been told succeeded, and would leave the *rotated*
      // refresh token — the newest one the server ever issued for this grant — on a device the
      // user believes is signed out. The new pair is abandoned instead, which is what the server's
      // own rotation already assumes for a token that is never presented.
      requireSameCredentialGeneration(generation, "the refresh was in flight")
      // Replace both halves at once: a reader must never see the new access token beside the old
      // refresh token, because the old one is now the thing the server watches for replays.
      store.save(refreshed)
      refreshed.accessToken
    }
  }

  /** Drops the stored grant. Used when the server says it is gone. */
  internal suspend fun clearTokens() {
    // A deliberate discard, on the same terms as signOut: nothing in flight may put it back.
    credentialGeneration += 1
    forgetSpentRefreshTokens()
    tokenStoreProvider.value.clear()
  }

  /**
   * Releases the HTTP client, when this instance built one. Idempotent.
   *
   * **Call this on any `TraciveOAuth` you constructed yourself without passing a `transport`.** The
   * constructor is public precisely so a partner can drive the flow on its own, and with no
   * transport supplied this class lazily builds a [KtorTransport] and owns it — which on Android
   * means an OkHttp dispatcher and a connection pool that outlive every reference to this object.
   *
   * There is nothing to do on the [id.tracive.sdk.client.TraciveClient] path: that client injects
   * its shared transport, so this instance does not own it and `TraciveClient.close()` releases it
   * exactly once.
   */
  fun close() {
    if (ownsTransport && transportProvider.isInitialized()) transportProvider.value.close()
  }

  // --------------------------------------------------------------- internal ----

  /**
   * Refuses to store a credential that was obtained before the grant was thrown away.
   *
   * Called immediately before every token write that follows a round trip, with no suspension
   * between the check and the write — so a sign-out either lands before this and the write is
   * abandoned, or lands after the store has already accepted it and clears it in the normal way.
   *
   * @param generation what [credentialGeneration] held when that round trip started.
   * @param during names what was in flight, for the message and nothing else.
   * @throws TraciveException.GrantRevoked always, when the generation has moved. It is the right
   *   shape for both callers: the grant this credential belongs to is gone, and the caller's next
   *   step is to authorize again rather than to retry.
   */
  private fun requireSameCredentialGeneration(generation: Long, during: String) {
    if (generation != credentialGeneration) {
      throw TraciveException.GrantRevoked("The grant was dropped while $during.")
    }
  }

  /**
   * Records [token] as having gone to the server, dropping the oldest entry once the history is
   * full. Called under [refreshMutex]; see [spentRefreshTokens].
   */
  private fun markRefreshTokenSpent(token: String) {
    val history = spentRefreshTokens
    if (token in history) return
    spentRefreshTokens = (history + token).takeLast(SPENT_REFRESH_TOKEN_HISTORY)
  }

  /**
   * Takes [token] back out of the history, for the one case that proves it was not consumed: the
   * server answered. Called under [refreshMutex]; see [spentRefreshTokens].
   */
  private fun releaseRefreshToken(token: String) {
    spentRefreshTokens = spentRefreshTokens.filterNot { it == token }
  }

  /** Forgets every spent token, because the grant they belong to is being discarded. */
  private fun forgetSpentRefreshTokens() {
    spentRefreshTokens = emptyList()
  }

  /** Exchanges the authorization code, or hands it back for a confidential client to exchange. */
  private suspend fun complete(code: String, record: PendingAuthorization): AuthResult {
    if (mode.clientType == ClientType.CONFIDENTIAL) {
      // No exchange, no storage: the client secret lives on the partner's backend and the SDK must
      // never hold one. The verifier goes with the code because without it the code is unusable.
      return AuthResult.CodeReceived(
        code = code,
        codeVerifier = record.codeVerifier,
        redirectUri = record.redirectUri,
      )
    }

    // Same race the refresh path guards, and the same rule: a sign-out landing while the code is
    // being exchanged wins. Storing afterwards would hand the user a live grant they had already
    // abandoned finishing.
    val generation = credentialGeneration
    return try {
      val response =
        post(
          url = environment.baseUrl + PATH_TOKEN,
          form =
            formBody(
              listOf(
                PARAM_GRANT_TYPE to GRANT_AUTHORIZATION_CODE,
                PARAM_CODE to code,
                // The redirect URI comes from the record, not from `mode`: RFC 6749 §4.1.3 requires
                // the exchange to repeat the one the authorize request actually sent.
                AuthorizeUrlBuilder.PARAM_REDIRECT_URI to record.redirectUri,
                AuthorizeUrlBuilder.PARAM_CLIENT_ID to mode.clientId,
                PARAM_CODE_VERIFIER to record.codeVerifier,
              )
            ),
        )
      if (!response.isSuccessful) {
        AuthResult.Failed(ApiErrors.toException(response))
      } else {
        val tokens = tokenSetFrom(decodeTokenResponse(response), fallbackScopes = mode.scopes)
        requireSameCredentialGeneration(generation, "the authorization was completing")
        tokenStoreProvider.value.save(tokens)
        AuthResult.Authorized(tokens.grantedScopes)
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (failure: TraciveException) {
      AuthResult.Failed(failure)
    } catch (failure: Throwable) {
      AuthResult.Failed(TraciveException.Network(cause = failure))
    }
  }

  private suspend fun post(url: String, form: String): HttpResponse {
    val request =
      HttpRequest(
        method = HttpMethod.POST,
        url = url,
        headers = mapOf("Accept" to "application/json"),
        body = form,
        contentType = FORM_CONTENT_TYPE,
      )
    return try {
      transportProvider.value.execute(request)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (failure: TraciveException) {
      throw failure
    } catch (failure: Throwable) {
      throw TraciveException.Network(cause = failure)
    }
  }

  /**
   * The error for a failed *refresh*, clearing the grant when the server says it is dead.
   *
   * Two shapes mean the same thing. The Tracive envelope says `grant_revoked`; a standards-shaped
   * authorization server says `invalid_grant` (RFC 6749 §5.2), which is also what a **reused**
   * refresh token returns. Both are terminal, and both must clear the stored grant — retrying with
   * a token the server has already retired is indistinguishable from an attack.
   */
  private suspend fun revocationAware(response: HttpResponse, store: TokenStore): TraciveException {
    val envelope = ApiErrors.parseEnvelope(response.body)
    val revoked =
      envelope.code == TraciveException.CODE_GRANT_REVOKED || envelope.code == ERROR_INVALID_GRANT
    if (revoked) {
      store.clear()
      return TraciveException.GrantRevoked(envelope.message)
    }
    return ApiErrors.toException(response)
  }

  private fun decodeTokenResponse(response: HttpResponse): TokenResponse =
    try {
      SdkJson.decodeFromString(TokenResponse.serializer(), response.body)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Throwable) {
      // The body is not quoted: a token endpoint's reply is a pair of live credentials.
      throw TraciveException.Unknown(
        status = response.status,
        message = "The token endpoint returned a reply the SDK could not read.",
      )
    }

  /**
   * The reply as a [TokenSet], filling in what a conforming server is allowed to leave out.
   *
   * @param fallbackScopes what to record when the reply names no scope.
   * @param previousRefreshToken the refresh token this round trip presented, or null when there was
   *   none to present. Only the refresh path has one; the code exchange is the grant's first round
   *   trip, so a reply that carries no refresh token there really does mean the grant has none.
   */
  private fun tokenSetFrom(
    response: TokenResponse,
    fallbackScopes: List<String>,
    previousRefreshToken: String? = null,
  ): TokenSet =
    TokenSet(
      accessToken = response.accessToken,
      // A server that omits `refresh_token` is saying "keep the one you have" (RFC 6749 §6), not
      // "you no longer have one". Dropping it would leave the next refresh with nothing to present,
      // and the user would be sent back through consent for a grant that never ended.
      refreshToken = response.refreshToken ?: previousRefreshToken,
      expiresAt = response.expiresIn?.let { clock.now() + it.seconds },
      // A server that omits `scope` granted what was asked for (RFC 6749 §5.1).
      grantedScopes = response.scope?.split(' ')?.filter { it.isNotBlank() } ?: fallbackScopes,
      tokenType = response.tokenType,
    )

  private inline fun runCatchingLaunch(block: () -> Unit) {
    try {
      block()
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Throwable) {
      // Nothing to do: the caller is told the app is missing either way.
    }
  }

  /**
   * One authorization attempt: the persistable [record], plus the [id] that is not persisted.
   *
   * [id] identifies *this* attempt so a cancellation raised for it cannot land on a later one; see
   * [cancelPendingAuthorization]. It is not `state`: `state` travels to the authorization server
   * and back, and an identifier a host is asked to hold on to should not be the same value an
   * attacker would like to learn. It is also the reason this class exists at all rather than a bare
   * [PendingAuthorization] field — the record is what survives a process, the id is what does not.
   */
  private class AuthorizationAttempt(val id: String, val record: PendingAuthorization)

  companion object {
    /** `POST` here to exchange a code or a refresh token. Relative to the API base URL. */
    const val PATH_TOKEN: String = "/oauth/token"

    /** `POST` here to revoke a token (RFC 7009). Relative to the API base URL. */
    const val PATH_REVOKE: String = "/oauth/revoke"

    /** RFC 6749 requires the token endpoint to take a form-encoded body. */
    const val FORM_CONTENT_TYPE: String = "application/x-www-form-urlencoded"

    /**
     * How long a started authorization stays completable. Ten minutes.
     *
     * The number is the authorization code's own server-side lifetime, and that is the whole
     * argument. After ten minutes the code the callback carries is already dead, so keeping the
     * verifier any longer buys no recovery — it only leaves a secret on disk and turns a hopeless
     * attempt into a round trip that can come back nothing but `invalid_grant`. Refusing locally
     * costs the user the same retry and says so immediately.
     *
     * Ten is also the window quoted for the install path, where the same expiry is the reason
     * nothing is resumed after a download; see [INSTALL_URL].
     */
    val DEFAULT_PENDING_AUTHORIZATION_MAX_AGE: Duration = 10.minutes

    /**
     * The install page a device without the Tracive app is sent to.
     *
     * A short link, deliberately: the flow stops here. Every Tracive SDK opens this same base URL
     * and may add exactly two optional, non-sensitive query parameters —
     * [INSTALL_ATTRIBUTION_PARAM] naming the *partner's* OAuth client, and
     * [INSTALL_UTM_SOURCE_PARAM] naming the SDK that opened it. Nothing else.
     *
     * **No OAuth parameter may ever appear here** — not `state`, `code_challenge`, `code_verifier`,
     * `partner_user_id`, `redirect_uri` or `scope`. They would be useless by the time the user came
     * back (the server-side request expires in ten minutes, and there is no deferred resume), and
     * this link transits a third-party link service, so anything on it is handed to someone else's
     * servers. A partner's user identifier going that way is not acceptable in production.
     */
    const val INSTALL_URL: String = "https://tracive.chottu.link/install"

    /** Query parameter carrying the attribution tag on [INSTALL_URL]. */
    const val INSTALL_ATTRIBUTION_PARAM: String = "ref"

    /** Query parameter naming which SDK opened [INSTALL_URL]. */
    const val INSTALL_UTM_SOURCE_PARAM: String = "utm_source"

    /** This SDK's value for [INSTALL_UTM_SOURCE_PARAM]. One per Tracive SDK. */
    const val INSTALL_UTM_SOURCE: String = "tracive-sdk-kotlin"

    /**
     * Why a blank install-link override is refused, as a stable token in the failure message.
     *
     * Every Tracive SDK rejects a blank override with this exact string, so an integration spanning
     * more than one of them can match on a single value. Branch on the token, never on the wording
     * around it: the sentence is free to change and the token is not.
     */
    const val REASON_BLANK_INSTALL_URL: String = "blank_install_url"

    /**
     * How many spent refresh tokens [spentRefreshTokens] remembers.
     *
     * Comfortably more rotations than a caller can realistically be behind, and an under-estimate
     * is not a correctness problem: see [spentRefreshTokens] for why the store, not this history,
     * is what decides which token goes on the wire. The Flutter SDK keeps the same number.
     */
    private const val SPENT_REFRESH_TOKEN_HISTORY = 8

    private const val PARAM_GRANT_TYPE = "grant_type"
    private const val PARAM_CODE = "code"
    private const val PARAM_CODE_VERIFIER = "code_verifier"
    private const val PARAM_REFRESH_TOKEN = "refresh_token"
    private const val GRANT_AUTHORIZATION_CODE = "authorization_code"
    private const val GRANT_REFRESH_TOKEN = "refresh_token"
    private const val PARAM_ERROR = "error"

    /**
     * The parameters an authorization redirect must carry at least one of.
     *
     * A URL on this client's own redirect that has none of them did not come from the authorization
     * server, so it is left alone rather than allowed to resolve a pending request.
     */
    private val OAUTH_CALLBACK_PARAMS =
      listOf(AuthorizeUrlBuilder.PARAM_STATE, PARAM_CODE, PARAM_ERROR)

    private const val ERROR_ACCESS_DENIED = "access_denied"
    private const val ERROR_INVALID_GRANT = "invalid_grant"
    private const val TOKEN_TYPE_REFRESH = "refresh_token"
    private const val TOKEN_TYPE_ACCESS = "access_token"

    /**
     * [INSTALL_URL] with [clientId] as the attribution tag and this SDK as the source, and nothing
     * else. The attribution tag is dropped when [clientId] is blank; both parameters are optional.
     */
    fun defaultInstallUrl(clientId: String): String {
      val parameters = mutableListOf<Pair<String, String>>()
      if (clientId.isNotBlank()) parameters += INSTALL_ATTRIBUTION_PARAM to clientId
      parameters += INSTALL_UTM_SOURCE_PARAM to INSTALL_UTM_SOURCE
      return INSTALL_URL +
        "?" +
        parameters.joinToString("&") { (name, value) ->
          name + "=" + AuthorizeUrlBuilder.percentEncode(value)
        }
    }

    /**
     * Applies the blank rule to an install-link override, wherever one is accepted.
     *
     * Shared so that [TraciveOAuth] and [id.tracive.sdk.client.TraciveClient] refuse the same
     * values with the same message. Null passes: absence means "use the default", which is a real
     * answer. A blank string does not, and never falls back to the default — see [installUrl].
     */
    internal fun requireUsableInstallUrl(installUrlOverride: String?) {
      require(installUrlOverride == null || installUrlOverride.isNotBlank()) {
        "The Tracive install link was set to a blank string ($REASON_BLANK_INSTALL_URL). Pass null " +
          "to use the default link, or a non-blank one to replace it."
      }
    }

    /** `a=1&b=2`, percent-encoded — the body shape RFC 6749 §4.1.3 requires. */
    internal fun formBody(parameters: List<Pair<String, String>>): String =
      parameters.joinToString("&") { (name, value) ->
        AuthorizeUrlBuilder.percentEncode(name) + "=" + AuthorizeUrlBuilder.percentEncode(value)
      }

    /**
     * The query parameters of [url], percent-decoded.
     *
     * Written out rather than delegated to a URL parser because there is no multiplatform one, and
     * because the shapes that matter here are narrow: `https://partner.example/cb?code=…&state=…`
     * and its custom-scheme equivalent. A fragment is dropped — the authorization *code* flow never
     * puts parameters there, and reading a fragment would mean accepting an implicit-flow response
     * this SDK does not support.
     */
    internal fun parseQueryParameters(url: String): Map<String, String> {
      val start = url.indexOf('?')
      if (start < 0 || start == url.lastIndex) return emptyMap()
      val query = url.substring(start + 1).substringBefore('#')
      if (query.isEmpty()) return emptyMap()
      val parameters = LinkedHashMap<String, String>()
      for (pair in query.split('&')) {
        if (pair.isEmpty()) continue
        val separator = pair.indexOf('=')
        val name = if (separator < 0) pair else pair.substring(0, separator)
        val value = if (separator < 0) "" else pair.substring(separator + 1)
        val decodedName = percentDecode(name)
        if (decodedName.isEmpty()) continue
        // First value wins. A duplicated `state` or `code` is a smuggling attempt, and the SDK
        // compares against the first one rather than letting a later copy overwrite it.
        if (!parameters.containsKey(decodedName)) parameters[decodedName] = percentDecode(value)
      }
      return parameters
    }

    /** The inverse of [AuthorizeUrlBuilder.percentEncode], tolerating `+` as a space. */
    internal fun percentDecode(value: String): String {
      if ('%' !in value && '+' !in value) return value
      val bytes = ArrayList<Byte>(value.length)
      var index = 0
      while (index < value.length) {
        val character = value[index]
        when {
          character == '+' -> {
            bytes.add(' '.code.toByte())
            index += 1
          }
          character == '%' && index + 2 < value.length -> {
            val high = hexValue(value[index + 1])
            val low = hexValue(value[index + 2])
            if (high < 0 || low < 0) {
              bytes.add(character.code.toByte())
              index += 1
            } else {
              bytes.add(((high shl 4) or low).toByte())
              index += 3
            }
          }
          else -> {
            for (byte in character.toString().encodeToByteArray()) bytes.add(byte)
            index += 1
          }
        }
      }
      return bytes.toByteArray().decodeToString()
    }

    private fun hexValue(character: Char): Int =
      when (character) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        in 'A'..'F' -> character - 'A' + 10
        else -> -1
      }
  }
}
