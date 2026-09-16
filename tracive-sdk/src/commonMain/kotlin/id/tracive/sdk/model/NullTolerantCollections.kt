package id.tracive.sdk.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull

/**
 * `null` where the wire contract promises a collection, read as empty.
 *
 * The server sends `"fraudAmount": null` and `"tags": null` on every miss — the answer most checks
 * get, since most accounts are clean — and on any hit that carries no tags. Its own spec types both
 * as non-nullable, so neither shape appears in a fixture written from the spec. Failing the decode
 * here costs the caller a verdict they have already been charged for, which is the worst available
 * outcome.
 *
 * Deliberately two fields rather than `coerceInputValues = true` on the SDK's `Json`: that flag
 * would also silently fold an unrecognised `riskTier` into `NONE`, which `CheckResult` explains at
 * length is the one thing that must stay loud.
 */
private fun <T : Any> decodeNullAs(decoder: Decoder, delegate: KSerializer<T>, empty: T): T {
  val json = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
  val element = json.decodeJsonElement()
  return if (element is JsonNull) empty else json.json.decodeFromJsonElement(delegate, element)
}

/** `tags`: a null array reads as no tags. */
internal object NullTolerantStringList : KSerializer<List<String>> {
  private val delegate = ListSerializer(String.serializer())

  override val descriptor: SerialDescriptor = delegate.descriptor

  override fun deserialize(decoder: Decoder): List<String> =
    decodeNullAs(decoder, delegate, emptyList())

  override fun serialize(encoder: Encoder, value: List<String>) = delegate.serialize(encoder, value)
}

/** `fraudAmount`: a null object reads as nothing reported. */
internal object NullTolerantCurrencyMap : KSerializer<Map<String, Double>> {
  private val delegate = MapSerializer(String.serializer(), Double.serializer())

  override val descriptor: SerialDescriptor = delegate.descriptor

  override fun deserialize(decoder: Decoder): Map<String, Double> =
    decodeNullAs(decoder, delegate, emptyMap())

  override fun serialize(encoder: Encoder, value: Map<String, Double>) =
    delegate.serialize(encoder, value)
}
