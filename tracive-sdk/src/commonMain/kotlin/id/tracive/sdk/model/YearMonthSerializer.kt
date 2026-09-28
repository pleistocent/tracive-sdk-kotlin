package id.tracive.sdk.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * `firstReportOn` / `lastReportOn`: a `"YYYY-MM"` month, or null.
 *
 * Older servers, and cache payloads written by earlier SDK builds, carry a full ISO date-time
 * (`2026-06-15T12:00:00Z`); only its month is kept, so the day never reaches a caller. Anything
 * without a valid month prefix reads as null rather than failing the whole result.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object YearMonthSerializer : KSerializer<String?> {
  private val YEAR_MONTH_PREFIX = Regex("""^\d{4}-(0[1-9]|1[0-2])""")

  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("id.tracive.sdk.YearMonth", PrimitiveKind.STRING).nullable

  private fun normalize(raw: String): String? = YEAR_MONTH_PREFIX.find(raw)?.value

  override fun deserialize(decoder: Decoder): String? {
    if (decoder is JsonDecoder) {
      val element = decoder.decodeJsonElement() as? JsonPrimitive ?: return null
      return if (element.isString) normalize(element.content) else null
    }
    return if (decoder.decodeNotNullMark()) normalize(decoder.decodeString())
    else decoder.decodeNull()
  }

  override fun serialize(encoder: Encoder, value: String?) {
    if (value == null) encoder.encodeNull() else encoder.encodeString(value)
  }
}
