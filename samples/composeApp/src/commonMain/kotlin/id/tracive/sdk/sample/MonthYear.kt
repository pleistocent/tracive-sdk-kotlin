package id.tracive.sdk.sample

// Report dates are "YYYY-MM" and carry no day, so none is shown or made up.
internal fun monthYear(yearMonth: String): String {
  val year = yearMonth.substringBefore('-').toIntOrNull()
  val month = yearMonth.substringAfter('-', "").toIntOrNull()
  if (year == null || month == null || month !in 1..12) return yearMonth
  return formatMonthYear(year, month)
}

internal expect fun formatMonthYear(year: Int, month: Int): String
