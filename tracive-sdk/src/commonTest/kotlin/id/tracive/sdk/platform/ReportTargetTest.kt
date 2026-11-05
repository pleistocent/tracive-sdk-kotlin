package id.tracive.sdk.platform

import id.tracive.sdk.model.ReportTarget
import id.tracive.sdk.model.TraciveEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Spelled out rather than compared to constants, like [AppPresenceCheckerTest]: these name the
 * Tracive app's builds, and a wrong one sends every user of that environment the link, or nothing.
 */
class ReportTargetTest {

  @Test
  fun productionIsTheProductionApp() {
    val target = ReportTarget.Production
    assertEquals("com.pleistocent.tracive", target.applicationId)
    assertEquals("tracive", target.urlScheme)
    assertEquals("tracive-report", target.capabilityScheme)
    assertEquals("tracivereport", target.fileExtension)
  }

  @Test
  fun sandboxIsTheSandboxApp() {
    val target = ReportTarget.Sandbox
    assertEquals("com.pleistocent.tracive.sandbox", target.applicationId)
    assertEquals("tracivesandbox", target.urlScheme)
    assertEquals("tracivesandbox-report", target.capabilityScheme)
    assertEquals("tracivesandboxreport", target.fileExtension)
  }

  @Test
  fun theAndroidMimeTypeIsTheSameForEveryBuild() {
    assertEquals("application/vnd.tracive.report+json", ReportTarget.ANDROID_MIME_TYPE)
  }

  @Test
  fun customMapsToProduction() {
    assertEquals(ReportTarget.Sandbox, ReportTarget.forEnvironment(TraciveEnvironment.Sandbox))
    assertEquals(
      ReportTarget.Production,
      ReportTarget.forEnvironment(TraciveEnvironment.Production),
    )
    assertEquals(
      ReportTarget.Production,
      ReportTarget.forEnvironment(TraciveEnvironment.Custom("https://proxy.partner.test")),
    )
  }

  @Test
  fun aSchemeWithItsSeparatorOrADottedExtensionIsRefused() {
    assertFailsWith<IllegalArgumentException> { ReportTarget("a.b", "tracivedev://", "x", "y") }
    assertFailsWith<IllegalArgumentException> { ReportTarget("a.b", "x", "x-report", ".ext") }
    assertFailsWith<IllegalArgumentException> { ReportTarget(" ", "x", "x-report", "ext") }
  }
}
