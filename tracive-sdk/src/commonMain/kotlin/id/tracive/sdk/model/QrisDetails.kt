package id.tracive.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the reports' first verified QR says about the merchant, on a QRIS hit.
 *
 * The merchant name is whatever the QR's maker typed: show it, but never identify a person by it.
 * The PAN is never returned.
 */
@Serializable
data class QrisDetails(
  @SerialName("merchantName") val merchantName: String? = null,
  @SerialName("city") val city: String? = null,

  /**
   * `UMI`, `UKE`, `UME` or `UBE`. A `String` rather than an enum so that a value added later
   * decodes instead of failing the whole check.
   */
  @SerialName("criteria") val criteria: String? = null,

  /** The acquirer's GUID, e.g. `ID.DANA.WWW`. */
  @SerialName("acquirer") val acquirer: String? = null,
)
