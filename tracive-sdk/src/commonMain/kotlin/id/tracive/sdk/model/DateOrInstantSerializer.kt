package id.tracive.sdk.model

import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * An instant that may arrive as a plain calendar date.
 *
 * `GET /v1/profile` derives `memberSince` from the account's creation timestamp and sends the date
 * alone (`2025-03-14`), which its spec types as `format: date`. Read date-only as midnight UTC —
 * the reading the Flutter and React Native SDKs already give it, so all four stay 1:1 without
 * changing the public type to a date and losing that parity.
 */
internal object DateOrInstantSerializer : KSerializer<Instant> {
  private val DATE_ONLY = Regex("""\d{4}-\d{2}-\d{2}""")

  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("id.tracive.sdk.DateOrInstant", PrimitiveKind.STRING)

  override fun deserialize(decoder: Decoder): Instant {
    val raw = decoder.decodeString()
    return Instant.parse(if (DATE_ONLY.matches(raw)) "${raw}T00:00:00Z" else raw)
  }

  override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
}
