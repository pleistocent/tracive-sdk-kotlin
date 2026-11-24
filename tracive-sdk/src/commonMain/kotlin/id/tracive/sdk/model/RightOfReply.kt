package id.tracive.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The QRIS owner's reply to the reports, reviewed by Tracive.
 *
 * Show [text] as written, untranslated, below the warning and never above it.
 */
@Serializable
data class RightOfReply(
  @SerialName("text") val text: String,

  /** BCP 47 code of [text], e.g. `id`. */
  @SerialName("language") val language: String,
)
