package id.tracive.sdk.cache

/*
 * Small byte helpers shared by the cache's crypto. Kotlin's common standard library has no hex
 * codec that is stable across the Kotlin versions this SDK supports and no constant-time compare,
 * so both live here rather than behind an expect/actual — the cache must behave identically on
 * Android and iOS, and two platform implementations are two chances to disagree.
 */

private const val HEX_DIGITS = "0123456789abcdef"

/** Lower-case hex. Used for the cache key, which is a SHA-256 digest and never an identifier. */
internal fun ByteArray.toHex(): String {
  val out = StringBuilder(size * 2)
  for (b in this) {
    val v = b.toInt() and 0xFF
    out.append(HEX_DIGITS[v ushr 4])
    out.append(HEX_DIGITS[v and 0x0F])
  }
  return out.toString()
}

/**
 * Compares two byte arrays without short-circuiting on the first differing byte.
 *
 * Used for the GCM tag. A `contentEquals` returns as soon as it finds a difference, and the time it
 * took is a function of how many leading bytes an attacker guessed correctly — enough to forge a
 * tag one byte at a time given enough attempts.
 */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
  if (a.size != b.size) return false
  var diff = 0
  for (i in a.indices) {
    diff = diff or (a[i].toInt() xor b[i].toInt())
  }
  return diff == 0
}

/**
 * Overwrites a byte array in place. Best effort: it cannot undo a copy the runtime already made.
 */
internal fun ByteArray.wipe() {
  for (i in indices) this[i] = 0
}
