package id.tracive.sdk.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The authorised user's remaining check allowance. Returned by `GET /credits`.
 *
 * **Do not call this before each check.** Handle `TraciveException.InsufficientCredits` instead:
 * the pre-flight call doubles the requests per transaction and still races with anything else
 * spending the same balance, so it can report credits that are gone by the time the check runs.
 */
@Serializable
data class CreditsInfo(
  /** Checks remaining in the current subscription period. */
  @SerialName("subscriptionCredits") val subscriptionCredits: Int = 0,

  /**
   * The subscription tier this allowance comes from.
   *
   * A `String`, not an enum: plans change without an SDK release, and an unrecognised plan name
   * must not stop the whole response from decoding. This is unrelated to [RiskTier].
   */
  @SerialName("tier") val tier: String? = null,

  /** When the allowance next refills. Null when the plan does not refill. */
  @SerialName("nextResetAt") val nextResetAt: Instant? = null,
)
