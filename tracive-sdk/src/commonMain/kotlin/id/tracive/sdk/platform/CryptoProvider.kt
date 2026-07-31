package id.tracive.sdk.platform

/**
 * The two cryptographic primitives the SDK needs, and nothing else.
 *
 * Only the random source is platform-specific: unpredictability is a property of the operating
 * system's entropy pool and cannot be written in common code, while SHA-256 is a fixed function
 * that must produce identical bytes everywhere (see [Sha256]).
 */
object CryptoProvider {

  /**
   * [size] cryptographically secure random bytes, from the platform CSPRNG.
   *
   * This backs the PKCE `code_verifier` and the OAuth `state`. Both are security values: a verifier
   * an attacker can predict removes the entire protection PKCE provides against an intercepted
   * authorization code, and a predictable `state` removes the CSRF protection on the callback. A
   * general-purpose PRNG (`kotlin.random.Random`) is not acceptable for either.
   */
  fun randomBytes(size: Int): ByteArray {
    require(size > 0) { "randomBytes needs a positive size." }
    return secureRandomBytes(size)
  }

  /** The SHA-256 digest of [input]. */
  fun sha256(input: ByteArray): ByteArray = Sha256.digest(input)

  /**
   * The SHA-256 of [input]'s UTF-8 bytes, as lower-case hex.
   *
   * This is how a cache key is derived from a `searchQueryIndex`: the folded key contains the
   * caller's phone number, email or account number in cleartext, and the digest is what makes it
   * safe to write to disk.
   */
  fun sha256Hex(input: String): String = Sha256.digest(input.encodeToByteArray()).toLowerHex()
}

/**
 * Platform CSPRNG: `SecureRandom` on Android, `SecRandomCopyBytes` on iOS.
 *
 * Internal on purpose — [CryptoProvider.randomBytes] is the public door, and it validates the size
 * before any platform call.
 */
internal expect fun secureRandomBytes(size: Int): ByteArray
