package id.tracive.sdk.extended.ui

import androidx.annotation.ColorInt

/**
 * Your brand on Tracive's screens, in light and dark. The terms line above the Send button ignores
 * it: the user must always be able to read what they agree to.
 *
 * @param primaryColor an ARGB colour for buttons and highlights. Transparency is ignored, and a
 *   colour too close to the background is shifted until it can be seen. Null uses Tracive's.
 * @param cornerRadius in dp, from 0 to 28. Null uses Tracive's.
 */
class TraciveReportTheme(
  @param:ColorInt val primaryColor: Int? = null,
  val cornerRadius: Int? = null,
)
