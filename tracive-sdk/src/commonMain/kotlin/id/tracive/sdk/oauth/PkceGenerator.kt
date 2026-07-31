package id.tracive.sdk.oauth

import id.tracive.sdk.platform.Base64Url
import id.tracive.sdk.platform.CryptoProvider

/**
 * One PKCE (RFC 7636) proof pair.
 *
 * [codeVerifier] is a secret and stays on this device until the token exchange; [codeChallenge] is
 * its SHA-256 digest and is the only half that travels in the authorize URL. That asymmetry is the
 * whole mechanism: an attacker who intercepts the authorization code — a malicious app claiming the
 * same redirect, a link handler on a rooted device — still cannot redeem it without the verifier.
 *
 * Not a `data class`, so the verifier does not reach a log through a generated `toString`.
 */
class Pkce(
  /** The secret. Sent only in the token exchange body, over TLS, once. */
  val codeVerifier: String,

  /** `BASE64URL(SHA-256(codeVerifier))`, unpadded. Safe to put in a URL. */
  val codeChallenge: String,
) {

  /** Always `S256`. See [PkceGenerator] for why `plain` is not an option. */
  val codeChallengeMethod: String
    get() = PkceGenerator.METHOD_S256

  /** Redacted: [codeVerifier] is a live credential for as long as the code is unredeemed. */
  override fun toString(): String = "Pkce(method=$codeChallengeMethod, verifier=redacted)"
}

/**
 * Generates the PKCE proof pair and the OAuth `state`.
 *
 * **S256 only.** RFC 7636 also defines `plain`, where the challenge *is* the verifier; the SDK does
 * not implement it and there is no switch to turn it on. `plain` gives an attacker who can read the
 * authorize URL — an OS-level link handler, a shoulder-surfer, a URL captured in a browser history
 * or a proxy log — everything needed to redeem the code, which is precisely the attack PKCE exists
 * to stop. RFC 8252 §8.1 requires S256 for native apps for exactly this reason.
 *
 * The randomness comes from [CryptoProvider.randomBytes], the platform CSPRNG.
 * `kotlin.random.Random` would be a bug here rather than a shortcut: a predictable verifier removes
 * PKCE's protection entirely, and a predictable `state` removes the CSRF protection on the
 * callback.
 *
 * `PkceGeneratorTest` pins [challengeFor] against the RFC 7636 appendix B vector, so a change to
 * the digest or to the base64url padding rule fails there rather than in a partner's integration.
 */
object PkceGenerator {

  /** The only challenge method this SDK supports. */
  const val METHOD_S256: String = "S256"

  /** RFC 7636 §4.1: a verifier is at least 43 characters. */
  const val MIN_VERIFIER_LENGTH: Int = 43

  /** RFC 7636 §4.1: and at most 128. */
  const val MAX_VERIFIER_LENGTH: Int = 128

  /**
   * The SDK's default verifier length.
   *
   * 64 base64url characters carry 48 bytes — 384 bits — of entropy, comfortably above the 256 bits
   * RFC 7636 asks for, and short enough that the authorize URL stays well inside every browser's
   * length limit.
   */
  const val DEFAULT_VERIFIER_LENGTH: Int = 64

  /** Bytes of entropy behind a `state` value. */
  const val DEFAULT_STATE_BYTES: Int = 32

  /**
   * A fresh verifier and its challenge.
   *
   * @param verifierLength characters in the verifier, within [MIN_VERIFIER_LENGTH] and
   *   [MAX_VERIFIER_LENGTH].
   * @throws IllegalArgumentException if [verifierLength] is outside that range. Silently clamping
   *   would let a caller believe it had asked for more entropy than it got.
   */
  fun generate(verifierLength: Int = DEFAULT_VERIFIER_LENGTH): Pkce {
    require(verifierLength in MIN_VERIFIER_LENGTH..MAX_VERIFIER_LENGTH) {
      "A PKCE verifier is $MIN_VERIFIER_LENGTH..$MAX_VERIFIER_LENGTH characters (RFC 7636 §4.1)."
    }
    val verifier = randomUnreserved(verifierLength)
    return Pkce(codeVerifier = verifier, codeChallenge = challengeFor(verifier))
  }

  /**
   * `BASE64URL(SHA-256(ASCII(codeVerifier)))`, unpadded — RFC 7636 §4.2.
   *
   * The padding matters. A trailing `=` makes the authorization server compute a different string
   * from the one it stored, and the exchange then fails with a bare `invalid_grant` that says
   * nothing about padding. That is why the SDK encodes base64url itself rather than borrowing a
   * platform encoder whose padding default differs per platform.
   */
  fun challengeFor(codeVerifier: String): String {
    require(codeVerifier.isNotEmpty()) { "A PKCE verifier cannot be empty." }
    return Base64Url.encode(CryptoProvider.sha256(codeVerifier.encodeToByteArray()))
  }

  /**
   * A fresh `state` value: the CSRF token that ties a callback to the request this SDK started.
   *
   * Without it, any app or page that can reach the redirect URI could hand the SDK an authorization
   * code from a *different* grant — the classic login-CSRF, which ends with the victim's app
   * operating on the attacker's account.
   */
  fun randomState(byteCount: Int = DEFAULT_STATE_BYTES): String {
    require(byteCount >= 16) { "An OAuth state needs at least 16 bytes of entropy." }
    return Base64Url.encode(CryptoProvider.randomBytes(byteCount))
  }

  /**
   * [length] random characters from the base64url alphabet.
   *
   * That alphabet (`A-Z a-z 0-9 - _`) is a subset of RFC 7636's `unreserved` set, so the result
   * needs no percent-encoding in a URL and none in a form body. Truncating the encoded string is
   * safe for the same reason — every character stands alone.
   */
  private fun randomUnreserved(length: Int): String {
    val byteCount = (length * 3 + 3) / 4
    val encoded = Base64Url.encode(CryptoProvider.randomBytes(byteCount))
    return if (encoded.length <= length) encoded else encoded.substring(0, length)
  }
}
