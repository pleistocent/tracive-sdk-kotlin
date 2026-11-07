package id.tracive.sdk.extended.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

private const val DEFAULT_RADIUS = 12
private const val MAX_RADIUS = 28
private const val MIN_PRIMARY_CONTRAST = 3f

private val TraciveLight =
  lightColorScheme(
    primary = Color(0xFF405F90),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF264777),
    secondary = Color(0xFF555F71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E3F9),
    onSecondaryContainer = Color(0xFF3E4758),
    tertiary = Color(0xFF6F5675),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF9D8FE),
    onTertiaryContainer = Color(0xFF563E5C),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6CF),
    surfaceDim = Color(0xFFD9D9E0),
    surfaceBright = Color(0xFFF9F9FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),
  )

private val TraciveDark =
  darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF08305F),
    primaryContainer = Color(0xFF264777),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBDC7DC),
    onSecondary = Color(0xFF273141),
    secondaryContainer = Color(0xFF3E4758),
    onSecondaryContainer = Color(0xFFD9E3F9),
    tertiary = Color(0xFFDCBCE1),
    onTertiary = Color(0xFF3E2845),
    tertiaryContainer = Color(0xFF563E5C),
    onTertiaryContainer = Color(0xFFF9D8FE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF4A1C1E),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF14151A),
    onBackground = Color(0xFFE3E3E9),
    surface = Color(0xFF1B1C22),
    onSurface = Color(0xFFE3E3E9),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC6C8D1),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF42454D),
    surfaceDim = Color(0xFF131419),
    surfaceBright = Color(0xFF3A3D48),
    surfaceContainerLowest = Color(0xFF0E0F14),
    surfaceContainerLow = Color(0xFF1D1F26),
    surfaceContainer = Color(0xFF24262E),
    surfaceContainerHigh = Color(0xFF2E303A),
    surfaceContainerHighest = Color(0xFF383B45),
  )

/**
 * Tracive's colours with the partner's primary. Only the primary roles move, so nothing else on
 * screen, the terms line above all, can be recoloured by a theme.
 */
internal fun reportColorScheme(dark: Boolean, primaryColor: Int?): ColorScheme {
  val base = if (dark) TraciveDark else TraciveLight
  if (primaryColor == null) return base
  val primary = visibleOn(base.surface, Color(primaryColor).copy(alpha = 1f), base.onSurface)
  return base.copy(
    primary = primary,
    onPrimary = readableOn(primary),
    primaryContainer = lerp(base.surface, primary, 0.2f),
    onPrimaryContainer = base.onSurface,
  )
}

private fun visibleOn(surface: Color, wanted: Color, extreme: Color): Color {
  var fraction = 0f
  var color = wanted
  while (contrast(color, surface) < MIN_PRIMARY_CONTRAST && fraction < 1f) {
    fraction += 0.1f
    color = lerp(wanted, extreme, fraction)
  }
  return color
}

private fun readableOn(color: Color): Color =
  if (contrast(color, Color.White) >= contrast(color, Color.Black)) Color.White else Color.Black

internal fun contrast(first: Color, second: Color): Float {
  val a = first.compositeOver(second).luminance() + 0.05f
  val b = second.luminance() + 0.05f
  return maxOf(a, b) / minOf(a, b)
}

@Composable
internal fun ReportTheme(theme: TraciveReportTheme, content: @Composable () -> Unit) {
  val radius = (theme.cornerRadius ?: DEFAULT_RADIUS).coerceIn(0, MAX_RADIUS).dp
  val shape = RoundedCornerShape(radius)
  MaterialTheme(
    colorScheme = reportColorScheme(isSystemInDarkTheme(), theme.primaryColor),
    shapes =
      Shapes(extraSmall = shape, small = shape, medium = shape, large = shape, extraLarge = shape),
    content = content,
  )
}
