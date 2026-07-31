package id.tracive.sdk.platform

/**
 * Unpadded base64url (RFC 4648 §5), the encoding RFC 7636 requires for a PKCE `code_challenge`.
 *
 * Written out rather than delegated to a platform API because the padding rule differs between
 * them: `java.util.Base64.getUrlEncoder()` pads by default and iOS has no base64url variant at all.
 * A single `=` on the end of a challenge is enough for the authorization server to reject the
 * exchange, and the failure surfaces as a generic `invalid_grant` that says nothing about padding.
 *
 * [decode] is here for the token stores, which round-trip an encrypted blob through a string.
 */
internal object Base64Url {

  private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

  /** [bytes] as unpadded base64url. */
  fun encode(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val out = StringBuilder((bytes.size * 4 + 2) / 3)
    var index = 0
    while (index + 2 < bytes.size) {
      val chunk =
        ((bytes[index].toInt() and 0xFF) shl 16) or
          ((bytes[index + 1].toInt() and 0xFF) shl 8) or
          (bytes[index + 2].toInt() and 0xFF)
      out.append(ALPHABET[(chunk ushr 18) and 0x3F])
      out.append(ALPHABET[(chunk ushr 12) and 0x3F])
      out.append(ALPHABET[(chunk ushr 6) and 0x3F])
      out.append(ALPHABET[chunk and 0x3F])
      index += 3
    }
    when (bytes.size - index) {
      1 -> {
        val chunk = (bytes[index].toInt() and 0xFF) shl 16
        out.append(ALPHABET[(chunk ushr 18) and 0x3F])
        out.append(ALPHABET[(chunk ushr 12) and 0x3F])
      }
      2 -> {
        val chunk =
          ((bytes[index].toInt() and 0xFF) shl 16) or ((bytes[index + 1].toInt() and 0xFF) shl 8)
        out.append(ALPHABET[(chunk ushr 18) and 0x3F])
        out.append(ALPHABET[(chunk ushr 12) and 0x3F])
        out.append(ALPHABET[(chunk ushr 6) and 0x3F])
      }
    }
    return out.toString()
  }

  /**
   * The inverse of [encode]. Padding is tolerated, and so is the standard `+/` alphabet, so a blob
   * written by an older build or by a platform encoder still decodes.
   *
   * @return null if [text] is not valid base64url. Callers treat that as "no stored value" rather
   *   than crashing, because the alternative is an app that cannot start after a corrupt write.
   */
  fun decode(text: String): ByteArray? {
    val trimmed = text.trimEnd('=')
    if (trimmed.isEmpty()) return ByteArray(0)
    val out = ArrayList<Byte>(trimmed.length * 3 / 4 + 3)
    var buffer = 0
    var bitsInBuffer = 0
    for (character in trimmed) {
      val value =
        when (character) {
          '+' -> 62
          '/' -> 63
          else -> ALPHABET.indexOf(character)
        }
      if (value < 0) return null
      buffer = (buffer shl 6) or value
      bitsInBuffer += 6
      if (bitsInBuffer >= 8) {
        bitsInBuffer -= 8
        out.add(((buffer ushr bitsInBuffer) and 0xFF).toByte())
      }
    }
    return out.toByteArray()
  }
}
