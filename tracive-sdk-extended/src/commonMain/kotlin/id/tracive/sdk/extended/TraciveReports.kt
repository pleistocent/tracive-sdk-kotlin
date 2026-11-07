package id.tracive.sdk.extended

import id.tracive.sdk.client.TraciveClient
import id.tracive.sdk.model.ReportTarget
import id.tracive.sdk.model.TraciveEnvironment
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlin.coroutines.cancellation.CancellationException
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Lets a user report a scammer without leaving your app.
 *
 * ```
 * val reports = TraciveReports(client)
 * val requirements = reports.requirements()
 * val evidence = reports.prepare(bytes, "image/png")
 * val result = reports.submit(ReportDraft(query, title, chronology, null, date, listOf(evidence), requirements.terms))
 * ```
 *
 * The user needs a Tracive account and a grant with [TraciveReportScopes.CREATE]. Without one,
 * calls answer [ReportErrorCode.NEEDS_CONSENT] or [SubmitResult.NeedsConsent]: run your authorize
 * flow with the scope added, and call again.
 *
 * Evidence goes straight from the device to Tracive when [submit] runs. It never passes through
 * your servers.
 */
class TraciveReports
internal constructor(
  baseUrl: String,
  private val appUrlScheme: String,
  tokens: AccessTokenProvider,
  private val http: HttpClient,
  private val device: ReportDevice,
  private val compressor: EvidenceCompressor,
  private val uploader: EvidenceUploader,
  private val retryDelayMillis: Long,
) {

  /** For Swift, Flutter and React Native, where the base SDK's types are not visible. */
  constructor(
    baseUrl: String,
    appUrlScheme: String,
    accessToken: AccessTokenProvider,
  ) : this(baseUrl, appUrlScheme, accessToken, reportHttpClient())

  /**
   * For a partner that keeps tokens on its own backend.
   *
   * @param target the Tracive build [openInTracive] opens. Null follows [environment].
   */
  constructor(
    environment: TraciveEnvironment,
    accessToken: AccessTokenProvider,
    target: ReportTarget? = null,
  ) : this(
    environment.baseUrl,
    (target ?: ReportTarget.forEnvironment(environment)).urlScheme,
    accessToken,
  )

  private constructor(
    baseUrl: String,
    appUrlScheme: String,
    tokens: AccessTokenProvider,
    http: HttpClient,
  ) : this(
    baseUrl.trimEnd('/'),
    appUrlScheme,
    tokens,
    http,
    platformDevice(),
    EvidenceCompressor(),
    EvidenceUploader(http),
    POST_RETRY_DELAY_MILLIS,
  )

  private val api = ReportApi(baseUrl.trimEnd('/'), tokens, http)

  private val submitGate = Mutex()
  private val attempts = mutableMapOf<String, SubmitAttempt>()
  private val finished = mutableMapOf<String, SubmitResult.Submitted>()

  /** What a report must satisfy right now, and the terms the user accepts by submitting. */
  @Throws(Exception::class)
  suspend fun requirements(): ReportRequirements =
    ReportJson.decodeFromString(api.get("/report-requirements"))

  /**
   * Compresses one piece of evidence: to AVIF, under 200 KB, 1024 px on the shortest side, with
   * metadata dropped. A PDF under 1 MiB passes through.
   *
   * @param mimeType the file's type. Anything else than an image or a PDF is refused.
   */
  @Throws(Exception::class)
  suspend fun prepare(bytes: ByteArray, mimeType: String): PreparedEvidence =
    compressor.prepare(bytes, mimeType)

  /**
   * Uploads the evidence and files the report.
   *
   * Call again with the same [draft] after a failure: finished uploads are skipped and the report
   * is created once. A second call for a report already filed answers the same [SubmitResult].
   */
  @Throws(Exception::class)
  suspend fun submit(draft: ReportDraft, onProgress: ReportProgressListener? = null): SubmitResult =
    submitGate.withLock {
      finished[draft.idempotencyKey]?.let {
        return@withLock it
      }
      try {
        val attempt = attempts.getOrPut(draft.idempotencyKey) { SubmitAttempt() }
        upload(draft, attempt, onProgress)
        val created = create(draft, attempt)
        SubmitResult.Submitted(created.reportId, ReportStatus.fromWire(created.status)).also {
          finished[draft.idempotencyKey] = it
          attempts.remove(draft.idempotencyKey)
        }
      } catch (failure: TraciveReportException) {
        if (failure.code == ReportErrorCode.NEEDS_CONSENT) SubmitResult.NeedsConsent
        else SubmitResult.Failed(failure)
      }
    }

  /** One report's status. Needs [TraciveReportScopes.READ]. Reports answer for 30 days. */
  @Throws(Exception::class)
  suspend fun status(reportId: String): ReportStatus {
    val path = "/reports/${reportId.encodeURLPathPart()}"
    return ReportStatus.fromWire(ReportJson.decodeFromString<CreatedReport>(api.get(path)).status)
  }

  /** Opens the report in the Tracive app, where a revision is made. False without the app. */
  fun openInTracive(reportId: String): Boolean = openExternalUrl(detailsLink(reportId))

  internal fun detailsLink(reportId: String): String =
    "$appUrlScheme://report/details?id=${reportId.encodeURLParameter()}"

  /** Releases the HTTP client. */
  fun close() {
    http.close()
  }

  private suspend fun upload(
    draft: ReportDraft,
    attempt: SubmitAttempt,
    onProgress: ReportProgressListener?,
  ) {
    val total = draft.evidence.sumOf { it.bytes.size.toLong() }.coerceAtLeast(1)
    while (true) {
      val session = attempt.session ?: openSession(draft).also { attempt.session = it }
      try {
        draft.evidence.forEachIndexed { index, file ->
          if (index !in attempt.uploaded) {
            uploader.upload(session.attachments[index].url, file.bytes)
            attempt.uploaded += index
          }
          val sent = attempt.uploaded.sumOf { draft.evidence[it].bytes.size.toLong() }
          onProgress?.onProgress(sent.toFloat() / total)
        }
        return
      } catch (gone: UploadSessionGone) {
        // Once only: a session that dies right after being opened is not worth a loop.
        if (attempt.reopened) {
          throw TraciveReportException(
            ReportErrorCode.NETWORK,
            "The upload session ended.",
            cause = gone,
          )
        }
        attempt.reopened = true
        attempt.session = null
        attempt.uploaded.clear()
      }
    }
  }

  private suspend fun openSession(draft: ReportDraft): UploadSession {
    val request = UploadRequest(draft.evidence.map { UploadFile(it.mimeType, it.bytes.size) })
    return ReportJson.decodeFromString(
      api.post("/report-uploads", ReportJson.encodeToString(request))
    )
  }

  private suspend fun create(draft: ReportDraft, attempt: SubmitAttempt): CreatedReport {
    val session = checkNotNull(attempt.session)
    // Kept so a retry sends the same bytes: the server refuses a key reused with a different body.
    val body =
      attempt.body
        ?: ReportJson.encodeToString(
            CreateReportBody(
              identifiers = draft.identifiers,
              title = draft.title,
              chronology = draft.chronology,
              comment = draft.comment,
              incidentDate = draft.incidentDate,
              uploadId = session.uploadId,
              attachmentIds = session.attachments.map { it.attachmentId },
              acceptedTerms = draft.acceptedTerms.associate { it.id to it.version },
              language = draft.language ?: device.language,
              device = device.wire,
            )
          )
          .also { attempt.body = it }

    var tries = 0
    while (true) {
      try {
        return ReportJson.decodeFromString(
          api.post("/reports", body, idempotencyKey = draft.idempotencyKey)
        )
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: TraciveReportException) {
        val transient =
          failure.code == ReportErrorCode.NETWORK ||
            (failure.code == ReportErrorCode.SERVER && (failure.httpStatus ?: 0) >= 500)
        tries++
        if (!transient || tries >= MAX_POST_TRIES) throw failure
        delay(retryDelayMillis * tries)
      }
    }
  }

  internal companion object {
    private const val MAX_POST_TRIES = 3
    private const val POST_RETRY_DELAY_MILLIS = 1_000L

    fun forTest(
      baseUrl: String,
      tokens: AccessTokenProvider,
      http: HttpClient,
      device: ReportDevice,
      compressor: EvidenceCompressor,
    ): TraciveReports =
      TraciveReports(
        baseUrl,
        "tracivetest",
        tokens,
        http,
        device,
        compressor,
        EvidenceUploader(http, backoffMillis = 0),
        retryDelayMillis = 0,
      )
  }
}

/**
 * Uses the grant [client] already holds, and its single-flight refresh.
 *
 * A function because `@HiddenFromObjC` cannot sit on a constructor. Exported, [TraciveClient] puts
 * base's suspend methods in the Swift header, none of them with `@Throws`.
 *
 * @throws IllegalArgumentException for a client without OAuth.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
fun TraciveReports(client: TraciveClient, target: ReportTarget? = null): TraciveReports =
  TraciveReports(client.environment, grantTokens(client), target)

private fun grantTokens(client: TraciveClient): AccessTokenProvider {
  val oauth = requireNotNull(client.oauth) { "TraciveReports needs a client in OAuth mode." }
  return AccessTokenProvider { stale ->
    if (stale != null) oauth.refreshedToken(stale) else oauth.accessToken()
  }
}

private class SubmitAttempt {
  var session: UploadSession? = null
  val uploaded = mutableSetOf<Int>()
  var reopened = false
  var body: String? = null
}

@Serializable private class UploadFile(val mimeType: String, val sizeBytes: Int)

@Serializable private class UploadRequest(val attachments: List<UploadFile>)

@Serializable private class UploadTarget(val attachmentId: String, val url: String)

@Serializable private class UploadSession(val uploadId: String, val attachments: List<UploadTarget>)

@Serializable
private class CreateReportBody(
  val identifiers: List<String>,
  val title: String,
  val chronology: String,
  val comment: String? = null,
  val incidentDate: String,
  val uploadId: String,
  val attachmentIds: List<String>,
  val acceptedTerms: Map<String, String>,
  val language: String,
  val device: WireDevice,
)

@Serializable private class CreatedReport(val reportId: String, val status: String)
