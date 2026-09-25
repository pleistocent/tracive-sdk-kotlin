package id.tracive.sdk.sample

import kotlinx.datetime.Instant
import kotlinx.datetime.toNSDate
import platform.Foundation.NSDateFormatter

internal actual fun monthYear(instant: Instant): String =
  NSDateFormatter()
    .apply { setLocalizedDateFormatFromTemplate("MMMy") }
    .stringFromDate(instant.toNSDate())
