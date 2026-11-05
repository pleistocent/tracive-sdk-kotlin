package id.tracive.sdk.client

import id.tracive.sdk.model.ClientType
import id.tracive.sdk.model.ReportAttachment
import id.tracive.sdk.model.ReportFormError
import id.tracive.sdk.model.ReportFormResult
import id.tracive.sdk.model.ReportTarget
import id.tracive.sdk.model.TraciveEnvironment
import id.tracive.sdk.model.TraciveMode
import id.tracive.sdk.oauth.TraciveOAuth
import id.tracive.sdk.platform.ReportHandoff
import id.tracive.sdk.query.TraciveQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The fallback chain of `openReportForm`, the target it picks, and what it never touches. */
class OpenReportFormTest {

  private val query = TraciveQuery.phone("+6281234567890")

  private val png =
    ReportAttachment.image(
      byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0),
      "image/png",
    )

  @Test
  fun anAppThatAcceptsThePackageReceivesIt() = runSdkTest {
    val launcher = RecordingReportFormLauncher(ReportHandoff.PACKAGE)
    val installs = RecordingAuthorizeLauncher()

    val result =
      reportClient(installLauncher = installs)
        .openReportForm(query, attachments = listOf(png), launcher = launcher)

    assertEquals(ReportFormResult.Opened(attachmentsDelivered = true), result)
    assertEquals(1, launcher.packages.size)
    assertTrue(launcher.links.isEmpty())
    assertTrue(installs.installUrls.isEmpty())
  }

  @Test
  fun aPackageTheUserSentNowhereIsCancelled() = runSdkTest {
    val launcher = RecordingReportFormLauncher(ReportHandoff.PACKAGE, delivers = false)

    assertEquals(
      ReportFormResult.Cancelled,
      reportClient().openReportForm(query, attachments = listOf(png), launcher = launcher),
    )
  }

  @Test
  fun anOlderAppGetsTheIdentifierOnlyLink() = runSdkTest {
    val launcher = RecordingReportFormLauncher(ReportHandoff.LINK)

    val result =
      reportClient().openReportForm(query, attachments = listOf(png), launcher = launcher)

    assertEquals(ReportFormResult.Opened(attachmentsDelivered = false), result)
    assertEquals(listOf("tracive://report/create?media=PHONE_%2B6281234567890"), launcher.links)
    assertTrue(launcher.packages.isEmpty(), "an older app is never sent the package")
  }

  @Test
  fun aLinkTheUserDeclinedIsCancelled() = runSdkTest {
    val launcher = RecordingReportFormLauncher(ReportHandoff.LINK, opensLink = false)

    assertEquals(
      ReportFormResult.Cancelled,
      reportClient().openReportForm(query, launcher = launcher),
    )
  }

  @Test
  fun noAppOpensTheInstallPageCarryingNothingFromTheReport() = runSdkTest {
    val launcher = RecordingReportFormLauncher(ReportHandoff.APP_NOT_INSTALLED)
    val installs = RecordingAuthorizeLauncher()

    val result =
      reportClient(installLauncher = installs)
        .openReportForm(query, attachments = listOf(png), launcher = launcher)

    assertEquals(ReportFormResult.InstallPageOpened, result)
    assertEquals(listOf(TraciveOAuth.defaultInstallUrl("cli_test")), installs.installUrls)
    assertFalse(installs.installUrls.single().contains("81234567890"), "no identifier on it")
    assertTrue(launcher.packages.isEmpty() && launcher.links.isEmpty())
  }

  @Test
  fun theInstallPageOverrideIsTheOneOpened() = runSdkTest {
    val installs = RecordingAuthorizeLauncher()

    reportClient(installLauncher = installs, installUrl = "https://partner.test/get-tracive")
      .openReportForm(
        query,
        launcher = RecordingReportFormLauncher(ReportHandoff.APP_NOT_INSTALLED),
      )

    assertEquals(listOf("https://partner.test/get-tracive"), installs.installUrls)
  }

  @Test
  fun aPlatformFailureIsTypedAndDoesNotQuoteTheIdentifier() = runSdkTest {
    val launcher =
      RecordingReportFormLauncher(
        failure = IllegalStateException("No Activity for tracive://report/create?media=PHONE_%2B62")
      )

    val result = reportClient().openReportForm(query, launcher = launcher)

    val error =
      assertIs<ReportFormError.PlatformFailure>(assertIs<ReportFormResult.Failed>(result).error)
    assertEquals("PLATFORM_FAILURE", error.code)
    assertTrue(error.message.contains("IllegalStateException"), error.message)
    assertFalse(error.message.contains("PHONE"), error.message)
  }

  @Test
  fun anInstallPageThatWouldNotOpenIsAFailureNotInstallPageOpened() = runSdkTest {
    val installs = RecordingAuthorizeLauncher(onInstall = { throw IllegalStateException() })

    val result =
      reportClient(installLauncher = installs)
        .openReportForm(
          query,
          launcher = RecordingReportFormLauncher(ReportHandoff.APP_NOT_INSTALLED),
        )

    assertIs<ReportFormError.PlatformFailure>(assertIs<ReportFormResult.Failed>(result).error)
  }

  @Test
  fun cancellationIsNotReportedAsAFailure() = runSdkTest {
    val launcher = RecordingReportFormLauncher(failure = CancellationException("screen closed"))

    assertFailsWith<CancellationException> {
      reportClient().openReportForm(query, launcher = launcher)
    }
  }

  @Test
  fun theTargetFollowsTheEnvironmentAndCustomMeansProduction() = runSdkTest {
    val expectations =
      listOf(
        TraciveEnvironment.Production to ReportTarget.Production,
        TraciveEnvironment.Sandbox to ReportTarget.Sandbox,
        TraciveEnvironment.Custom("https://proxy.partner.test") to ReportTarget.Production,
      )
    for ((environment, target) in expectations) {
      val launcher = RecordingReportFormLauncher(ReportHandoff.LINK)
      reportClient(environment = environment).openReportForm(query, launcher = launcher)
      assertEquals(listOf(target), launcher.prepared, "$environment")
      assertTrue(launcher.links.single().startsWith("${target.urlScheme}://"), "$environment")
    }
  }

  @Test
  fun anExplicitTargetWinsOverTheEnvironment() = runSdkTest {
    val dev =
      ReportTarget(
        "com.pleistocent.tracive.dev",
        "tracivedev",
        "tracivedev-report",
        "tracivedevreport",
      )
    val launcher = RecordingReportFormLauncher(ReportHandoff.LINK)

    reportClient(environment = TraciveEnvironment.Sandbox)
      .openReportForm(query, target = dev, launcher = launcher)

    assertEquals(listOf(dev), launcher.prepared)
    assertEquals(listOf("tracivedev://report/create?media=PHONE_%2B6281234567890"), launcher.links)
  }

  @Test
  fun aConfidentialClientWithNoGrantCanOpenTheForm() = runSdkTest {
    val transport = FakeHttpTransport { kotlin.test.fail("openReportForm sent a request") }
    val launcher = RecordingReportFormLauncher(ReportHandoff.PACKAGE)

    val result =
      reportClient(
          mode = testOAuthMode(clientType = ClientType.CONFIDENTIAL),
          transport = transport,
        )
        .openReportForm(query, attachments = listOf(png), launcher = launcher)

    assertEquals(ReportFormResult.Opened(attachmentsDelivered = true), result)
    assertTrue(transport.requests.isEmpty())
  }

  @Test
  fun proxyModeCanOpenTheFormToo() = runSdkTest {
    val installs = RecordingAuthorizeLauncher()

    val result =
      reportClient(
          mode = TraciveMode.Proxy("https://proxy.partner.test", emptyMap()),
          installLauncher = installs,
        )
        .openReportForm(
          query,
          launcher = RecordingReportFormLauncher(ReportHandoff.APP_NOT_INSTALLED),
        )

    assertEquals(ReportFormResult.InstallPageOpened, result)
    assertEquals(listOf(TraciveOAuth.defaultInstallUrl("")), installs.installUrls)
  }

  @Test
  fun todayIsTheLatestIncidentDateAccepted() = runSdkTest {
    val clock = FixedClock()
    val today = clock.todayIn(TimeZone.currentSystemDefault())

    val accepted = RecordingReportFormLauncher(ReportHandoff.PACKAGE)
    assertEquals(
      ReportFormResult.Opened(attachmentsDelivered = true),
      reportClient(clock = clock).openReportForm(query, incidentDate = today, launcher = accepted),
    )
    val sent = Json.parseToJsonElement(accepted.packages.single().decodeToString()).jsonObject
    assertEquals(today.toString(), sent.getValue("incidentDate").jsonPrimitive.content)

    val refused = RecordingReportFormLauncher(ReportHandoff.PACKAGE)
    assertEquals(
      ReportFormResult.Failed(ReportFormError.IncidentDateInFuture),
      reportClient(clock = clock)
        .openReportForm(query, incidentDate = today.plus(1, DateTimeUnit.DAY), launcher = refused),
    )
    assertTrue(refused.prepared.isEmpty())
  }

  @Test
  fun theFirstFailingAttachmentIsTheOneNamed() = runSdkTest {
    val gif = ReportAttachment.image("GIF89a".encodeToByteArray(), "image/gif")
    val mislabelled =
      ReportAttachment.image(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "image/png")

    val result =
      reportClient()
        .openReportForm(
          query,
          attachments = listOf(png, mislabelled, gif),
          launcher = RecordingReportFormLauncher(),
        )

    assertEquals(ReportFormResult.Failed(ReportFormError.ContentMismatch(1)), result)
  }

  @Test
  fun newTypesNeverPrintTheirBytes() {
    assertEquals("ReportAttachment(image/png, 9 bytes)", png.toString())
    assertEquals("ReportTarget(com.pleistocent.tracive)", ReportTarget.Production.toString())
  }
}
