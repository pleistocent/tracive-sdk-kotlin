package id.tracive.sdk.extended.ui

import java.text.DateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/** Material's date picker speaks in UTC midnights, so no device time zone may touch these. */
internal fun pickerMillis(date: LocalDate): Long =
  date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

internal fun pickedDate(utcMillis: Long): LocalDate =
  Instant.fromEpochMilliseconds(utcMillis).toLocalDateTime(TimeZone.UTC).date

internal fun parsedDate(value: String?): LocalDate? = value?.let {
  runCatching { LocalDate.parse(it) }.getOrNull()
}

internal fun displayedDate(date: LocalDate, locale: Locale): String {
  val calendar = Calendar.getInstance(locale)
  calendar.clear()
  calendar.set(date.year, date.monthNumber - 1, date.dayOfMonth)
  return DateFormat.getDateInstance(DateFormat.LONG, locale).format(calendar.time)
}
