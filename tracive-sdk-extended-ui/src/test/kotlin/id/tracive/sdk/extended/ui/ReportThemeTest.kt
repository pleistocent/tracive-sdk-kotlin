package id.tracive.sdk.extended.ui

import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportThemeTest {

  private val partners =
    listOf(
      0xFF00695C.toInt(),
      0xFFFFFFFF.toInt(),
      0xFF000000.toInt(),
      0xFFF9F9FF.toInt(),
      0xFF1B1C22.toInt(),
      0x00000000,
      0xFFFFEB3B.toInt(),
    )

  @Test
  fun noPartnerColourReachesTheColoursOfTheTermsLine() {
    listOf(false, true).forEach { dark ->
      val tracive = reportColorScheme(dark, null)
      partners.forEach { primary ->
        val themed = reportColorScheme(dark, primary)
        assertEquals(tracive.onSurface, themed.onSurface)
        assertEquals(tracive.surface, themed.surface)
        assertEquals(tracive.background, themed.background)
      }
    }
  }

  @Test
  fun theTermsLineIsReadableInBothModes() {
    listOf(false, true).forEach { dark ->
      val scheme = reportColorScheme(dark, 0xFFFFFFFF.toInt())
      assertTrue(contrast(scheme.onSurface, scheme.surface) > 7f)
    }
  }

  @Test
  fun aPrimaryCloseToTheBackgroundIsMovedUntilItShows() {
    listOf(false, true).forEach { dark ->
      partners.forEach { primary ->
        val scheme = reportColorScheme(dark, primary)
        assertTrue(contrast(scheme.primary, scheme.surface) >= 3f, "$dark $primary")
        assertTrue(contrast(scheme.onPrimary, scheme.primary) >= 3f, "$dark $primary")
      }
    }
  }

  @Test
  fun aPrimaryThatAlreadyShowsIsLeftAlone() {
    assertEquals(0xFF00695C.toInt(), reportColorScheme(false, 0xFF00695C.toInt()).primary.toArgb())
  }
}
