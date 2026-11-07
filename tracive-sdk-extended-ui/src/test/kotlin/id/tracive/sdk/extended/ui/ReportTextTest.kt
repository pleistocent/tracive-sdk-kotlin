package id.tracive.sdk.extended.ui

import id.tracive.sdk.extended.ReportErrorCode
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class ReportTextTest {

  @Test
  fun aTermWithoutItsOwnNameIsPrettifiedFromItsId() {
    assertEquals("Data Sharing Addendum", prettyTermId("DATA_SHARING_ADDENDUM"))
  }

  @Test
  fun everyErrorHasAMessageOfItsOwn() {
    val messages = ReportErrorCode.entries.map(::errorText)

    assertEquals(messages.size, messages.toSet().size)
  }

  @Test
  fun onlyAFailureThatCouldPassOnItsOwnOffersRetry() {
    assertTrue(isRetryable(ReportErrorCode.NETWORK))
    assertTrue(isRetryable(ReportErrorCode.SERVER))
    assertFalse(isRetryable(ReportErrorCode.TERMS_OUTDATED))
    assertFalse(isRetryable(ReportErrorCode.DAILY_LIMIT_REACHED))
  }

  @Test
  fun pickerMillisAreUtcMidnightsWhateverTheDeviceZone() {
    val date = LocalDate(2026, 11, 6)

    assertEquals(1_793_923_200_000, pickerMillis(date))
    assertEquals(date, pickedDate(pickerMillis(date)))
  }

  @Test
  fun aDateReadsInTheUsersLanguage() {
    val date = LocalDate(2026, 11, 6)

    assertEquals("November 6, 2026", displayedDate(date, Locale.US))
    assertTrue(displayedDate(date, Locale("in", "ID")).contains("2026"))
  }
}
