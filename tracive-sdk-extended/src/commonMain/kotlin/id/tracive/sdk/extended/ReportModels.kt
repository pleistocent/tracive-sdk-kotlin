package id.tracive.sdk.extended

import id.tracive.sdk.query.TraciveQuery
import kotlin.random.Random
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** The OAuth scopes this SDK needs. Add them to the scopes your client requests. */
object TraciveReportScopes {
  /** Send reports. */
  const val CREATE: String = "report.create"

  /** Read the status of reports sent through your app. */
  const val READ: String = "report.read"
}

/**
 * Gives the SDK an access token. Base SDKs implement this for you: see [TraciveReports].
 *
 * Keep refresh in one place. A refresh token rotates and a reused one revokes the grant, so this
 * must never refresh on its own schedule, only when [stale] asks.
 */
fun interface AccessTokenProvider {
  /**
   * @param stale the token the server just refused, or null on a first call.
   * @return a usable token.
   */
  @Throws(Exception::class) suspend fun accessToken(stale: String?): String
}

/** What a report must satisfy right now. Read it before showing a form. */
@Serializable
data class ReportRequirements(
  /** False while reporting is switched off. */
  val enabled: Boolean,
  /** How many more reports this user can send today. */
  val remainingToday: Int,
  val limits: ReportLimits,
  /** The documents the user accepts by submitting. Show a link to each one. */
  val terms: List<RequiredTerm>,
)

/** Field limits, from the server. */
@Serializable
data class ReportLimits(
  val titleMaxLength: Int,
  val chronologyMinLength: Int,
  val chronologyMaxLength: Int,
  val commentMaxLength: Int,
  val maxIdentifiers: Int,
  val attachmentsPerIdentifier: Int,
  val extraAttachmentsWithPhone: Int,
  val maxAttachmentBytes: Int,
  val attachmentTypes: List<String>,
)

/** One document the user accepts by submitting. */
@Serializable data class RequiredTerm(val id: String, val version: String, val url: String)

/** A report's stage. It never says what a review concluded. */
enum class ReportStatus {
  SUBMITTED,
  IN_REVIEW,
  REVIEWED,
  NEED_REVISION;

  internal companion object {
    fun fromWire(value: String?): ReportStatus =
      entries.firstOrNull { it.name == value } ?: IN_REVIEW
  }
}

/** Why a call failed. */
enum class ReportErrorCode {
  /** No grant, or a grant without the scope. Run your authorize flow, then call again. */
  NEEDS_CONSENT,
  /** The user must verify their email in Tracive. */
  EMAIL_NOT_VERIFIED,
  /** Unknown, not sent through this app for this user, or older than 30 days. */
  REPORT_NOT_FOUND,
  /** The terms changed. Read the requirements again, show the new links, and build a new draft. */
  TERMS_OUTDATED,
  /** A field failed. The message names it. */
  INVALID_REPORT,
  /** A file is missing, or its bytes do not match its type. */
  ATTACHMENT_INVALID,
  /** Account or device cap. See [TraciveReportException.retryAfterSeconds]. */
  DAILY_LIMIT_REACHED,
  /** Reporting is switched off for now. */
  REPORTING_DISABLED,
  /** No answer. Call [TraciveReports.submit] again with the same draft: nothing is sent twice. */
  NETWORK,
  /** The server failed, or answered something this version does not know. */
  SERVER,
  /** Neither an image nor a PDF. */
  UNSUPPORTED_FILE,
  /** A PDF of 1 MiB or more. */
  FILE_TOO_LARGE,
  /** The image could not be decoded, or could not be brought under the size limit. */
  IMAGE_UNREADABLE,
}

/** A failed report call. The message never carries user data. */
class TraciveReportException(
  val code: ReportErrorCode,
  message: String,
  /** The HTTP status, when the error came from a response. */
  val httpStatus: Int? = null,
  /** Seconds to wait, for [ReportErrorCode.DAILY_LIMIT_REACHED]. */
  val retryAfterSeconds: Long? = null,
  cause: Throwable? = null,
) : Exception(message, cause)

/** The outcome of [TraciveReports.submit]. */
sealed class SubmitResult {
  /** Accepted. Keep [reportId] with your transaction; there is no way to list reports. */
  class Submitted(val reportId: String, val status: ReportStatus) : SubmitResult()

  /** No grant, or a grant without [TraciveReportScopes.CREATE]. Authorize and call again. */
  object NeedsConsent : SubmitResult()

  class Failed(val error: TraciveReportException) : SubmitResult()
}

/** Evidence ready to send, from [TraciveReports.prepare]. */
class PreparedEvidence internal constructor(val bytes: ByteArray, val mimeType: String)

/** Field problems a form can show before sending anything. */
enum class ReportFieldProblem {
  NO_IDENTIFIER,
  TOO_MANY_IDENTIFIERS,
  TITLE_MISSING,
  TITLE_TOO_LONG,
  CHRONOLOGY_TOO_SHORT,
  CHRONOLOGY_TOO_LONG,
  COMMENT_TOO_LONG,
  NO_EVIDENCE,
  TOO_MANY_EVIDENCE,
  INCIDENT_DATE_INVALID,
  INCIDENT_DATE_IN_FUTURE,
}

/**
 * One report. Keep the same draft across retries: its [idempotencyKey] is what stops a retry from
 * becoming a second report.
 *
 * The chronology must be the user's own words. You may prefill everything else.
 *
 * @param identifiers `TraciveQuery.searchQueryIndex` values.
 * @param incidentDate `YYYY-MM-DD`.
 * @param acceptedTerms from [ReportRequirements.terms], only what was shown to the user.
 * @param language `id` or `en`. Null uses the device language.
 */
class ReportDraft(
  val identifiers: List<String>,
  val title: String,
  val chronology: String,
  val comment: String? = null,
  val incidentDate: String,
  val evidence: List<PreparedEvidence>,
  val acceptedTerms: List<RequiredTerm>,
  val language: String? = null,
  val idempotencyKey: String = newIdempotencyKey(),
) {

  constructor(
    query: TraciveQuery,
    title: String,
    chronology: String,
    comment: String? = null,
    incidentDate: LocalDate,
    evidence: List<PreparedEvidence>,
    acceptedTerms: List<RequiredTerm>,
    language: String? = null,
  ) : this(
    identifiers = listOf(query.searchQueryIndex),
    title = title,
    chronology = chronology,
    comment = comment,
    incidentDate = incidentDate.toString(),
    evidence = evidence,
    acceptedTerms = acceptedTerms,
    language = language,
  )

  /** Everything wrong with this draft, in form order. Empty means it can be sent. */
  fun problems(limits: ReportLimits, today: LocalDate): List<ReportFieldProblem> = buildList {
    if (identifiers.isEmpty()) add(ReportFieldProblem.NO_IDENTIFIER)
    if (identifiers.size > limits.maxIdentifiers) add(ReportFieldProblem.TOO_MANY_IDENTIFIERS)

    val date = runCatching { LocalDate.parse(incidentDate) }.getOrNull()
    if (date == null) add(ReportFieldProblem.INCIDENT_DATE_INVALID)
    else if (date > today) add(ReportFieldProblem.INCIDENT_DATE_IN_FUTURE)

    if (title.isBlank()) add(ReportFieldProblem.TITLE_MISSING)
    if (title.length > limits.titleMaxLength) add(ReportFieldProblem.TITLE_TOO_LONG)
    if (chronology.length < limits.chronologyMinLength) {
      add(ReportFieldProblem.CHRONOLOGY_TOO_SHORT)
    }
    if (chronology.length > limits.chronologyMaxLength) {
      add(ReportFieldProblem.CHRONOLOGY_TOO_LONG)
    }
    if ((comment?.length ?: 0) > limits.commentMaxLength) add(ReportFieldProblem.COMMENT_TOO_LONG)

    if (evidence.isEmpty()) add(ReportFieldProblem.NO_EVIDENCE)
    if (identifiers.isNotEmpty() && evidence.size > maxEvidence(limits)) {
      add(ReportFieldProblem.TOO_MANY_EVIDENCE)
    }
  }

  /** How many files this draft may carry. */
  fun maxEvidence(limits: ReportLimits): Int {
    val phone = identifiers.any { it.startsWith(PHONE_PREFIX) }
    return identifiers.size * limits.attachmentsPerIdentifier +
      if (phone) limits.extraAttachmentsWithPhone else 0
  }

  private companion object {
    const val PHONE_PREFIX = "PHONE_"
  }
}

/** Upload progress, from 0 to 1. */
fun interface ReportProgressListener {
  fun onProgress(fraction: Float)
}

private fun newIdempotencyKey(): String =
  Random.Default.nextBytes(16).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
