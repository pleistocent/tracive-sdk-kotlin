package id.tracive.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a check cost.
 *
 * Present only on a server result; a cache hit costs nothing and carries no [Billing]. This is not
 * cached either — it describes one call, not the identifier.
 */
@Serializable
data class Billing(
  /**
   * Who paid: `USER` in phase 1, always.
   *
   * A `String` rather than an enum so that a credit source added in a later release decodes to a
   * readable value instead of failing the whole response.
   */
  @SerialName("creditSource") val creditSource: String,

  /**
   * Credits charged for this call.
   *
   * A miss costs the same as a hit — the server did the lookup either way. That is the whole reason
   * the SDK validates and folds a query before sending it.
   */
  @SerialName("unitsCharged") val unitsCharged: Int = 0,

  /** The partner's remaining balance, when the server reports one. */
  @SerialName("partnerBalanceRemaining") val partnerBalanceRemaining: Long? = null,
)
