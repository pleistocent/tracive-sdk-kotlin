package id.tracive.sdk.extended.ui

internal enum class MediaKind {
  PHONE,
  EMAIL,
  BANK,
  CRYPTO,
  SOCIAL_MEDIA,
  OTHER,
}

internal data class ReadableIdentifier(val kind: MediaKind, val value: String)

/**
 * Splits on `_` the way the keys are built: bank, chain and platform codes hold none, while account
 * numbers, addresses and handles may. A key that does not parse is shown as it is.
 */
internal fun readableIdentifier(key: String): ReadableIdentifier {
  val parts = key.split('_', limit = 2)
  val value = parts.getOrNull(1).orEmpty()
  return when (parts[0].takeIf { value.isNotEmpty() }) {
    "PHONE" -> ReadableIdentifier(MediaKind.PHONE, value)
    "EMAIL" -> ReadableIdentifier(MediaKind.EMAIL, value)
    "BANK" -> bank(key, value)
    "CRYPTO" -> crypto(key, value)
    "USERNAME" -> username(key, value)
    else -> ReadableIdentifier(MediaKind.OTHER, key)
  }
}

private fun bank(key: String, value: String): ReadableIdentifier {
  val parts = value.split('_', limit = 3)
  return when {
    parts[0] == "IBAN" && parts.size >= 2 ->
      ReadableIdentifier(MediaKind.BANK, value.removePrefix("IBAN_"))
    parts.size == 3 -> ReadableIdentifier(MediaKind.BANK, "${parts[1]} ${parts[2]}")
    else -> ReadableIdentifier(MediaKind.OTHER, key)
  }
}

private fun crypto(key: String, value: String): ReadableIdentifier {
  val parts = value.split('_', limit = 2)
  return if (parts.size == 2) ReadableIdentifier(MediaKind.CRYPTO, "${parts[0]} ${parts[1]}")
  else ReadableIdentifier(MediaKind.OTHER, key)
}

private fun username(key: String, value: String): ReadableIdentifier {
  val parts = value.split('_', limit = 2)
  if (parts.size != 2) return ReadableIdentifier(MediaKind.OTHER, key)
  val platform = parts[0].lowercase().replaceFirstChar { it.uppercase() }
  return ReadableIdentifier(MediaKind.SOCIAL_MEDIA, "$platform @${parts[1].removePrefix("@")}")
}
