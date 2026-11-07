package id.tracive.sdk.extended.ui

import id.tracive.sdk.extended.PreparedEvidence
import id.tracive.sdk.extended.ReportDraft
import id.tracive.sdk.extended.ReportProgressListener
import id.tracive.sdk.extended.ReportRequirements
import id.tracive.sdk.extended.ReportStatus
import id.tracive.sdk.extended.SubmitResult
import id.tracive.sdk.extended.TraciveReports

/** What the screens need from the core. */
internal interface ReportBackend {
  suspend fun requirements(): ReportRequirements

  suspend fun prepare(bytes: ByteArray, mimeType: String): PreparedEvidence

  suspend fun submit(draft: ReportDraft, onProgress: ReportProgressListener): SubmitResult

  suspend fun status(reportId: String): ReportStatus

  fun openInTracive(reportId: String): Boolean
}

internal class CoreBackend(private val reports: TraciveReports) : ReportBackend {
  override suspend fun requirements() = reports.requirements()

  override suspend fun prepare(bytes: ByteArray, mimeType: String) =
    reports.prepare(bytes, mimeType)

  override suspend fun submit(draft: ReportDraft, onProgress: ReportProgressListener) =
    reports.submit(draft, onProgress)

  override suspend fun status(reportId: String) = reports.status(reportId)

  override fun openInTracive(reportId: String) = reports.openInTracive(reportId)
}
