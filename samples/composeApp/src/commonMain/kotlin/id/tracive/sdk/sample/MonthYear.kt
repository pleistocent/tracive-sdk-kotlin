package id.tracive.sdk.sample

import kotlinx.datetime.Instant

// Report dates carry month precision only, so the day is never shown. The device time zone is safe
// to format in: the server sends mid-month noon UTC, which is the same month everywhere.
internal expect fun monthYear(instant: Instant): String
