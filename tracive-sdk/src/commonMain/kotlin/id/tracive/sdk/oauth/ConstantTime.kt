package id.tracive.sdk.oauth

/**
 * Compares two secrets in time that does not depend on where they first differ.
 *
 * `==` on a `String` returns as soon as it finds a differing character, so how long it takes says
 * how much of the value was guessed correctly. That is what turns a comparison into an oracle:
 * given enough measured attempts an attacker recovers the secret a character at a time, instead of
 * having to guess all of it at once.
 *
 * **The honest scope, for the one caller this has today.** The OAuth `state` it guards is 32 random
 * bytes, and an attacker would have to be an app on the same device, feeding
 * [TraciveOAuth.handleCallback] one URL at a time, timing a suspending call on a phone. That is not
 * a practical attack, and this is not being changed because it was one. It is being changed because
 * the Swift, Flutter and React Native SDKs all compare `state` this way deliberately, so leaving
 * `!=` here was a difference between the four that nobody chose — and unexamined differences in
 * this kind of code are how the real ones survive.
 *
 * The length is not hidden: unequal lengths return immediately. Padding to a common length would
 * only move the leak, and the length of a `state` this SDK generated is a constant anyway.
 */
internal fun constantTimeEquals(first: String, second: String): Boolean {
  // Compared as bytes so a multi-byte character costs the same as any other. A `state` this SDK
  // generated is base64url and therefore ASCII, but a callback's is whatever arrived.
  val left = first.encodeToByteArray()
  val right = second.encodeToByteArray()
  if (left.size != right.size) return false

  // Accumulates every difference instead of returning at the first one, so the loop always runs to
  // the end. `or` rather than `+`: it cannot overflow back to zero.
  var difference = 0
  for (index in left.indices) {
    difference = difference or (left[index].toInt() xor right[index].toInt())
  }
  return difference == 0
}
