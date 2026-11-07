package id.tracive.sdk.extended

import id.tracive.sdk.TraciveException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.errors.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private const val BASE = "https://api.test/v1"
private const val SESSION_1 = "https://storage.test/session/1"
private const val SESSION_2 = "https://storage.test/session/2"

private val SESSION_BODY =
  """{"uploadId":"upl_1","attachments":[{"attachmentId":"att_1","url":"$SESSION_1"}]}"""

private val REQUIREMENTS_BODY =
  """{"enabled":true,"remainingToday":3,"limits":{"titleMaxLength":50,"chronologyMinLength":50,
  "chronologyMaxLength":2000,"commentMaxLength":150,"maxIdentifiers":10,
  "attachmentsPerIdentifier":5,"extraAttachmentsWithPhone":5,"maxAttachmentBytes":1048575,
  "attachmentTypes":["image/avif"]},"terms":[{"id":"TERMS_OF_SERVICE","version":"v1.2",
  "url":"https://t"}],"somethingNew":1}"""

private class Recorded(
  val method: HttpMethod,
  val url: String,
  val headers: Map<String, String?>,
  val body: ByteArray,
)

private class Harness(
  private val tokens: AccessTokenProvider = AccessTokenProvider { stale ->
    if (stale == null) "first" else "second"
  },
  private val handler: suspend MockRequestHandleScope.(HttpRequestData, Int) -> HttpResponseData,
) {
  val calls = mutableListOf<Recorded>()

  val reports =
    TraciveReports.forTest(
      BASE,
      tokens,
      HttpClient(
        MockEngine { request ->
          calls +=
            Recorded(
              request.method,
              request.url.toString(),
              listOf(HttpHeaders.Authorization, "Idempotency-Key", HttpHeaders.ContentRange)
                .associateWith { request.headers[it] },
              request.body.toByteArray(),
            )
          handler(request, calls.count { it.url == request.url.toString() })
        }
      ) {
        expectSuccess = false
        followRedirects = false
      },
      ReportDevice(WireDevice("install", "iOS", "26.1", "Apple", "iPhone"), "id"),
      EvidenceCompressor(avif = { _, _, _ -> byteArrayOf(1, 2, 3, 4) }, fallbacks = emptyList()),
    )

  fun callsTo(url: String) = calls.filter { it.url == url }
}

private fun MockRequestHandleScope.json(body: String, status: Int = 200): HttpResponseData =
  respond(
    body,
    HttpStatusCode.fromValue(status),
    headersOf(HttpHeaders.ContentType, "application/json"),
  )

private fun MockRequestHandleScope.apiError(code: String, status: Int): HttpResponseData =
  json("""{"error":{"code":"$code","message":"m"}}""", status)

private suspend fun Harness.draft(): ReportDraft =
  ReportDraft(
    identifiers = listOf("BANK_IDN_BCA_1"),
    title = "t",
    chronology = "c".repeat(50),
    incidentDate = "2026-11-04",
    evidence = listOf(reports.prepare(byteArrayOf(9), "image/png")),
    acceptedTerms = listOf(RequiredTerm("TERMS_OF_SERVICE", "v1.2", "https://t")),
  )

class TraciveReportsTest {

  @Test
  fun requirementsParseAndIgnoreUnknownKeys() = runTest {
    val harness = Harness { _, _ -> json(REQUIREMENTS_BODY) }

    val requirements = harness.reports.requirements()

    assertEquals(3, requirements.remainingToday)
    assertEquals("v1.2", requirements.terms.single().version)
    assertEquals("Bearer first", harness.calls.single().headers[HttpHeaders.Authorization])
  }

  @Test
  fun aRefusedTokenRetriesOnceWithTheStaleOnePassedBack() = runTest {
    val stales = mutableListOf<String?>()
    val harness =
      Harness(
        tokens =
          AccessTokenProvider { stale ->
            stales += stale
            if (stale == null) "first" else "second"
          }
      ) { _, call ->
        if (call == 1) apiError("invalid_token", 401) else json(REQUIREMENTS_BODY)
      }

    harness.reports.requirements()

    assertEquals(listOf(null, "first"), stales)
    assertEquals("Bearer second", harness.calls.last().headers[HttpHeaders.Authorization])
  }

  @Test
  fun aSecondRefusalIsConsentNotALoop() = runTest {
    val harness = Harness { _, _ -> apiError("invalid_token", 401) }

    val failure = assertFailsWith<TraciveReportException> { harness.reports.requirements() }

    assertEquals(ReportErrorCode.NEEDS_CONSENT, failure.code)
    assertEquals(2, harness.calls.size)
  }

  @Test
  fun aRevokedGrantEndsInNeedsConsent() = runTest {
    val harness =
      Harness(tokens = AccessTokenProvider { throw TraciveException.GrantRevoked() }) { _, _ ->
        json("{}")
      }

    assertIs<SubmitResult.NeedsConsent>(harness.reports.submit(harness.draft()))
    assertTrue(harness.calls.isEmpty())
  }

  @Test
  fun aMissingScopeEndsInNeedsConsent() = runTest {
    val harness = Harness { _, _ -> apiError("missing_scope", 403) }

    assertIs<SubmitResult.NeedsConsent>(harness.reports.submit(harness.draft()))
  }

  @Test
  fun submitSendsTheFileToTheSessionWithoutAToken() = runTest {
    val harness = Harness { request, _ ->
      when (request.url.toString()) {
        "$BASE/report-uploads" -> json(SESSION_BODY)
        SESSION_1 -> json("{}")
        else -> json("""{"reportId":"r1","status":"SUBMITTED"}""", 202)
      }
    }
    val draft = harness.draft()

    val result = assertIs<SubmitResult.Submitted>(harness.reports.submit(draft))

    assertEquals("r1", result.reportId)
    assertEquals(ReportStatus.SUBMITTED, result.status)
    val put = harness.callsTo(SESSION_1).single()
    assertNull(put.headers[HttpHeaders.Authorization], "a bearer token must not reach storage")
    assertEquals(listOf<Byte>(1, 2, 3, 4), put.body.toList())

    val post = harness.callsTo("$BASE/reports").single()
    assertEquals(draft.idempotencyKey, post.headers["Idempotency-Key"])
    val body = post.body.decodeToString()
    assertTrue(""""acceptedTerms":{"TERMS_OF_SERVICE":"v1.2"}""" in body, body)
    assertTrue(""""attachmentIds":["att_1"]""" in body, body)
    assertTrue(""""installId":"install"""" in body, body)
    assertTrue(""""language":"id"""" in body, body)
  }

  @Test
  fun aRetryAfterALostAnswerSendsTheSameBytesAndUploadsNothingAgain() = runTest {
    var failing = true
    val harness = Harness { request, _ ->
      when (request.url.toString()) {
        "$BASE/report-uploads" -> json(SESSION_BODY)
        SESSION_1 -> json("{}")
        else ->
          if (failing) throw IOException("offline")
          else json("""{"reportId":"r1","status":"SUBMITTED"}""", 202)
      }
    }
    val draft = harness.draft()

    val failed = assertIs<SubmitResult.Failed>(harness.reports.submit(draft))
    assertEquals(ReportErrorCode.NETWORK, failed.error.code)

    failing = false
    assertIs<SubmitResult.Submitted>(harness.reports.submit(draft))

    val posts = harness.callsTo("$BASE/reports")
    assertEquals(1, harness.callsTo("$BASE/report-uploads").size)
    assertEquals(1, harness.callsTo(SESSION_1).size)
    assertEquals(1, posts.map { it.body.decodeToString() }.toSet().size)
    assertEquals(1, posts.map { it.headers["Idempotency-Key"] }.toSet().size)
  }

  @Test
  fun aSecondSubmitOfAFiledDraftMakesNoCall() = runTest {
    val harness = Harness { request, _ ->
      when (request.url.toString()) {
        "$BASE/report-uploads" -> json(SESSION_BODY)
        SESSION_1 -> json("{}")
        else -> json("""{"reportId":"r1","status":"SUBMITTED"}""", 202)
      }
    }
    val draft = harness.draft()

    val first = harness.reports.submit(draft)
    val second = harness.reports.submit(draft)

    assertEquals(first, second)
    assertEquals(1, harness.callsTo("$BASE/reports").size)
  }

  @Test
  fun aPostRefusedByTheServerIsNotRetried() = runTest {
    val harness = Harness { request, _ ->
      when (request.url.toString()) {
        "$BASE/report-uploads" -> json(SESSION_BODY)
        SESSION_1 -> json("{}")
        else -> apiError("terms_outdated", 409)
      }
    }

    val failed = assertIs<SubmitResult.Failed>(harness.reports.submit(harness.draft()))

    assertEquals(ReportErrorCode.TERMS_OUTDATED, failed.error.code)
    assertEquals(1, harness.callsTo("$BASE/reports").size)
  }

  @Test
  fun aDailyCapCarriesRetryAfter() = runTest {
    val harness = Harness { _, _ ->
      respond(
        """{"error":{"code":"daily_quota_exceeded","message":"m"}}""",
        HttpStatusCode.TooManyRequests,
        headersOf(HttpHeaders.RetryAfter, "120"),
      )
    }

    val failure = assertFailsWith<TraciveReportException> { harness.reports.requirements() }

    assertEquals(ReportErrorCode.DAILY_LIMIT_REACHED, failure.code)
    assertEquals(120, failure.retryAfterSeconds)
  }

  @Test
  fun anUploadCutOffResumesFromWhatTheSessionHolds() = runTest {
    val harness = Harness { request, call ->
      when (request.url.toString()) {
        "$BASE/report-uploads" -> json(SESSION_BODY)
        SESSION_1 ->
          when (call) {
            1 -> throw IOException("cut off")
            2 ->
              respond("", HttpStatusCode.fromValue(308), headersOf(HttpHeaders.Range, "bytes=0-1"))
            else -> json("{}")
          }
        else -> json("""{"reportId":"r1","status":"SUBMITTED"}""", 202)
      }
    }

    assertIs<SubmitResult.Submitted>(harness.reports.submit(harness.draft()))

    val puts = harness.callsTo(SESSION_1)
    assertEquals("bytes */4", puts[1].headers[HttpHeaders.ContentRange])
    assertEquals("bytes 2-3/4", puts[2].headers[HttpHeaders.ContentRange])
    assertEquals(listOf<Byte>(3, 4), puts[2].body.toList())
  }

  @Test
  fun aFinishedUploadFoundByTheStatusQueryIsNotSentAgain() = runTest {
    val harness = Harness { request, call ->
      when (request.url.toString()) {
        "$BASE/report-uploads" -> json(SESSION_BODY)
        SESSION_1 -> if (call == 1) throw IOException("answer lost") else json("{}")
        else -> json("""{"reportId":"r1","status":"SUBMITTED"}""", 202)
      }
    }

    assertIs<SubmitResult.Submitted>(harness.reports.submit(harness.draft()))

    assertEquals(2, harness.callsTo(SESSION_1).size)
  }

  @Test
  fun aSessionThatEndedIsOpenedAgainOnce() = runTest {
    val harness = Harness { request, call ->
      when (request.url.toString()) {
        "$BASE/report-uploads" ->
          json(
            if (call == 1) SESSION_BODY
            else
              """{"uploadId":"upl_2","attachments":[{"attachmentId":"att_1","url":"$SESSION_2"}]}"""
          )
        SESSION_1 -> json("", 410)
        SESSION_2 -> json("{}")
        else -> json("""{"reportId":"r1","status":"SUBMITTED"}""", 202)
      }
    }

    assertIs<SubmitResult.Submitted>(harness.reports.submit(harness.draft()))

    assertTrue("upl_2" in harness.calls.last().body.decodeToString())
  }

  @Test
  fun everyStatusMapsAndAnUnknownOneIsInReview() = runTest {
    val statuses =
      mapOf(
        "SUBMITTED" to ReportStatus.SUBMITTED,
        "IN_REVIEW" to ReportStatus.IN_REVIEW,
        "REVIEWED" to ReportStatus.REVIEWED,
        "NEED_REVISION" to ReportStatus.NEED_REVISION,
        "SOMETHING_NEW" to ReportStatus.IN_REVIEW,
      )
    statuses.forEach { (wire, expected) ->
      val harness = Harness { _, _ -> json("""{"reportId":"r","status":"$wire"}""") }
      assertEquals(expected, harness.reports.status("r"))
    }
  }

  @Test
  fun aMissingReportIsReportNotFound() = runTest {
    val harness = Harness { _, _ -> apiError("report_not_found", 404) }

    val failure = assertFailsWith<TraciveReportException> { harness.reports.status("a/b") }

    assertEquals(ReportErrorCode.REPORT_NOT_FOUND, failure.code)
    assertFalse("a/b" in harness.calls.single().url, "an id must not add a path segment")
  }
}
