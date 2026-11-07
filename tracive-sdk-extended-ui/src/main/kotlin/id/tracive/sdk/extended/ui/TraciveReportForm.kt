package id.tracive.sdk.extended.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import id.tracive.sdk.extended.ReportStatus
import id.tracive.sdk.query.TraciveQuery
import kotlinx.datetime.LocalDate

/**
 * Tracive's report form, in its own Activity, so View, Flutter and React Native hosts need no
 * Compose.
 *
 * ```
 * val form = registerForActivityResult(TraciveReportForm.Contract()) { outcome -> ... }
 * form.launch(ReportFormRequest(ReportPrefill(query, incidentDate = date, title = title)))
 * ```
 *
 * Call [TraciveReportScreens.configure] first.
 */
object TraciveReportForm {

  class Contract : ActivityResultContract<ReportFormRequest, ReportFormOutcome>() {

    override fun createIntent(context: Context, input: ReportFormRequest): Intent =
      Intent(context, TraciveReportFormActivity::class.java).putExtras(input.toExtras())

    override fun parseResult(resultCode: Int, intent: Intent?): ReportFormOutcome =
      if (resultCode == Activity.RESULT_OK) intent.toFormOutcome() else ReportFormOutcome.Cancelled
  }
}

/** What the form opens with, and how it looks. */
class ReportFormRequest(
  val prefill: ReportPrefill,
  val theme: TraciveReportTheme = TraciveReportTheme(),
)

/**
 * What you already know about the scammer. The user writes the chronology, so it cannot be
 * prefilled.
 *
 * @param identifiers `TraciveQuery.searchQueryIndex` values, shown read-only.
 * @param incidentDate the user can change it, but not to a day in the future.
 * @param title the user can change it.
 * @param attachments the user can remove them. Each is compressed when the form opens.
 * @throws IllegalArgumentException with no identifier, because the form could never be sent.
 */
class ReportPrefill(
  val identifiers: List<String>,
  val incidentDate: LocalDate? = null,
  val title: String? = null,
  val attachments: List<ReportPrefillAttachment> = emptyList(),
) {

  init {
    require(identifiers.isNotEmpty()) { "A ReportPrefill needs at least one identifier." }
  }

  constructor(
    query: TraciveQuery,
    incidentDate: LocalDate? = null,
    title: String? = null,
    attachments: List<ReportPrefillAttachment> = emptyList(),
  ) : this(listOf(query.searchQueryIndex), incidentDate, title, attachments)
}

/** A receipt or screenshot to attach, as an image or a PDF. */
class ReportPrefillAttachment(val bytes: ByteArray, val mimeType: String)

/** How the form ended. */
sealed class ReportFormOutcome {

  /** The report was filed. Keep [reportId] with your transaction: reports cannot be listed. */
  data class Submitted(val reportId: String, val status: ReportStatus) : ReportFormOutcome()

  /** No grant, or a grant without `report.create`. Authorize with the scope and open it again. */
  data object NeedsConsent : ReportFormOutcome()

  /** The user closed the form, or Android restored it into a process with nothing configured. */
  data object Cancelled : ReportFormOutcome()
}
