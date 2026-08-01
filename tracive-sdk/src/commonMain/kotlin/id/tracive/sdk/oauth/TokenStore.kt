package id.tracive.sdk.oauth

import kotlin.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One authorization grant's live credentials.
 *
 * Not a `data class`: the generated `toString` would print an access token and a refresh token into
 * every log line that touches a client, and the generated `copy` would make it trivial to leak one
 * into an unrelated object. Both values are bearer credentials — anyone holding them can spend the
 * user's credits until the grant is revoked.
 *
 * The refresh token is the more dangerous of the two. It rotates: the server issues a new one on
 * every refresh and treats a *reused* one as evidence of theft, revoking the whole grant. That is
 * why [TokenStore.save] must replace the pair atomically and why the SDK refreshes single-flight.
 */
class TokenSet(
  /** The bearer token sent as `Authorization: Bearer …`. A JWT with roughly an hour of life. */
  val accessToken: String,

  /**
   * The rotating refresh token, when the grant issued one.
   *
   * Null for a client that received its tokens from a partner backend without one, in which case
   * the SDK cannot refresh and a 401 surfaces to the caller as `InvalidToken`.
   */
  val refreshToken: String? = null,

  /**
   * When [accessToken] stops being accepted, when the server said. Null when it did not.
   *
   * The SDK never reads this to decide whether to send the token. It sends it and refreshes on the
   * 401, single-flight, which costs one wasted round trip per expiry and is worth it: a device
   * clock running fast would otherwise make every call refresh first, rotating the refresh token
   * far more often than the grant needs. Compare it yourself if you want to pre-warm a session.
   */
  val expiresAt: Instant? = null,

  /**
   * The scopes actually granted, which can be narrower than those requested.
   *
   * Worth checking before offering a feature that needs one, rather than discovering the gap as a
   * `TraciveException.MissingScope` in the middle of a user's flow.
   */
  val grantedScopes: List<String> = emptyList(),

  /** The token type the server named. `Bearer` in practice; kept so a reply is not misread. */
  val tokenType: String = "Bearer",
) {

  init {
    require(accessToken.isNotBlank()) { "A TokenSet needs an access token." }
  }

  /**
   * Redacted. Neither token ever reaches a log line, a crash report or a watch window.
   *
   * [expiresAt] is safe to print and is left in: it is a timestamp, not a credential, and it is
   * usually the field someone is squinting at when they look at one of these.
   */
  override fun toString(): String =
    "TokenSet(tokenType=$tokenType, scopes=$grantedScopes, expiresAt=$expiresAt, tokens=redacted)"
}

/**
 * An authorization that has been started and is waiting for its redirect.
 *
 * Written down before the browser opens rather than merely held in a field, because consent does
 * not happen in this app. The host process spends the whole of it in the background, which on
 * Android makes it an ordinary candidate for being killed, and [codeVerifier] is the half of the
 * PKCE pair that never leaves the device — so if it dies with the process, the returning callback
 * names a `state` nothing can match and a code nobody can redeem.
 *
 * Every field here except [createdAt] is a secret or close to one, so an implementation of
 * [TokenStore] must keep this record exactly where it keeps [TokenSet]: the platform's encrypted
 * store, never `SharedPreferences`, `UserDefaults` or a plain file.
 */
class PendingAuthorization(
  /** The CSRF nonce that travelled in the authorize URL, and must come back unchanged. */
  val state: String,

  /**
   * The PKCE verifier whose SHA-256 digest was sent as `code_challenge`.
   *
   * The one value that makes the returned authorization code redeemable. It is why this record is a
   * secret and why it never reaches a log line or a `toString`.
   */
  val codeVerifier: String,

  /** The `redirect_uri` that was sent, which the token exchange has to repeat verbatim. */
  val redirectUri: String,

  /** When the attempt was started, by the SDK's clock. Read by [isStaleAt] and nothing else. */
  val createdAt: Instant,
) {

  init {
    require(state.isNotBlank()) { "A PendingAuthorization needs a state." }
    require(codeVerifier.isNotBlank()) { "A PendingAuthorization needs a code verifier." }
  }

  /**
   * Whether this attempt was started more than [maxAge] before [now].
   *
   * A clock that has moved *backwards* since the attempt started — a manual change, an NTP
   * correction — yields a negative age and reads as fresh. That is the right way round: the
   * authorization code has its own server-side lifetime, so the worst a too-generous local answer
   * costs is one round trip that comes back `invalid_grant`, while a too-strict one would refuse a
   * consent the user really did give.
   */
  fun isStaleAt(now: Instant, maxAge: Duration): Boolean = now - createdAt > maxAge

  /** Redacted. Neither the verifier nor the `state` ever reaches a log line. */
  override fun toString(): String =
    "PendingAuthorization(redirectUri=$redirectUri, createdAt=$createdAt, " +
      "state=redacted, codeVerifier=redacted)"
}

/**
 * Where the SDK keeps the grant, and the authorization in flight, between calls.
 *
 * The shipped implementation is the platform's hardware-backed store —
 * [id.tracive.sdk.platform.SecureTokenStore], which is the Android Keystore or the iOS Keychain. A
 * custom implementation is allowed and occasionally necessary (a host with its own encrypted store,
 * or a confidential client that keeps tokens elsewhere), but it must meet the same bar: **never
 * `SharedPreferences`, `UserDefaults` or a plain file in the clear.** That bar covers
 * [PendingAuthorization] as well as [TokenSet]; the pending record carries a live PKCE verifier.
 *
 * Implementations must tolerate concurrent callers. The SDK refreshes single-flight, so [save] is
 * called from exactly one coroutine at a time, but [load] is called from many.
 */
interface TokenStore {

  /** The stored grant, or null when there is none. */
  suspend fun load(): TokenSet?

  /**
   * Replaces the stored grant with [tokens].
   *
   * Atomic with respect to [load]: a reader must never observe the new access token next to the old
   * refresh token. Sending a superseded refresh token is what trips the server's reuse detection,
   * and that revokes the entire grant rather than failing one call.
   *
   * **Must throw when it did not store.** Returning normally is the SDK's only signal that the
   * grant is safe on the device, and the caller acts on that signal by discarding the previous
   * tokens. An implementation that swallows a write failure leaves the device holding a refresh
   * token the server has already retired, which the server reads as theft the next time it is
   * presented. Throw
   * [TraciveException.TokenStorageFailed][id.tracive.sdk.TraciveException.TokenStorageFailed] —
   * carrying the platform's numeric status and never the tokens themselves.
   */
  suspend fun save(tokens: TokenSet)

  /**
   * Removes the stored grant **and any pending authorization**. Called on sign-out and whenever the
   * server says the grant was revoked.
   *
   * Dropping the pending record here as well is deliberate. Sign-out is the user saying "nothing of
   * mine stays on this device", and a half-finished authorization is something of theirs: its
   * verifier would still redeem a code that arrived afterwards.
   */
  suspend fun clear()

  /**
   * The authorization waiting for its redirect, or null when there is none.
   *
   * @return null for an unreadable record — corrupt, truncated, or written by a newer schema —
   *   exactly as [load] does. There is nothing safe to salvage from half a PKCE pair.
   */
  suspend fun loadPending(): PendingAuthorization?

  /**
   * Stores [pending], or erases the stored one when it is null.
   *
   * Called immediately before the authorize URL is opened and again on every terminal outcome, so
   * that a spent or abandoned attempt cannot be replayed by a redirect that arrives later.
   *
   * **Must throw when it did not store**, on the same terms as [save] and carrying the same
   * [TraciveException.TokenStorageFailed][id.tracive.sdk.TraciveException.TokenStorageFailed].
   * Unlike [save], the SDK treats that failure as non-fatal and carries on with the authorization
   * anyway — see [TraciveOAuth.authorize] — but it can only make that judgement if this method
   * reports honestly rather than swallowing the refusal.
   */
  suspend fun savePending(pending: PendingAuthorization?)
}

/**
 * A [TokenStore] that keeps the grant in memory for the life of the process.
 *
 * For tests, and for a host that deliberately wants the user to re-authorize on every launch.
 * **Not** a substitute for the platform store in a shipping app: the user would face the consent
 * screen every cold start, which trains exactly the habit the app-to-app flow exists to prevent.
 */
class InMemoryTokenStore(initial: TokenSet? = null) : TokenStore {

  private val mutex = Mutex()
  private var tokens: TokenSet? = initial
  private var pending: PendingAuthorization? = null

  override suspend fun load(): TokenSet? = mutex.withLock { tokens }

  override suspend fun save(tokens: TokenSet) {
    mutex.withLock { this.tokens = tokens }
  }

  override suspend fun clear() {
    mutex.withLock {
      tokens = null
      pending = null
    }
  }

  override suspend fun loadPending(): PendingAuthorization? = mutex.withLock { pending }

  override suspend fun savePending(pending: PendingAuthorization?) {
    mutex.withLock { this.pending = pending }
  }

  override fun toString(): String =
    "InMemoryTokenStore(present=${tokens != null}, pending=${pending != null})"
}

/**
 * The one JSON configuration every stored record uses.
 *
 * Shared by [TokenSetCodec] and [PendingAuthorizationCodec] so the two can never drift into
 * disagreeing about unknown keys or explicit nulls — a difference that would only ever surface as a
 * blob one of them wrote and the other could not read back.
 */
private val storageJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
  explicitNulls = false
}

/**
 * The on-disk form of a [TokenSet], for the platform stores.
 *
 * The Keystore and the Keychain both store bytes, so the token set has to be serialized before it
 * is encrypted. Doing that here rather than twice in `androidMain` and `iosMain` means one format,
 * one set of field names, and one place where a future field is added.
 *
 * The JSON produced here is *always* encrypted before it is written. It is never a file on its own.
 */
internal object TokenSetCodec {

  /** Bump when the stored shape changes so an old blob is discarded rather than mis-read. */
  const val VERSION: Int = 1

  @Serializable
  private class StoredTokens(
    @SerialName("v") val version: Int,
    @SerialName("at") val accessToken: String,
    @SerialName("rt") val refreshToken: String? = null,
    @SerialName("exp") val expiresAtEpochSeconds: Long? = null,
    @SerialName("scopes") val grantedScopes: List<String> = emptyList(),
    @SerialName("type") val tokenType: String = "Bearer",
  )

  /** [tokens] as a JSON string, ready to be encrypted. */
  fun encode(tokens: TokenSet): String =
    storageJson.encodeToString(
      StoredTokens.serializer(),
      StoredTokens(
        version = VERSION,
        accessToken = tokens.accessToken,
        refreshToken = tokens.refreshToken,
        expiresAtEpochSeconds = tokens.expiresAt?.epochSeconds,
        grantedScopes = tokens.grantedScopes,
        tokenType = tokens.tokenType,
      ),
    )

  /**
   * The inverse of [encode].
   *
   * @return null when [text] is not a blob this version wrote — corrupt, truncated, or from a
   *   future schema. Null means "no stored grant", which costs the user one re-authorization and is
   *   the only safe reading: half-decoded credentials would be sent and rejected, and a rejected
   *   refresh token looks like theft to the server.
   */
  fun decode(text: String): TokenSet? {
    val stored =
      try {
        storageJson.decodeFromString(StoredTokens.serializer(), text)
      } catch (_: Throwable) {
        return null
      }
    if (stored.version != VERSION || stored.accessToken.isBlank()) return null
    return TokenSet(
      accessToken = stored.accessToken,
      refreshToken = stored.refreshToken,
      expiresAt = stored.expiresAtEpochSeconds?.let { Instant.fromEpochSeconds(it) },
      grantedScopes = stored.grantedScopes,
      tokenType = stored.tokenType,
    )
  }
}

/**
 * The on-disk form of a [PendingAuthorization], for the platform stores.
 *
 * Kept beside [TokenSetCodec] and for the same reason: the record is serialized once, here, so that
 * `androidMain` and `iosMain` cannot disagree about its shape. Like a token blob, the JSON produced
 * here is *always* encrypted before it is written — it carries a live PKCE verifier.
 *
 * It is stored separately from the grant rather than folded into it. The two have unrelated
 * lifetimes: a grant outlives many launches, while this record is written minutes before it is
 * deleted, and every terminal outcome erases it without touching the tokens.
 */
internal object PendingAuthorizationCodec {

  /** Bump when the stored shape changes so an old blob is discarded rather than mis-read. */
  const val VERSION: Int = 1

  @Serializable
  private class StoredPending(
    @SerialName("v") val version: Int,
    @SerialName("st") val state: String,
    @SerialName("cv") val codeVerifier: String,
    @SerialName("ru") val redirectUri: String,
    @SerialName("ts") val createdAtEpochSeconds: Long,
  )

  /** [pending] as a JSON string, ready to be encrypted. */
  fun encode(pending: PendingAuthorization): String =
    storageJson.encodeToString(
      StoredPending.serializer(),
      StoredPending(
        version = VERSION,
        state = pending.state,
        codeVerifier = pending.codeVerifier,
        redirectUri = pending.redirectUri,
        createdAtEpochSeconds = pending.createdAt.epochSeconds,
      ),
    )

  /**
   * The inverse of [encode].
   *
   * @return null when [text] is not a blob this version wrote, or when either half of the PKCE pair
   *   came back blank. There is no partial recovery available: without both the `state` and the
   *   verifier the attempt cannot be matched or redeemed, and reporting "none pending" costs the
   *   user one retry rather than an error they cannot act on.
   */
  fun decode(text: String): PendingAuthorization? {
    val stored =
      try {
        storageJson.decodeFromString(StoredPending.serializer(), text)
      } catch (_: Throwable) {
        return null
      }
    if (stored.version != VERSION) return null
    if (stored.state.isBlank() || stored.codeVerifier.isBlank()) return null
    return PendingAuthorization(
      state = stored.state,
      codeVerifier = stored.codeVerifier,
      redirectUri = stored.redirectUri,
      createdAt = Instant.fromEpochSeconds(stored.createdAtEpochSeconds),
    )
  }
}
