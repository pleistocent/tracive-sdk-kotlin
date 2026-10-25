package id.tracive.sdk.platform

import id.tracive.sdk.model.TraciveEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ids are spelled out rather than compared to the constants: they belong to the Tracive app's
 * flavors, and a wrong one reports "not installed" to every user of that environment, silently.
 */
class AppPresenceCheckerTest {

  @Test
  fun sandboxLooksForTheSandboxBuild() {
    assertEquals(
      "com.pleistocent.tracive.sandbox",
      AppPresenceChecker.androidPackageFor(TraciveEnvironment.Sandbox),
    )
    assertEquals("tracivesandbox", AppPresenceChecker.iosUrlSchemeFor(TraciveEnvironment.Sandbox))
  }

  @Test
  fun productionAndCustomLookForTheProductionApp() {
    val custom = TraciveEnvironment.Custom("https://proxy.partner.test")
    for (environment in listOf(TraciveEnvironment.Production, custom)) {
      assertEquals("com.pleistocent.tracive", AppPresenceChecker.androidPackageFor(environment))
      assertEquals("tracive", AppPresenceChecker.iosUrlSchemeFor(environment))
    }
  }
}
