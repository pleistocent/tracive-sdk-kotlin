package id.tracive.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How much of a report set to return.
 *
 * Sent on the wire as the request body's `detail` field, hence the lower-case serial names.
 */
@Serializable
enum class Detail {
  /**
   * Counts, tier, tags and dates. The default, and everything most callers need to make a decision.
   */
  @SerialName("minimal") MINIMAL,

  /**
   * Adds `topChronologies` and `comments` — narrative text written by reporters.
   *
   * Requires a scope the server may not have granted; without it the call fails with
   * `TraciveException.DetailNotPermitted`. Full-detail content is never cached, because it varies
   * with `detail` and `locale` while the cache is keyed on the query alone.
   */
  @SerialName("full") FULL,
}
