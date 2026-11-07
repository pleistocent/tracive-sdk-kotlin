package id.tracive.sdk.extended.ui

import id.tracive.sdk.extended.PreparedEvidence
import id.tracive.sdk.extended.ReportDraft
import id.tracive.sdk.extended.ReportErrorCode
import id.tracive.sdk.extended.ReportLimits
import id.tracive.sdk.extended.ReportProgressListener
import id.tracive.sdk.extended.ReportRequirements
import id.tracive.sdk.extended.ReportStatus
import id.tracive.sdk.extended.RequiredTerm
import id.tracive.sdk.extended.SubmitResult
import id.tracive.sdk.extended.TraciveReportException
import kotlinx.coroutines.CompletableDeferred

internal val LIMITS =
  ReportLimits(
    titleMaxLength = 50,
    chronologyMinLength = 50,
    chronologyMaxLength = 2000,
    commentMaxLength = 150,
    maxIdentifiers = 10,
    attachmentsPerIdentifier = 2,
    extraAttachmentsWithPhone = 3,
    maxAttachmentBytes = 1_048_575,
    attachmentTypes = emptyList(),
  )

internal val TERMS =
  listOf(
    RequiredTerm("TERMS_OF_SERVICE", "v1", "https://tracive.id/terms"),
    RequiredTerm("PRIVACY_POLICY", "v1", "https://tracive.id/privacy"),
  )

internal val REQUIREMENTS =
  ReportRequirements(enabled = true, remainingToday = 3, limits = LIMITS, terms = TERMS)

internal fun failure(code: ReportErrorCode, retryAfterSeconds: Long? = null) =
  TraciveReportException(code, "scripted", retryAfterSeconds = retryAfterSeconds)

/** The constructor is internal to the core on purpose, so partners cannot skip compression. */
internal fun evidence(): PreparedEvidence =
  PreparedEvidence::class
    .java
    .getDeclaredConstructor(ByteArray::class.java, String::class.java)
    .newInstance(byteArrayOf(1, 2, 3), "image/avif")

internal class FakeBackend : ReportBackend {
  val requirementAnswers = ArrayDeque<suspend () -> ReportRequirements>()
  var requirementCalls = 0
  val submitted = mutableListOf<ReportDraft>()
  val submitAnswers = ArrayDeque<suspend () -> SubmitResult>()
  var prepareAnswer: suspend (String) -> PreparedEvidence = { evidence() }
  var statusAnswer: suspend () -> ReportStatus = { ReportStatus.SUBMITTED }
  val statusCalls = mutableListOf<String>()
  val opened = mutableListOf<String>()
  var openAnswer = true
  var progressDuringSubmit = emptyList<Float>()

  override suspend fun requirements(): ReportRequirements {
    requirementCalls++
    return requirementAnswers.removeFirstOrNull()?.invoke() ?: REQUIREMENTS
  }

  override suspend fun prepare(bytes: ByteArray, mimeType: String) = prepareAnswer(mimeType)

  override suspend fun submit(
    draft: ReportDraft,
    onProgress: ReportProgressListener,
  ): SubmitResult {
    submitted += draft
    progressDuringSubmit.forEach(onProgress::onProgress)
    return submitAnswers.removeFirstOrNull()?.invoke()
      ?: SubmitResult.Submitted("report-1", ReportStatus.SUBMITTED)
  }

  override suspend fun status(reportId: String): ReportStatus {
    statusCalls += reportId
    return statusAnswer()
  }

  override fun openInTracive(reportId: String): Boolean {
    opened += reportId
    return openAnswer
  }
}

/** Parks a submit until the test lets it go, or until it is cancelled. */
internal class ParkedSubmit(private val answer: SubmitResult) {
  private val gate = CompletableDeferred<Unit>()
  var cancelled = false
    private set

  val call: suspend () -> SubmitResult = {
    try {
      gate.await()
      answer
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
      cancelled = true
      throw cancellation
    }
  }

  fun release() = gate.complete(Unit)
}
