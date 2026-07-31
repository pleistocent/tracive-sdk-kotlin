package id.tracive.sdk.platform

import java.security.SecureRandom

/**
 * `SecureRandom`, seeded by the OS entropy pool.
 *
 * One instance is kept and reused: constructing a `SecureRandom` per call would re-seed from
 * `/dev/urandom` every time, which is slower and buys nothing — the instance reseeds itself.
 *
 * `SecureRandom()` with no arguments is the correct call on modern Android. Naming a provider or an
 * algorithm here is how apps end up on the deterministic `SHA1PRNG` path, which is exactly the bug
 * that produced predictable PKCE verifiers in other SDKs.
 */
private val secureRandom: SecureRandom by lazy { SecureRandom() }

internal actual fun secureRandomBytes(size: Int): ByteArray =
  ByteArray(size).also { secureRandom.nextBytes(it) }
