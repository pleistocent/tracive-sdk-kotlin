package id.tracive.sdk.oauth

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
 * Where the SDK keeps the grant between calls.
 *
 * The shipped implementation is the platform's hardware-backed store —
 * [id.tracive.sdk.platform.SecureTokenStore], which is the Android Keystore or the iOS Keychain. A
 * custom implementation is allowed and occasionally necessary (a host with its own encrypted store,
 * or a confidential client that keeps tokens elsewhere), but it must meet the same bar: **never
 * `SharedPreferences`, `UserDefaults` or a plain file in the clear.**
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

  /** Removes the stored grant. Called on sign-out and whenever the server says it was revoked. */
  suspend fun clear()
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

  override suspend fun load(): TokenSet? = mutex.withLock { tokens }

  override suspend fun save(tokens: TokenSet) {
    mutex.withLock { this.tokens = tokens }
  }

  override suspend fun clear() {
    mutex.withLock { tokens = null }
  }

  override fun toString(): String = "InMemoryTokenStore(present=${tokens != null})"
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

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
  }

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
    json.encodeToString(
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
        json.decodeFromString(StoredTokens.serializer(), text)
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
