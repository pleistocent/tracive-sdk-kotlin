package id.tracive.sdk.extended.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/**
 * One report's status in plain words, in its own Activity. Needs a grant with `report.read`.
 *
 * ```
 * val status = registerForActivityResult(TraciveReportStatus.Contract()) { outcome -> ... }
 * status.launch(ReportStatusRequest(reportId))
 * ```
 *
 * Call [TraciveReportScreens.configure] first.
 */
object TraciveReportStatus {

  class Contract : ActivityResultContract<ReportStatusRequest, ReportStatusOutcome>() {

    override fun createIntent(context: Context, input: ReportStatusRequest): Intent =
      Intent(context, TraciveReportStatusActivity::class.java).putExtras(input.toExtras())

    override fun parseResult(resultCode: Int, intent: Intent?): ReportStatusOutcome =
      if (resultCode == Activity.RESULT_OK) ReportStatusOutcome.NeedsConsent
      else ReportStatusOutcome.Closed
  }
}

/** Which report to show, and how it looks. */
class ReportStatusRequest(
  val reportId: String,
  val theme: TraciveReportTheme = TraciveReportTheme(),
)

/** How the status screen ended. */
sealed class ReportStatusOutcome {

  /** The user closed it, or Android restored it into a process with nothing configured. */
  data object Closed : ReportStatusOutcome()

  /** No grant, or a grant without `report.read`. Authorize with the scope and open it again. */
  data object NeedsConsent : ReportStatusOutcome()
}
