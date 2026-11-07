package id.tracive.sdk.extended.ui

import id.tracive.sdk.extended.ReportErrorCode
import id.tracive.sdk.extended.ReportStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ReportStatusViewModelTest {

  private val backend = FakeBackend()

  @BeforeTest
  fun main() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @AfterTest
  fun resetMain() {
    Dispatchers.resetMain()
  }

  @Test
  fun needRevisionOpensThisReportInTracive() {
    backend.statusAnswer = { ReportStatus.NEED_REVISION }
    val model = ReportStatusViewModel(backend, "report-7")
    assertEquals(StatusLoad.Loaded(ReportStatus.NEED_REVISION), model.load)

    model.openInTracive()

    assertEquals(listOf("report-7"), backend.opened)
    assertFalse(model.traciveMissing)
  }

  @Test
  fun withoutTraciveTheScreenSaysSo() {
    backend.statusAnswer = { ReportStatus.NEED_REVISION }
    backend.openAnswer = false
    val model = ReportStatusViewModel(backend, "report-7")

    model.openInTracive()

    assertTrue(model.traciveMissing)
  }

  @Test
  fun anExpiredReportCannotBeRetriedIntoExistence() {
    backend.statusAnswer = { throw failure(ReportErrorCode.REPORT_NOT_FOUND) }

    val model = ReportStatusViewModel(backend, "report-7")

    assertEquals(StatusLoad.Failed(ReportErrorCode.REPORT_NOT_FOUND), model.load)
  }

  @Test
  fun aFailedCheckCanBeRetried() {
    backend.statusAnswer = { throw failure(ReportErrorCode.NETWORK) }
    val model = ReportStatusViewModel(backend, "report-7")
    assertEquals(StatusLoad.Failed(ReportErrorCode.NETWORK), model.load)

    backend.statusAnswer = { ReportStatus.REVIEWED }
    model.retry()

    assertEquals(StatusLoad.Loaded(ReportStatus.REVIEWED), model.load)
  }

  @Test
  fun aGrantWithoutReportReadEndsTheScreen() {
    backend.statusAnswer = { throw failure(ReportErrorCode.NEEDS_CONSENT) }

    assertTrue(ReportStatusViewModel(backend, "report-7").needsConsent)
  }
}
