package id.tracive.sdk.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/**
 * `SecRandomCopyBytes`, the system CSPRNG.
 *
 * A failure throws rather than degrading to a weaker source. The only two callers are the PKCE
 * verifier and the OAuth `state`, and a predictable value in either silently removes a security
 * property — the verifier stops protecting an intercepted authorization code, and the state stops
 * protecting the callback from CSRF. A loud failure on a device whose entropy pool is broken is
 * strictly better than a quiet downgrade nobody can observe.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(size: Int): ByteArray {
  val bytes = ByteArray(size)
  val status = bytes.usePinned { pinned ->
    SecRandomCopyBytes(kSecRandomDefault, size.convert(), pinned.addressOf(0))
  }
  check(status == errSecSuccess) {
    "SecRandomCopyBytes failed with OSStatus $status; the Tracive SDK will not fall back to a " +
      "weaker random source."
  }
  return bytes
}
