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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * The shape of the flow is fixed by the plan's §4.2 sequence and is not negotiable:
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
 * ### Driving it from a host app
 *
 * ```
 * // In a coroutine, typically a viewModelScope:
 * when (val result = client.oauth!!.authorize()) {
 *   is AuthResult.Authorized -> proceed(result.grantedScopes)
 *   is AuthResult.CodeReceived -> myBackend.exchange(result.code, result.codeVerifier, result.redirectUri)
 *   AuthResult.Denied -> showDeclinedMessage()
 *   AuthResult.AppNotInstalled -> showInstallMessage()  // the install page is already open
 *   is AuthResult.Failed -> showError(result.error)
 * }
 *
 * // Wherever the redirect arrives — Android onNewIntent, iOS onOpenURL:
 * client.oauth?.handleCallback(url)
 * ```
 *
 * [authorize] suspends until [handleCallback] delivers the redirect, so the whole outcome arrives
 * as one value. Two consequences worth stating plainly:
 * - If the user backs out of the browser without a redirect, nothing arrives. Call
 *   [cancelPendingAuthorization] when the browser closes, or cancel the calling coroutine.
 * - The verifier lives in memory, so if the host process is killed while the browser is in front,
 *   the pending request dies with it and the callback is refused. That fails *closed*: without the
 *   verifier the code cannot be redeemed by anyone, including this SDK.
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
   * Where a user without the Tracive app is sent.
   *
   * Must carry **no OAuth parameters** — see [installUrl]. A non-sensitive attribution tag naming
   * the client is fine and is what the default adds.
   */
  installUrlOverride: String? = null,

  /** An explicit authorize endpoint, for staging or a test double. */
  authorizeEndpointOverride: String? = null,
) {

  /**
   * Lazy on purpose. These four defaults reach for the platform — an Android `Context`, the
   * Keystore, the Keychain — and evaluating them in the constructor would make simply *building* a
   * client fail in a unit test, or on a host that injects its own implementations anyway.
   */
  private val transportProvider = lazy { transport ?: KtorTransport() }
  private val tokenStoreProvider = lazy { tokenStore ?: SecureTokenStore() }
  private val presenceProvider = lazy { appPresenceChecker ?: defaultAppPresenceChecker() }
  private val launcherProvider = lazy { authorizeLauncher ?: defaultAuthorizeLauncher() }

  private val ownsTransport: Boolean = transport == null

  private val urlBuilder = AuthorizeUrlBuilder(environment, authorizeEndpointOverride)

  /**
   * Serializes refreshes so a rotating refresh token is never spent twice. See [refreshedToken].
   */
  private val refreshMutex = Mutex()

  /**
   * The authorization currently waiting for its redirect.
   *
   * Written by [authorize] before the browser opens and read by [handleCallback] when the redirect
   * arrives, which are always far apart in wall-clock time. The value that actually crosses threads
   * is the [CompletableDeferred] inside it, and completing one is atomic.
   */
  private var pending: PendingAuthorization? = null

  /** The install page for a device without the Tracive app. Never carries OAuth parameters. */
  val installUrl: String =
    installUrlOverride?.trim()?.takeIf { it.isNotEmpty() } ?: defaultInstallUrl(mode.clientId)

  /**
   * The authorize endpoint in use, with no query string. Useful when verifying an App Link setup.
   */
  val authorizeEndpoint: String
    get() = urlBuilder.endpoint

  // ------------------------------------------------------------------ flow ----

  /**
   * Runs one authorization and suspends until it resolves.
   *
   * Returns [AuthResult.AppNotInstalled] without starting anything when the Tracive app is missing;
   * the install page is opened first so the user has somewhere to go. Otherwise the browser opens
   * and this call waits for [handleCallback], [cancelPendingAuthorization], or cancellation.
   *
   * Calling it again while one is pending supersedes the first: the earlier call returns
   * [AuthResult.Failed] rather than hanging forever, because only one `state` can be outstanding
   * and honouring a stale callback is exactly the confusion `state` exists to prevent.
   *
   * @param extraAuthorizeParams extra query parameters for the authorize URL. OAuth parameters
   *   cannot be overridden.
   */
  suspend fun authorize(extraAuthorizeParams: Map<String, String> = emptyMap()): AuthResult {
    if (!presenceProvider.value.isTraciveAppInstalled()) {
      // Fire-and-forget: the user is going to the store, and there is nothing to resume when they
      // come back. Failing to open the page is not worth turning into an error — the caller still
      // has to show its own "install Tracive first" message either way.
      runCatchingLaunch { launcherProvider.value.launchInstall(installUrl) }
      return AuthResult.AppNotInstalled
    }

    val pkce = PkceGenerator.generate()
    val state = PkceGenerator.randomState()
    val request = PendingAuthorization(state, pkce)

    pending
      ?.outcome
      ?.complete(
        CallbackOutcome.Failed(
          TraciveException.InvalidRequest("A newer authorization request superseded this one.")
        )
      )
    pending = request

    val url = urlBuilder.build(mode, pkce, state, extraAuthorizeParams)
    try {
      launcherProvider.value.launchAuthorize(url)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (failure: Throwable) {
      if (pending === request) pending = null
      return AuthResult.Failed(
        TraciveException.Unknown(
          message = "No browser is available to open the Tracive authorization page."
        )
      )
    }

    val outcome =
      try {
        request.outcome.await()
      } finally {
        if (pending === request) pending = null
      }

    return when (outcome) {
      is CallbackOutcome.Denied -> AuthResult.Denied
      is CallbackOutcome.Failed -> AuthResult.Failed(outcome.error)
      is CallbackOutcome.Code -> complete(outcome.code, request.pkce)
    }
  }

  /**
   * Hands a redirect URI back to the pending authorization.
   *
   * Safe to call from a platform callback — Android's `onNewIntent`, iOS's `onOpenURL` — because it
   * does not suspend. Any exchange happens on the coroutine that called [authorize].
   *
   * The `state` check happens here, before the code is used for anything: a callback whose `state`
   * does not match the one this SDK generated did not come from the request this SDK started, so
   * its code must not be redeemed. That case resolves [authorize] with
   * [AuthResult.Failed]`(InvalidRequest)`.
   *
   * @return false when there is nothing pending — a stray link, a duplicate delivery, or a callback
   *   that outlived the process. The host can treat false as "not mine".
   */
  fun handleCallback(url: String): Boolean {
    val request = pending ?: return false
    val parameters = parseQueryParameters(url)

    val state = parameters[AuthorizeUrlBuilder.PARAM_STATE]
    if (state == null || state != request.state) {
      request.outcome.complete(
        CallbackOutcome.Failed(
          TraciveException.InvalidRequest(
            "The authorization callback did not match this request (state mismatch)."
          )
        )
      )
      return true
    }

    val error = parameters["error"]
    if (error != null) {
      request.outcome.complete(
        if (error == ERROR_ACCESS_DENIED) CallbackOutcome.Denied
        else
          CallbackOutcome.Failed(
            TraciveException.fromApiError(
              status = 400,
              code = error,
              message = parameters["error_description"],
            )
          )
      )
      return true
    }

    val code = parameters["code"]
    if (code.isNullOrEmpty()) {
      request.outcome.complete(
        CallbackOutcome.Failed(
          TraciveException.InvalidRequest("The authorization callback carried no code.")
        )
      )
      return true
    }

    request.outcome.complete(CallbackOutcome.Code(code))
    return true
  }

  /**
   * Abandons the pending authorization, resolving [authorize] with [AuthResult.Denied].
   *
   * Call this when the browser closes without a redirect — the user pressed back, or dismissed the
   * Custom Tab. Nothing else will ever arrive in that case, so without this the [authorize] call
   * would wait forever.
   */
  fun cancelPendingAuthorization() {
    pending?.outcome?.complete(CallbackOutcome.Denied)
    pending = null
  }

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
   * @throws TraciveException.InvalidToken when nothing is stored. The caller has to authorize
   *   first; there is no anonymous mode.
   */
  internal suspend fun accessToken(): String =
    tokenStoreProvider.value.load()?.accessToken
      ?: throw TraciveException.InvalidToken(
        "No Tracive grant is stored on this device. Call authorize() first."
      )

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
   * @param staleAccessToken the token whose 401 triggered this, so a caller that lost the race can
   *   recognise that someone else already fixed it.
   * @throws TraciveException.GrantRevoked when the server rejects the refresh token. The stored
   *   grant is cleared first: it is worthless, and sending it again looks like a replay.
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

      val response =
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

      if (!response.isSuccessful) throw revocationAware(response, store)

      val refreshed =
        tokenSetFrom(decodeTokenResponse(response), fallbackScopes = current.grantedScopes)
      // Replace both halves at once: a reader must never see the new access token beside the old
      // refresh token, because the old one is now the thing the server watches for replays.
      store.save(refreshed)
      refreshed.accessToken
    }
  }

  /** Drops the stored grant. Used when the server says it is gone. */
  internal suspend fun clearTokens() {
    tokenStoreProvider.value.clear()
  }

  /** Releases the transport when this instance created it. */
  internal fun close() {
    if (ownsTransport && transportProvider.isInitialized()) transportProvider.value.close()
  }

  // --------------------------------------------------------------- internal ----

  /** Exchanges the authorization code, or hands it back for a confidential client to exchange. */
  private suspend fun complete(code: String, pkce: Pkce): AuthResult {
    if (mode.clientType == ClientType.CONFIDENTIAL) {
      // No exchange, no storage: the client secret lives on the partner's backend and the SDK must
      // never hold one. The verifier goes with the code because without it the code is unusable.
      return AuthResult.CodeReceived(
        code = code,
        codeVerifier = pkce.codeVerifier,
        redirectUri = mode.redirectUri,
      )
    }

    return try {
      val response =
        post(
          url = environment.baseUrl + PATH_TOKEN,
          form =
            formBody(
              listOf(
                PARAM_GRANT_TYPE to GRANT_AUTHORIZATION_CODE,
                PARAM_CODE to code,
                AuthorizeUrlBuilder.PARAM_REDIRECT_URI to mode.redirectUri,
                AuthorizeUrlBuilder.PARAM_CLIENT_ID to mode.clientId,
                PARAM_CODE_VERIFIER to pkce.codeVerifier,
              )
            ),
        )
      if (!response.isSuccessful) {
        AuthResult.Failed(ApiErrors.toException(response))
      } else {
        val tokens = tokenSetFrom(decodeTokenResponse(response), fallbackScopes = mode.scopes)
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

  private fun tokenSetFrom(response: TokenResponse, fallbackScopes: List<String>): TokenSet =
    TokenSet(
      accessToken = response.accessToken,
      refreshToken = response.refreshToken,
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

  private class PendingAuthorization(val state: String, val pkce: Pkce) {
    val outcome: CompletableDeferred<CallbackOutcome> = CompletableDeferred()
  }

  private sealed class CallbackOutcome {
    class Code(val code: String) : CallbackOutcome()

    object Denied : CallbackOutcome()

    class Failed(val error: TraciveException) : CallbackOutcome()
  }

  companion object {
    /** `POST` here to exchange a code or a refresh token. Relative to the API base URL. */
    const val PATH_TOKEN: String = "/oauth/token"

    /** `POST` here to revoke a token (RFC 7009). Relative to the API base URL. */
    const val PATH_REVOKE: String = "/oauth/revoke"

    /** RFC 6749 requires the token endpoint to take a form-encoded body. */
    const val FORM_CONTENT_TYPE: String = "application/x-www-form-urlencoded"

    /**
     * The install page a device without the Tracive app is sent to.
     *
     * A short link, deliberately: the flow stops here, and the only thing that may ride along is a
     * non-sensitive attribution tag naming the client. **No OAuth parameters** — not `state`, not
     * `code_challenge`, not `partner_user_id`. They would be useless by the time the user came back
     * (the server-side request expires in ten minutes) and they would put a partner's user
     * identifier through a third-party link service, which the plan rules out for production.
     */
    const val INSTALL_URL: String = "https://tracive.chottu.link/install"

    /** Query parameter carrying the attribution tag on [INSTALL_URL]. */
    const val INSTALL_ATTRIBUTION_PARAM: String = "ref"

    private const val PARAM_GRANT_TYPE = "grant_type"
    private const val PARAM_CODE = "code"
    private const val PARAM_CODE_VERIFIER = "code_verifier"
    private const val PARAM_REFRESH_TOKEN = "refresh_token"
    private const val GRANT_AUTHORIZATION_CODE = "authorization_code"
    private const val GRANT_REFRESH_TOKEN = "refresh_token"
    private const val ERROR_ACCESS_DENIED = "access_denied"
    private const val ERROR_INVALID_GRANT = "invalid_grant"
    private const val TOKEN_TYPE_REFRESH = "refresh_token"
    private const val TOKEN_TYPE_ACCESS = "access_token"

    /** [INSTALL_URL] with [clientId] as an attribution tag, and nothing else. */
    fun defaultInstallUrl(clientId: String): String =
      if (clientId.isBlank()) INSTALL_URL
      else
        INSTALL_URL +
          "?" +
          INSTALL_ATTRIBUTION_PARAM +
          "=" +
          AuthorizeUrlBuilder.percentEncode(clientId)

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
