package id.tracive.sdk.report

import id.tracive.sdk.client.RecordingAuthorizeLauncher
import id.tracive.sdk.client.reportClient
import id.tracive.sdk.client.runSdkTest
import id.tracive.sdk.model.ReportFormResult
import id.tracive.sdk.model.ReportTarget
import id.tracive.sdk.model.TraciveEnvironment
import id.tracive.sdk.platform.ReportFormLauncher
import id.tracive.sdk.platform.ReportHandoff
import id.tracive.sdk.query.TraciveQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iOS launcher's two decisions — which handoff the schemes allow, and how the "Open in" sheet
 * resolves — driven through `openReportForm` by a launcher that replays what UIKit would report.
 */
class IosHandoffRulesTest {

  private val query = TraciveQuery.bankAccount("IDN", "BCA", "1234567890")

  @Test
  fun theTargetPickedInTheSheetMeansDelivered() = runSdkTest {
    val launcher =
      ScriptedIosLauncher(
        queryable = setOf("tracive-report://", "tracive://"),
        sheet = { willBeginSending("com.pleistocent.tracive") },
      )

    assertEquals(ReportFormResult.Opened(attachmentsDelivered = true), open(launcher))
    assertEquals(1, launcher.presented)
  }

  @Test
  fun anotherAppPickedInTheSheetIsCancelled() = runSdkTest {
    val launcher =
      ScriptedIosLauncher(
        queryable = setOf("tracive-report://", "tracive://"),
        sheet = {
          willBeginSending("com.example.impostor")
          dismissed()
        },
      )

    assertEquals(ReportFormResult.Cancelled, open(launcher))
  }

  @Test
  fun theSandboxBuildIsNotTheProductionTarget() = runSdkTest {
    val launcher =
      ScriptedIosLauncher(
        queryable = setOf("tracive-report://", "tracive://"),
        sheet = { willBeginSending("com.pleistocent.tracive.sandbox") },
      )

    assertEquals(ReportFormResult.Cancelled, open(launcher))
  }

  @Test
  fun dismissingTheSheetOrSavingToFilesIsCancelled() = runSdkTest {
    val launcher =
      ScriptedIosLauncher(
        queryable = setOf("tracive-report://", "tracive://"),
        sheet = { dismissed() },
      )

    assertEquals(ReportFormResult.Cancelled, open(launcher))
  }

  @Test
  fun aDismissalAfterTheTargetWasPickedDoesNotOverturnIt() {
    val outcomes = mutableListOf<Boolean>()
    val outcome = OpenInMenuOutcome("com.pleistocent.tracive") { outcomes += it }

    outcome.willBeginSending("com.pleistocent.tracive")
    outcome.dismissed()
    outcome.willBeginSending("com.example.other")

    assertEquals(listOf(true), outcomes)
    assertTrue(outcome.sendingStarted)
  }

  @Test
  fun aSheetGoneOnTwoChecksInARowIsReportedOnce() {
    var gone = false
    var fired = 0
    val watch = VanishedSheetWatch(isGone = { gone }, onVanished = { fired += 1 })

    watch.check()
    gone = true
    watch.check()
    assertEquals(0, fired, "one sighting leaves room for a delegate answer on its way")
    watch.check()
    watch.check()

    assertEquals(1, fired)
  }

  @Test
  fun aSheetBackOnScreenStartsTheCountAgain() {
    val sightings = ArrayDeque(listOf(true, false, true, false, true))
    var fired = 0
    val watch =
      VanishedSheetWatch(isGone = { sightings.removeFirst() }, onVanished = { fired += 1 })

    repeat(5) { watch.check() }

    assertEquals(0, fired)
  }

  @Test
  fun anAppWithoutTheCapabilitySchemeGetsTheLink() = runSdkTest {
    val launcher =
      ScriptedIosLauncher(queryable = setOf("tracive://"), sheet = { error("no sheet") })

    assertEquals(ReportFormResult.Opened(attachmentsDelivered = false), open(launcher))
    assertEquals(0, launcher.presented)
    assertEquals(listOf("tracive://report/create?media=BANK_IDN_BCA_1234567890"), launcher.links)
  }

  @Test
  fun neitherSchemeMeansTheInstallPage() = runSdkTest {
    val installs = RecordingAuthorizeLauncher()
    val launcher = ScriptedIosLauncher(queryable = emptySet(), sheet = { error("no sheet") })

    val result = reportClient(installLauncher = installs).openReportForm(query, launcher = launcher)

    assertEquals(ReportFormResult.InstallPageOpened, result)
    assertEquals(1, installs.installUrls.size)
  }

  @Test
  fun theSchemesAskedAreTheTargetsOwn() {
    val production = setOf("tracive-report://", "tracive://")
    assertEquals(ReportHandoff.PACKAGE, schemeHandoff(ReportTarget.Production) { it in production })
    assertEquals(
      ReportHandoff.APP_NOT_INSTALLED,
      schemeHandoff(ReportTarget.Sandbox) { it in production },
    )
    assertEquals(
      ReportHandoff.PACKAGE,
      schemeHandoff(ReportTarget.Sandbox) { it == "tracivesandbox-report://" },
    )
  }

  private suspend fun open(launcher: ScriptedIosLauncher): ReportFormResult =
    reportClient(environment = TraciveEnvironment.Production)
      .openReportForm(query, launcher = launcher)

  /** What IosReportFormLauncher does, with UIKit's answers scripted. */
  private class ScriptedIosLauncher(
    private val queryable: Set<String>,
    private val sheet: OpenInMenuOutcome.() -> Unit,
  ) : ReportFormLauncher {

    var presented = 0
    val links = mutableListOf<String>()

    override suspend fun prepare(target: ReportTarget): ReportHandoff =
      schemeHandoff(target) { it in queryable }

    override suspend fun deliverPackage(target: ReportTarget, reportPackage: ByteArray): Boolean {
      presented += 1
      var delivered: Boolean? = null
      OpenInMenuOutcome(target.applicationId) { delivered = it }.sheet()
      return delivered ?: error("the sheet never resolved")
    }

    override suspend fun openLink(target: ReportTarget, url: String): Boolean {
      links += url
      return true
    }
  }
}
