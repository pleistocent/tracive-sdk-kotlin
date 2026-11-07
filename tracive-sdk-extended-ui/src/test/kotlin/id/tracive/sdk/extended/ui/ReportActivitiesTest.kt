package id.tracive.sdk.extended.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import id.tracive.sdk.extended.ReportErrorCode
import id.tracive.sdk.extended.ReportStatus
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

private const val STORY = "Dia menawarkan barang murah lewat DM, saya transfer, lalu saya diblokir."
private val SENT = ReportFormOutcome.Submitted("report-1", ReportStatus.SUBMITTED)

/** The Activity's real wiring: its view model store and its saved state, in a real Bundle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReportActivitiesTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val backend = FakeBackend()
  private val intent =
    TraciveReportForm.Contract()
      .createIntent(
        context,
        ReportFormRequest(
          ReportPrefill(
            listOf("BANK_IDN_BCA_1"),
            attachments = listOf(ReportPrefillAttachment(pdfBytes(), MIME_PDF)),
          )
        ),
      )

  @BeforeTest
  fun configure() {
    TraciveReportScreens.backend = backend
  }

  @AfterTest
  fun forget() {
    TraciveReportScreens.backend = null
  }

  private fun pdfBytes() = "%PDF-1.4".encodeToByteArray()

  /** Bounded: a spinner on screen animates forever, so waiting for an empty queue never ends. */
  private fun runUntil(done: () -> Boolean) {
    repeat(500) {
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
      if (done()) return
      Thread.sleep(5)
    }
    fail("Never happened")
  }

  private fun launched(saved: Bundle? = null): ActivityController<TraciveReportFormActivity> =
    Robolectric.buildActivity(TraciveReportFormActivity::class.java, intent).apply {
      if (saved == null) setup() else setup(saved)
    }

  private fun ActivityController<TraciveReportFormActivity>.model() =
    ViewModelProvider(get())[ReportFormViewModel::class]

  @Test
  fun rotationKeepsTheViewModelWithItsTextAndAttachments() {
    val controller = launched()
    val model = controller.model()
    model.onChronologyChanged(STORY)
    runUntil { model.evidence.singleOrNull()?.state is EvidenceState.Ready }
    val before = controller.get()

    controller.recreate()

    assertNotSame(before, controller.get())
    assertSame(model, controller.model())
    assertEquals(STORY, controller.model().chronology)
    assertEquals(1, controller.model().evidence.size)
  }

  @Test
  fun textComesBackAfterTheProcessDiesAndTheAttachmentsDoNotBreakTheForm() {
    val controller = launched()
    controller.model().apply {
      onTitleChanged("Judul")
      onChronologyChanged(STORY)
      onDateChanged("2026-11-05")
    }
    val saved = Bundle()
    controller.saveInstanceState(saved)

    val restored = launched(saved).model()

    assertEquals("Judul", restored.title)
    assertEquals(STORY, restored.chronology)
    assertEquals("2026-11-05", restored.incidentDate)
    assertEquals(listOf("BANK_IDN_BCA_1"), restored.identifiers)
    assertTrue(restored.evidence.isEmpty())
  }

  private fun savedAfterSending(): Bundle {
    val controller = launched()
    val model = controller.model()
    runUntil { model.evidence.singleOrNull()?.state is EvidenceState.Ready }
    model.onTitleChanged("Judul")
    model.onChronologyChanged(STORY)
    model.onDateChanged("2020-01-01")
    model.send()
    runUntil { model.sent != null && shadowOf(controller.get()).resultCode == Activity.RESULT_OK }
    return Bundle().also(controller::saveInstanceState)
  }

  private fun Activity.outcome() =
    TraciveReportForm.Contract().parseResult(shadowOf(this).resultCode, shadowOf(this).resultIntent)

  @Test
  fun aReportSentBeforeTheProcessDiedShowsAsSentNotAsAnEmptyForm() {
    val saved = savedAfterSending()
    val requirementCalls = backend.requirementCalls

    val controller = launched(saved)

    assertEquals(SENT, controller.get().outcome())
    assertEquals("report-1", controller.model().sent?.reportId)
    assertEquals(requirementCalls, backend.requirementCalls)
  }

  @Test
  fun aReportSentBeforeTheProcessDiedIsNotCancelledEvenWithNothingConfigured() {
    val saved = savedAfterSending()
    TraciveReportScreens.backend = null

    val activity =
      Robolectric.buildActivity(TraciveReportFormActivity::class.java, intent).create(saved).get()

    assertTrue(activity.isFinishing)
    assertEquals(SENT, activity.outcome())
  }

  @Test
  fun needsConsentFinishesTheFormWithThatOutcome() {
    backend.requirementAnswers += { throw failure(ReportErrorCode.NEEDS_CONSENT) }

    val activity = launched().get()

    runUntil { activity.isFinishing }
    assertEquals(ReportFormOutcome.NeedsConsent, activity.outcome())
  }
}
