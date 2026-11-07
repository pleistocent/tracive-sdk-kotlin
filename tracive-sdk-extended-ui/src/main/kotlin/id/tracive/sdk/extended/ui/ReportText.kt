package id.tracive.sdk.extended.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import id.tracive.sdk.extended.ReportErrorCode
import id.tracive.sdk.extended.ReportFieldProblem
import id.tracive.sdk.extended.ReportLimits
import id.tracive.sdk.extended.ReportStatus

private const val SECONDS_PER_HOUR = 3600L

@StringRes
internal fun errorText(code: ReportErrorCode): Int =
  when (code) {
    ReportErrorCode.NEEDS_CONSENT -> R.string.tracive_report_error_needs_consent
    ReportErrorCode.EMAIL_NOT_VERIFIED -> R.string.tracive_report_error_email_not_verified
    ReportErrorCode.REPORT_NOT_FOUND -> R.string.tracive_report_error_report_not_found
    ReportErrorCode.TERMS_OUTDATED -> R.string.tracive_report_error_terms_outdated
    ReportErrorCode.INVALID_REPORT -> R.string.tracive_report_error_invalid_report
    ReportErrorCode.ATTACHMENT_INVALID -> R.string.tracive_report_error_attachment_invalid
    ReportErrorCode.DAILY_LIMIT_REACHED -> R.string.tracive_report_error_daily_limit
    ReportErrorCode.REPORTING_DISABLED -> R.string.tracive_report_error_reporting_disabled
    ReportErrorCode.NETWORK -> R.string.tracive_report_error_network
    ReportErrorCode.SERVER -> R.string.tracive_report_error_server
    ReportErrorCode.UNSUPPORTED_FILE -> R.string.tracive_report_error_unsupported_file
    ReportErrorCode.FILE_TOO_LARGE -> R.string.tracive_report_error_file_too_large
    ReportErrorCode.IMAGE_UNREADABLE -> R.string.tracive_report_error_image_unreadable
  }

internal fun isRetryable(code: ReportErrorCode): Boolean =
  code == ReportErrorCode.NETWORK || code == ReportErrorCode.SERVER

@Composable
internal fun failureText(failure: SendFailure): String {
  val hours = failure.retryAfterSeconds?.let { (it + SECONDS_PER_HOUR - 1) / SECONDS_PER_HOUR }
  return if (failure.code == ReportErrorCode.DAILY_LIMIT_REACHED && hours != null) {
    pluralStringResource(
      R.plurals.tracive_report_error_daily_limit_hours,
      hours.toInt(),
      hours.toInt(),
    )
  } else {
    stringResource(errorText(failure.code))
  }
}

@Composable
internal fun problemText(problem: ReportFieldProblem, limits: ReportLimits, maxEvidence: Int) =
  when (problem) {
    ReportFieldProblem.NO_IDENTIFIER ->
      stringResource(R.string.tracive_report_problem_no_identifier)
    ReportFieldProblem.TOO_MANY_IDENTIFIERS ->
      stringResource(R.string.tracive_report_problem_too_many_identifiers, limits.maxIdentifiers)
    ReportFieldProblem.TITLE_MISSING ->
      stringResource(R.string.tracive_report_problem_title_missing)
    ReportFieldProblem.TITLE_TOO_LONG ->
      stringResource(R.string.tracive_report_problem_too_long, limits.titleMaxLength)
    ReportFieldProblem.CHRONOLOGY_TOO_SHORT ->
      stringResource(R.string.tracive_report_problem_too_short, limits.chronologyMinLength)
    ReportFieldProblem.CHRONOLOGY_TOO_LONG ->
      stringResource(R.string.tracive_report_problem_too_long, limits.chronologyMaxLength)
    ReportFieldProblem.COMMENT_TOO_LONG ->
      stringResource(R.string.tracive_report_problem_too_long, limits.commentMaxLength)
    ReportFieldProblem.NO_EVIDENCE -> stringResource(R.string.tracive_report_problem_no_evidence)
    ReportFieldProblem.TOO_MANY_EVIDENCE ->
      stringResource(R.string.tracive_report_problem_too_many_evidence, maxEvidence)
    ReportFieldProblem.INCIDENT_DATE_INVALID ->
      stringResource(R.string.tracive_report_problem_date_missing)
    ReportFieldProblem.INCIDENT_DATE_IN_FUTURE ->
      stringResource(R.string.tracive_report_problem_date_future)
  }

@StringRes
internal fun statusTitle(status: ReportStatus): Int =
  when (status) {
    ReportStatus.SUBMITTED -> R.string.tracive_report_status_submitted
    ReportStatus.IN_REVIEW -> R.string.tracive_report_status_in_review
    ReportStatus.REVIEWED -> R.string.tracive_report_status_reviewed
    ReportStatus.NEED_REVISION -> R.string.tracive_report_status_need_revision
  }

@StringRes
internal fun statusLine(status: ReportStatus): Int =
  when (status) {
    ReportStatus.SUBMITTED -> R.string.tracive_report_status_submitted_line
    ReportStatus.IN_REVIEW -> R.string.tracive_report_status_in_review_line
    ReportStatus.REVIEWED -> R.string.tracive_report_status_reviewed_line
    ReportStatus.NEED_REVISION -> R.string.tracive_report_status_need_revision_line
  }

@Composable
internal fun termName(id: String): String =
  when (id) {
    "TERMS_OF_SERVICE" -> stringResource(R.string.tracive_report_term_terms_of_service)
    "PRIVACY_POLICY" -> stringResource(R.string.tracive_report_term_privacy_policy)
    else -> prettyTermId(id)
  }

internal fun prettyTermId(id: String): String =
  id
    .split('_')
    .filter { it.isNotEmpty() }
    .joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }
