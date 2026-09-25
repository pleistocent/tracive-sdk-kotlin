package id.tracive.sdk.sample

import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.datetime.Instant

internal actual fun monthYear(instant: Instant): String {
  val locale = Locale.getDefault()
  val pattern = DateFormat.getBestDateTimePattern(locale, "MMMy")
  return SimpleDateFormat(pattern, locale).format(Date(instant.toEpochMilliseconds()))
}
