package id.tracive.sdk.sample

import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

internal actual fun formatMonthYear(year: Int, month: Int): String {
  val locale = Locale.getDefault()
  val pattern = DateFormat.getBestDateTimePattern(locale, "MMMy")
  val calendar =
    Calendar.getInstance().apply {
      clear()
      set(year, month - 1, 1)
    }
  return SimpleDateFormat(pattern, locale).format(calendar.time)
}
