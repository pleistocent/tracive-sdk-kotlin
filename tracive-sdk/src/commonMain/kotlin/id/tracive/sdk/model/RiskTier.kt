package id.tracive.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How much corroborated evidence there is against a checked identifier.
 *
 * The tier is the server's judgement, not a client-side derivation from the report counts — the
 * server weighs credibility, recency and amount, and it is the only place that logic lives. It also
 * drives the default cache TTL in `CachePolicy`: a `HIGH` verdict is far more stable over time than
 * a `NONE` one, so the two are cached for very different periods.
 *
 * `NONE` means "no evidence found", not "verified safe". Any UI built on this SDK must present it
 * that way, and must show `CheckResult.disclaimer` alongside it.
 */
@Serializable
enum class RiskTier {
  /** Strong, corroborated evidence of fraud. */
  @SerialName("HIGH") HIGH,

  /** Substantial evidence, short of the `HIGH` bar. */
  @SerialName("ELEVATED") ELEVATED,

  /** Some evidence, or evidence that is ambiguous or contested. */
  @SerialName("CAUTION") CAUTION,

  /** No evidence found. Not a clean bill of health. */
  @SerialName("NONE") NONE,
}
