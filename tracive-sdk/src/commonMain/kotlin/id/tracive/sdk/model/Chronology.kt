package id.tracive.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One reported incident narrative, returned only when `detail = FULL`.
 *
 * This is reporter-written text about a named party. Treat it as an allegation, display it with
 * `CheckResult.disclaimer`, and never cache it: it varies with the request's `detail` and `locale`,
 * while the cache is keyed on the query alone.
 */
@Serializable
data class Chronology(
  /** The narrative itself, in the requested locale where a translation exists. */
  @SerialName("chronology") val chronology: String,

  /** The server's severity weighting for this incident. Higher is worse. */
  @SerialName("riskLevel") val riskLevel: Int = 0,

  /**
   * The month the incident happened, as `"YYYY-MM"` (e.g. `"2026-06"`).
   *
   * A `String` rather than an instant on purpose: it is display text, and the server never sends a
   * day. Do not parse it as ISO-8601.
   */
  @SerialName("incidentDate") val incidentDate: String? = null,
)
