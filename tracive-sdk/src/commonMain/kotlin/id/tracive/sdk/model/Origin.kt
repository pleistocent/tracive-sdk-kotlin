package id.tracive.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a [CheckResult] actually came from. */
@Serializable
enum class Origin {
  /**
   * Served from the local encrypted cache: no network I/O happened and no credit was spent.
   *
   * `CheckResult.fetchedAt` still reports when the data was originally fetched from the server, so
   * a UI can say how stale the answer is.
   */
  @SerialName("CACHE") CACHE,

  /**
   * Fetched from the Tracive API on this call. A credit was spent unless `billing` says otherwise.
   */
  @SerialName("SERVER") SERVER,
}
