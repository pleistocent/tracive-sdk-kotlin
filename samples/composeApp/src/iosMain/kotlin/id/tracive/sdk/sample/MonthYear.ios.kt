package id.tracive.sdk.sample

import platform.Foundation.NSCalendar
import platform.Foundation.NSDateComponents
import platform.Foundation.NSDateFormatter

internal actual fun formatMonthYear(year: Int, month: Int): String {
  val components =
    NSDateComponents().apply {
      setYear(year.toLong())
      setMonth(month.toLong())
    }
  val date = NSCalendar.currentCalendar.dateFromComponents(components) ?: return "$year-$month"
  return NSDateFormatter().apply { setLocalizedDateFormatFromTemplate("MMMy") }.stringFromDate(date)
}
