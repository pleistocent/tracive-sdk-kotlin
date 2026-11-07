package id.tracive.sdk.extended.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import id.tracive.sdk.extended.ReportStatus
import id.tracive.sdk.query.TraciveQuery
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReportContractsTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val contract = TraciveReportForm.Contract()

  @AfterTest
  fun forgetBackend() {
    TraciveReportScreens.backend = null
  }

  private fun formIntent(attachments: List<ReportPrefillAttachment> = emptyList()) =
    contract.createIntent(
      context,
      ReportFormRequest(
        ReportPrefill(
          TraciveQuery.phone("+6281234567891"),
          incidentDate = LocalDate(2026, 11, 5),
          title = "Judul",
          attachments = attachments,
        ),
        TraciveReportTheme(primaryColor = 0xFF00695C.toInt(), cornerRadius = 8),
      ),
    )

  @Test
  fun attachmentsNeverEnterTheIntent() {
    val attachment = ReportPrefillAttachment(ByteArray(2 * 1024 * 1024), "image/png")

    val intent = formIntent(listOf(attachment))

    val parcel = Parcel.obtain()
    parcel.writeBundle(intent.extras)
    assertTrue(parcel.dataSize() < 4096, "${parcel.dataSize()} bytes")
    parcel.recycle()
    assertSame(attachment, PendingAttachments.take(intent.formExtras().attachmentsKey).single())
  }

  @Test
  fun theHoldingSlotGivesAttachmentsOutOnce() {
    val key =
      formIntent(listOf(ReportPrefillAttachment(ByteArray(1), "image/png")))
        .formExtras()
        .attachmentsKey

    assertEquals(1, PendingAttachments.take(key).size)
    assertTrue(PendingAttachments.take(key).isEmpty())
  }

  @Test
  fun theRequestSurvivesTheIntent() {
    val intent = formIntent()

    val extras = intent.formExtras()
    assertEquals(listOf("PHONE_+6281234567891"), extras.identifiers)
    assertEquals("2026-11-05", extras.incidentDate)
    assertEquals("Judul", extras.title)
    assertEquals(0xFF00695C.toInt(), intent.reportTheme().primaryColor)
    assertEquals(8, intent.reportTheme().cornerRadius)
  }

  @Test
  fun aSubmittedOutcomeSurvivesTheResultIntent() {
    val outcome = ReportFormOutcome.Submitted("report-1", ReportStatus.IN_REVIEW)

    val parsed = contract.parseResult(Activity.RESULT_OK, Intent().putExtras(outcome.toBundle()))

    assertEquals(outcome, parsed)
  }

  @Test
  fun aResultWithoutAReportIdIsNeedsConsentAndCancelIsCancelled() {
    assertEquals(ReportFormOutcome.NeedsConsent, contract.parseResult(Activity.RESULT_OK, Intent()))
    assertEquals(ReportFormOutcome.Cancelled, contract.parseResult(Activity.RESULT_CANCELED, null))
  }

  @Test
  fun formRestoredWithNothingConfiguredClosesAsCancelled() {
    val activity =
      Robolectric.buildActivity(TraciveReportFormActivity::class.java, formIntent()).create().get()

    assertTrue(activity.isFinishing)
    assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
  }

  @Test
  fun aFiledReportStaysSubmittedEvenWithNothingConfigured() {
    val outcome = ReportFormOutcome.Submitted("report-1", ReportStatus.SUBMITTED)
    val saved = Bundle().apply { putBundle("submitted", outcome.toBundle()) }

    val activity =
      Robolectric.buildActivity(TraciveReportFormActivity::class.java, formIntent())
        .create(saved)
        .get()

    assertTrue(activity.isFinishing)
    assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
    assertEquals(outcome, contract.parseResult(Activity.RESULT_OK, shadowOf(activity).resultIntent))
  }

  @Test
  fun statusRestoredWithNothingConfiguredCloses() {
    val intent =
      TraciveReportStatus.Contract().createIntent(context, ReportStatusRequest("report-1"))

    val activity =
      Robolectric.buildActivity(TraciveReportStatusActivity::class.java, intent).create().get()

    assertTrue(activity.isFinishing)
    assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    assertEquals(
      ReportStatusOutcome.Closed,
      TraciveReportStatus.Contract().parseResult(shadowOf(activity).resultCode, null),
    )
  }
}
