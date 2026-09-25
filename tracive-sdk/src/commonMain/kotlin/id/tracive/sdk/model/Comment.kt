package id.tracive.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One community comment on a checked identifier, returned only when `detail = FULL`.
 *
 * Unmoderated user text about a named party. Display it with `CheckResult.disclaimer`, and never
 * cache it — like [Chronology], it varies with `detail` and `locale`.
 */
@Serializable
data class Comment(
  /**
   * The month the comment was posted, as `"YYYY-MM"` (e.g. `"2026-06"`).
   *
   * A `String` rather than an instant: this field is display text. Do not parse it.
   */
  @SerialName("date") val date: String,

  /** The comment body. */
  @SerialName("text") val text: String,

  /**
   * Whether this comment came from a report the server judged credible.
   *
   * Worth surfacing: it is the difference between corroborated testimony and an anonymous remark.
   */
  @SerialName("fromCredibleReport") val fromCredibleReport: Boolean = false,
)
