package id.tracive.sdk.extended.ui

import android.content.Intent
import android.os.Bundle
import id.tracive.sdk.extended.ReportStatus
import java.util.UUID

private const val EXTRA_IDENTIFIERS = "id.tracive.sdk.extended.ui.identifiers"
private const val EXTRA_DATE = "id.tracive.sdk.extended.ui.incidentDate"
private const val EXTRA_TITLE = "id.tracive.sdk.extended.ui.title"
private const val EXTRA_ATTACHMENTS = "id.tracive.sdk.extended.ui.attachments"
private const val EXTRA_REPORT_ID = "id.tracive.sdk.extended.ui.reportId"
private const val EXTRA_STATUS = "id.tracive.sdk.extended.ui.status"
private const val EXTRA_PRIMARY = "id.tracive.sdk.extended.ui.primaryColor"
private const val EXTRA_RADIUS = "id.tracive.sdk.extended.ui.cornerRadius"

/**
 * Attachments can pass the Binder limit, so they never enter an Intent; the Intent carries a key.
 * One slot, so a launch that never reached the form leaves at most one set of bytes behind.
 */
internal object PendingAttachments {
  private var key: String? = null
  private var attachments: List<ReportPrefillAttachment> = emptyList()

  @Synchronized
  fun hold(files: List<ReportPrefillAttachment>): String? {
    key = files.takeIf { it.isNotEmpty() }?.let { UUID.randomUUID().toString() }
    attachments = files
    return key
  }

  @Synchronized
  fun take(requested: String?): List<ReportPrefillAttachment> {
    if (requested == null || requested != key) return emptyList()
    return attachments.also {
      key = null
      attachments = emptyList()
    }
  }
}

internal class FormExtras(
  val identifiers: List<String>,
  val incidentDate: String?,
  val title: String?,
  val attachmentsKey: String?,
)

internal fun ReportFormRequest.toExtras(): Bundle =
  theme.toExtras().apply {
    putStringArrayList(EXTRA_IDENTIFIERS, ArrayList(prefill.identifiers))
    putString(EXTRA_DATE, prefill.incidentDate?.toString())
    putString(EXTRA_TITLE, prefill.title)
    putString(EXTRA_ATTACHMENTS, PendingAttachments.hold(prefill.attachments))
  }

internal fun Intent.formExtras() =
  FormExtras(
    identifiers = getStringArrayListExtra(EXTRA_IDENTIFIERS).orEmpty(),
    incidentDate = getStringExtra(EXTRA_DATE),
    title = getStringExtra(EXTRA_TITLE),
    attachmentsKey = getStringExtra(EXTRA_ATTACHMENTS),
  )

internal fun ReportStatusRequest.toExtras(): Bundle =
  theme.toExtras().apply { putString(EXTRA_REPORT_ID, reportId) }

internal fun Intent.statusReportId(): String? = getStringExtra(EXTRA_REPORT_ID)

private fun TraciveReportTheme.toExtras(): Bundle =
  Bundle().apply {
    primaryColor?.let { putInt(EXTRA_PRIMARY, it) }
    cornerRadius?.let { putInt(EXTRA_RADIUS, it) }
  }

internal fun Intent.reportTheme() =
  TraciveReportTheme(
    primaryColor = if (hasExtra(EXTRA_PRIMARY)) getIntExtra(EXTRA_PRIMARY, 0) else null,
    cornerRadius = if (hasExtra(EXTRA_RADIUS)) getIntExtra(EXTRA_RADIUS, 0) else null,
  )

internal fun ReportFormOutcome.Submitted.toBundle(): Bundle =
  Bundle().apply {
    putString(EXTRA_REPORT_ID, reportId)
    putString(EXTRA_STATUS, status.name)
  }

internal fun Bundle.toSubmitted(): ReportFormOutcome.Submitted? {
  val reportId = getString(EXTRA_REPORT_ID) ?: return null
  val status = ReportStatus.entries.firstOrNull { it.name == getString(EXTRA_STATUS) }
  return ReportFormOutcome.Submitted(reportId, status ?: ReportStatus.IN_REVIEW)
}

internal fun Intent?.toFormOutcome(): ReportFormOutcome =
  this?.extras?.toSubmitted() ?: ReportFormOutcome.NeedsConsent
