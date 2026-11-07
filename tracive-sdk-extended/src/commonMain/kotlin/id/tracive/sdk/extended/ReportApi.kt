package id.tracive.sdk.extended

import id.tracive.sdk.TraciveException
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val ReportJson = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
}

/** Authorized JSON calls to the Open API. */
internal class ReportApi(
  private val baseUrl: String,
  private val tokens: AccessTokenProvider,
  private val http: HttpClient,
) {

  suspend fun get(path: String): String = authorized { token ->
    http.get("$baseUrl$path") { auth(token) }
  }

  suspend fun post(path: String, json: String, idempotencyKey: String? = null): String =
    authorized { token ->
      http.post("$baseUrl$path") {
        auth(token)
        idempotencyKey?.let { header(IDEMPOTENCY_KEY, it) }
        contentType(ContentType.Application.Json)
        setBody(json)
      }
    }

  private fun HttpRequestBuilder.auth(token: String) = bearerAuth(token)

  /** One call, retried once with a fresh token when the server refuses the first. */
  private suspend fun authorized(call: suspend (String) -> HttpResponse): String {
    val first = token(null)
    val answer = send(call, first)
    if (answer.status != 401) return answer.bodyOrThrow()
    val retried = send(call, token(first))
    if (retried.status == 401) throw needsConsent()
    return retried.bodyOrThrow()
  }

  private suspend fun token(stale: String?): String =
    try {
      tokens.accessToken(stale)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: TraciveException) {
      throw failure.toReportException()
    }

  private suspend fun send(call: suspend (String) -> HttpResponse, token: String): Answer =
    networked {
      val response = call(token)
      Answer(
        response.status.value,
        response.bodyAsText(),
        response.headers[HttpHeaders.RetryAfter]?.toLongOrNull(),
      )
    }

  private class Answer(val status: Int, val body: String, val retryAfterSeconds: Long?) {
    fun bodyOrThrow(): String =
      if (status in 200..299) body else throw apiFailure(status, body, retryAfterSeconds)
  }

  private companion object {
    const val IDEMPOTENCY_KEY = "Idempotency-Key"
  }
}

/** Turns a transport failure into [ReportErrorCode.NETWORK], leaving cancellation alone. */
internal suspend fun <T> networked(block: suspend () -> T): T =
  try {
    block()
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (failure: TraciveReportException) {
    throw failure
  } catch (failure: Exception) {
    throw TraciveReportException(
      ReportErrorCode.NETWORK,
      "The Tracive API could not be reached.",
      cause = failure,
    )
  }

internal fun needsConsent(httpStatus: Int? = null): TraciveReportException =
  TraciveReportException(
    ReportErrorCode.NEEDS_CONSENT,
    "This user has not granted the report permission. Authorize, then call again.",
    httpStatus,
  )

private fun TraciveException.toReportException(): TraciveReportException =
  when (this) {
    is TraciveException.InvalidToken,
    is TraciveException.ExpiredToken,
    is TraciveException.GrantRevoked -> needsConsent(httpStatus)
    is TraciveException.Network ->
      TraciveReportException(
        ReportErrorCode.NETWORK,
        "The Tracive API could not be reached.",
        cause = this,
      )
    else ->
      TraciveReportException(
        ReportErrorCode.SERVER,
        message ?: "The token call failed.",
        httpStatus,
        cause = this,
      )
  }

private fun apiFailure(
  status: Int,
  body: String,
  retryAfterSeconds: Long?,
): TraciveReportException {
  val error = errorEnvelope(body)
  val wireCode = error?.get("code")?.jsonPrimitive?.contentOrNull
  val message = error?.get("message")?.jsonPrimitive?.contentOrNull
  val code =
    when (wireCode) {
      "missing_scope",
      "invalid_token",
      "expired_token",
      "grant_revoked" -> return needsConsent(status)
      "email_not_verified" -> ReportErrorCode.EMAIL_NOT_VERIFIED
      "report_not_found" -> ReportErrorCode.REPORT_NOT_FOUND
      "terms_outdated" -> ReportErrorCode.TERMS_OUTDATED
      "invalid_report" -> ReportErrorCode.INVALID_REPORT
      "attachment_invalid" -> ReportErrorCode.ATTACHMENT_INVALID
      "daily_quota_exceeded" -> ReportErrorCode.DAILY_LIMIT_REACHED
      "reporting_disabled" -> ReportErrorCode.REPORTING_DISABLED
      else -> ReportErrorCode.SERVER
    }
  return TraciveReportException(
    code,
    message ?: "The Tracive API answered $status.",
    status,
    retryAfterSeconds,
  )
}

private fun errorEnvelope(body: String): JsonObject? =
  runCatching { ReportJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
