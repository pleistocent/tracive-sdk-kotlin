package id.tracive.sdk.query

import id.tracive.sdk.TraciveQueryException

/*
 * `QRIS_<NMID>` and `QRIS_<PAN>`: the fixture's QRIS_PAYLOAD, QRIS_NMID and QRIS_PAN rules.
 *
 * A payload is refused unless it is intact (TLV framing and CRC), names an NMID and belongs to a
 * micro or small business (UMI or UKE). No other QR is ever indexed, so a check on one could only
 * be a billed miss.
 *
 * This is not the JavaScript whitespace set of QueryFolding.kt. NMIDs, PANs and payload edges lose
 * exactly tab, LF, CR, space and U+00A0, and the vectors pin that U+2003 and U+200B stay.
 */

private val INDEXED_CRITERIA = setOf("UMI", "UKE")

private val KNOWN_CRITERIA = setOf("UMI", "UKE", "UME", "UBE")

private fun Char.isQrisSpace(): Boolean =
  this == '\t' || this == '\n' || this == '\r' || this == ' ' || this == ' '

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

internal fun foldQrisNmidKey(nmid: String): String =
  requireValidDocumentId(
    "QRIS_" +
      (normalizeNmid(nmid) ?: throw TraciveQueryException(TraciveQueryException.NOT_AN_NMID))
  )

internal fun foldQrisPanKey(pan: String): String =
  requireValidDocumentId(
    "QRIS_" + (normalizePan(pan) ?: throw TraciveQueryException(TraciveQueryException.NOT_A_PAN))
  )

internal fun foldQrisPayloadKey(raw: String): String {
  val payload = raw.trimQrisSpaces()
  if (!payload.startsWith("000201")) throw TraciveQueryException(TraciveQueryException.NOT_A_QRIS)
  val tlv = readTlv(payload) ?: throw TraciveQueryException(TraciveQueryException.NOT_A_QRIS)
  val (lastTag, crc) = tlv.last()
  if (lastTag != "63" || crc.length != 4) {
    throw TraciveQueryException(TraciveQueryException.NOT_A_QRIS)
  }
  if (qrisCrc(payload.substring(0, payload.length - 4)) != crc.uppercase()) {
    throw TraciveQueryException(TraciveQueryException.QRIS_CRC_MISMATCH)
  }

  val top = tlv.firstByTag()
  val national = subTags(top["51"])
  val nmid =
    national["02"]?.let(::normalizeNmid)
      ?: throw TraciveQueryException(TraciveQueryException.QRIS_NO_NMID)
  // A criteria in tag 51 that is not one of the four falls through to the acquirer's, as on the
  // server; only the first template with a GUID is asked, never the next one.
  val acquirer =
    top.keys
      .filter { it.toInt() in 26..45 }
      .sorted()
      .map { subTags(top[it]) }
      .firstOrNull { "00" in it }
  val criteria = criteriaOf(national["03"]) ?: criteriaOf(acquirer?.get("03"))
  if (criteria !in INDEXED_CRITERIA) {
    throw TraciveQueryException(TraciveQueryException.QRIS_NOT_INDEXED)
  }
  return requireValidDocumentId("QRIS_$nmid")
}

private fun normalizeNmid(raw: String): String? {
  // Spaces go before upper-casing, which may change the length (`ß` -> `SS`), as on the server.
  val nmid = raw.filterNot { it.isQrisSpace() }.uppercase()
  val shaped =
    nmid.length == 15 && nmid.startsWith("ID") && (2 until 15).all { nmid[it].isAsciiDigit() }
  return if (shaped) nmid else null
}

private fun normalizePan(raw: String): String? {
  val digits = raw.filterNot { it.isQrisSpace() }
  if (!digits.all { it.isAsciiDigit() }) return null
  return when {
    digits.length == 18 -> digits
    digits.length == 19 && luhnValid(digits) -> digits.substring(0, 18)
    else -> null
  }
}

/** Doubles every second digit counting leftwards from the check digit, which is not doubled. */
private fun luhnValid(digits: String): Boolean {
  var sum = 0
  for (offset in digits.indices) {
    var digit = digits[digits.length - 1 - offset] - '0'
    if (offset % 2 == 1) {
      digit *= 2
      if (digit > 9) digit -= 9
    }
    sum += digit
  }
  return sum % 10 == 0
}

private fun String.trimQrisSpaces(): String {
  var start = 0
  var end = length
  while (start < end && this[start].isQrisSpace()) start += 1
  while (end > start && this[end - 1].isQrisSpace()) end -= 1
  return substring(start, end)
}

/**
 * A 2-digit tag, a 2-digit length, then the value; null when the framing breaks.
 *
 * The length counts UTF-16 code units, as JavaScript's `String.length` does. A Kotlin `String` is
 * indexed in the same units, so the indices below need no conversion.
 */
private fun readTlv(data: String): List<Pair<String, String>>? {
  val out = ArrayList<Pair<String, String>>()
  var index = 0
  while (index < data.length) {
    if (index + 4 > data.length) return null
    if (!(index until index + 4).all { data[it].isAsciiDigit() }) return null
    val length = (data[index + 2] - '0') * 10 + (data[index + 3] - '0')
    val end = index + 4 + length
    if (end > data.length) return null
    out += data.substring(index, index + 2) to data.substring(index + 4, end)
    index = end
  }
  return out
}

private fun List<Pair<String, String>>.firstByTag(): Map<String, String> {
  val map = LinkedHashMap<String, String>()
  for ((tag, value) in this) if (tag !in map) map[tag] = value
  return map
}

/** A template whose value is not TLV inside reads as having no sub-tags. */
private fun subTags(value: String?): Map<String, String> =
  (value?.let(::readTlv) ?: emptyList()).firstByTag()

private fun criteriaOf(value: String?): String? =
  value?.uppercase()?.takeIf { it in KNOWN_CRITERIA }

/**
 * CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF) over the UTF-8 bytes of [data], as 4 upper-case hex
 * digits.
 *
 * The bytes are encoded here rather than by `encodeToByteArray()`, which writes a lone surrogate as
 * `?` on the JVM and as U+FFFD on Kotlin/Native. Node writes U+FFFD, so this does too.
 */
internal fun qrisCrc(data: String): String {
  var crc = 0xFFFF
  fun feed(byte: Int) {
    crc = crc xor (byte shl 8)
    repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
    crc = crc and 0xFFFF
  }
  var index = 0
  while (index < data.length) {
    var code = data[index].code
    if (
      data[index].isHighSurrogate() && index + 1 < data.length && data[index + 1].isLowSurrogate()
    ) {
      code = 0x10000 + ((code - 0xD800) shl 10) + (data[index + 1].code - 0xDC00)
      index += 1
    } else if (data[index].isSurrogate()) {
      code = 0xFFFD
    }
    when {
      code < 0x80 -> feed(code)
      code < 0x800 -> {
        feed(0xC0 or (code shr 6))
        feed(0x80 or (code and 0x3F))
      }
      code < 0x10000 -> {
        feed(0xE0 or (code shr 12))
        feed(0x80 or ((code shr 6) and 0x3F))
        feed(0x80 or (code and 0x3F))
      }
      else -> {
        feed(0xF0 or (code shr 18))
        feed(0x80 or ((code shr 12) and 0x3F))
        feed(0x80 or ((code shr 6) and 0x3F))
        feed(0x80 or (code and 0x3F))
      }
    }
    index += 1
  }
  return crc.toString(16).uppercase().padStart(4, '0')
}
