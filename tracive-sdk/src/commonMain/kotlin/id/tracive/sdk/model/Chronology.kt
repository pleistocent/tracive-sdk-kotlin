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
   * When the incident happened, as the reporter gave it.
   *
   * A `String` rather than an instant on purpose: reporters supply partial and imprecise dates, and
   * the server passes them through rather than inventing a timestamp. Do not parse it as ISO-8601.
   */
  @SerialName("incidentDate") val incidentDate: String? = null,
)
