package id.tracive.sdk.client

import id.tracive.sdk.TraciveException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a non-2xx [HttpResponse] into a typed `TraciveException`.
 *
 * The mapping table itself lives in `TraciveException.fromApiError`, the one place the Tracive
 * API's documented error table is implemented. This object's job is only to get the three envelope
 * fields out of a body that may not have the documented shape, and to read `Retry-After`.
 *
 * Every parse failure here degrades to "no code", which still maps to a typed error carrying the
 * status. A malformed error body must never become a crash or, worse, a success.
 */
internal object ApiErrors {

  /** The parsed error envelope: whatever of it could be read. */
  data class Envelope(val code: String?, val message: String?, val checkId: String?)

  /**
   * Reads the error envelope out of [body].
   *
   * Two shapes are accepted. The Tracive API sends `{"error":{"code","message","checkId"}}`. The
   * OAuth endpoints may also answer in RFC 6749's form,
   * `{"error":"invalid_grant","error_description":"…"}`, where `error` is a bare string — a
   * standards-compliant authorization server may well do that, and reading only the nested shape
   * would silently discard the reason a token exchange failed.
   */
  fun parseEnvelope(body: String): Envelope {
    if (body.isBlank()) return EMPTY
    val root =
      try {
        SdkJson.parseToJsonElement(body) as? JsonObject
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Throwable) {
        null
      } ?: return EMPTY

    return when (val error = root["error"]) {
      is JsonObject ->
        Envelope(
          code = error["code"].stringOrNull(),
          message = error["message"].stringOrNull(),
          checkId = error["checkId"].stringOrNull(),
        )
      is JsonPrimitive ->
        Envelope(
          code = error.contentOrNull,
          message =
            root["error_description"].stringOrNull() ?: root["errorDescription"].stringOrNull(),
          checkId = root["checkId"].stringOrNull(),
        )
      else -> EMPTY
    }
  }

  /**
   * The typed error for a non-2xx [response].
   *
   * `Retry-After` is read only in its delta-seconds form. The HTTP-date form is ignored rather than
   * parsed: it would need a date parser and a trusted local clock, and a null retry hint costs a
   * caller nothing — it just has to choose its own backoff.
   */
  fun toException(response: HttpResponse): TraciveException {
    val envelope = parseEnvelope(response.body)
    return TraciveException.fromApiError(
      status = response.status,
      code = envelope.code,
      message = envelope.message,
      checkId = envelope.checkId,
      retryAfter = retryAfterOf(response),
    )
  }

  /** The `Retry-After` header in delta-seconds form, or null. */
  fun retryAfterOf(response: HttpResponse): Duration? =
    response.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.seconds

  private val EMPTY = Envelope(null, null, null)

  private fun JsonElement?.stringOrNull(): String? =
    try {
      this?.jsonPrimitive?.contentOrNull
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Throwable) {
      null
    }
}
