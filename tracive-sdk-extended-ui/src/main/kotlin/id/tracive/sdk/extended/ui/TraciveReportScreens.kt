package id.tracive.sdk.extended.ui

import id.tracive.sdk.extended.TraciveReports

/**
 * Connects Tracive's report screens to your [TraciveReports].
 *
 * Call [configure] once, anywhere before starting a screen. `Application.onCreate` is the best
 * place: `TraciveReports` is not Parcelable, so it lives only in this process. A screen that
 * Android restores into a new process without it closes as cancelled.
 *
 * On API 24 and 25 the host app needs core library desugaring, because dates in this SDK read
 * `java.time`.
 */
object TraciveReportScreens {

  @Volatile internal var backend: ReportBackend? = null

  fun configure(reports: TraciveReports) {
    backend = CoreBackend(reports)
  }
}
