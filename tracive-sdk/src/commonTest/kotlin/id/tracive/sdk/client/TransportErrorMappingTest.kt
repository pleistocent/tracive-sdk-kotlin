package id.tracive.sdk.client

import id.tracive.sdk.TraciveException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The error table, exercised through a real HTTP reply rather than a pre-parsed envelope.
 *
 * `ErrorMappingTest` covers the mapping function itself. This covers the step before it: pulling
 * `code`, `message` and `checkId` out of a body that may not have the documented shape, and reading
 * `Retry-After`. That step is where a wrong answer is silent — a body the parser cannot read still
 * has to produce a typed error carrying the status, never a crash and never a success.
 */
class TransportErrorMappingTest {

  @Test
  fun everyDocumentedCodeMapsThroughARealResponse() {
    assertMaps(400, "invalid_request") { it is TraciveException.InvalidRequest }
    assertMaps(400, "unsupported_input_type") { it is TraciveException.UnsupportedInputType }
    assertMaps(400, "unsupported_credit_source") { it is TraciveException.UnsupportedCreditSource }
    assertMaps(401, "invalid_token") { it is TraciveException.InvalidToken }
    assertMaps(401, "expired_token") { it is TraciveException.ExpiredToken }
    assertMaps(401, "grant_revoked") { it is TraciveException.GrantRevoked }
    assertMaps(401, "invalid_api_key") { it is TraciveException.InvalidApiKey }
    assertMaps(402, "insufficient_user_credits") { it is TraciveException.InsufficientCredits }
    assertMaps(402, "insufficient_credits") { it is TraciveException.InsufficientCredits }
    assertMaps(402, "insufficient_partner_balance") { it is TraciveException.InsufficientCredits }
    assertMaps(403, "missing_scope") { it is TraciveException.MissingScope }
    assertMaps(403, "detail_not_permitted") { it is TraciveException.DetailNotPermitted }
    assertMaps(403, "credential_mismatch") { it is TraciveException.CredentialMismatch }
    assertMaps(403, "partner_suspended") { it is TraciveException.PartnerSuspended }
    assertMaps(409, "idempotency_conflict") { it is TraciveException.IdempotencyConflict }
    assertMaps(429, "rate_limited") { it is TraciveException.RateLimited }
    assertMaps(500, "internal_error") { it is TraciveException.Server }
  }

  @Test
  fun theEnvelopesCheckIdSurvivesSoSupportCanTraceTheCall() {
    val error =
      ApiErrors.toException(
        HttpResponse(403, errorJson("missing_scope", "Scope reports:read is missing.", "chk_88"))
      )

    assertEquals("chk_88", error.checkId)
    assertEquals("Scope reports:read is missing.", error.message)
    assertEquals(403, error.httpStatus)
  }

  @Test
  fun retryAfterIsReadFromTheHeader() {
    val error =
      ApiErrors.toException(
        HttpResponse(429, errorJson("rate_limited"), mapOf("Retry-After" to "45"))
      )

    assertTrue(error is TraciveException.RateLimited)
    assertEquals(45.seconds, (error as TraciveException.RateLimited).retryAfter)
  }

  /**
   * The HTTP-date form of `Retry-After` is deliberately not parsed: it needs a date parser and a
   * trusted local clock, and a missing hint costs a caller nothing but its own backoff choice.
   */
  @Test
  fun anUnparseableRetryAfterIsDroppedRatherThanGuessed() {
    val error =
      ApiErrors.toException(
        HttpResponse(
          429,
          errorJson("rate_limited"),
          mapOf("retry-after" to "Wed, 21 Oct 2026 07:28:00 GMT"),
        )
      )

    assertNull((error as TraciveException.RateLimited).retryAfter)
  }

  /** An OAuth endpoint may answer in RFC 6749's shape, where `error` is a bare string. */
  @Test
  fun theRfc6749ErrorShapeIsUnderstoodToo() {
    val envelope =
      ApiErrors.parseEnvelope(
        "{\"error\":\"invalid_grant\",\"error_description\":\"The code was already redeemed.\"}"
      )

    assertEquals("invalid_grant", envelope.code)
    assertEquals("The code was already redeemed.", envelope.message)
  }

  @Test
  fun aBodyThatIsNotJsonStillProducesATypedError() {
    for (body in listOf("", "   ", "<html>502 Bad Gateway</html>", "null", "[1,2,3]", "{")) {
      val error = ApiErrors.toException(HttpResponse(502, body))
      assertTrue(error is TraciveException.Server, "body ${body.take(12)} did not map")
      assertEquals(502, error.httpStatus)
      assertNull(error.errorCode)
    }
  }

  @Test
  fun anEnvelopeWithTheWrongFieldTypesDoesNotCrashTheParser() {
    val envelope = ApiErrors.parseEnvelope("{\"error\":{\"code\":404,\"message\":[\"a\"]}}")
    // A non-string field reads as absent rather than throwing: the point of this layer is that no
    // malformed error body can turn into a crash, or into a success.
    assertNull(envelope.message)
  }

  private fun assertMaps(status: Int, code: String, predicate: (TraciveException) -> Boolean) {
    val error = ApiErrors.toException(HttpResponse(status, errorJson(code)))
    assertTrue(predicate(error), "$status/$code mapped to ${error::class.simpleName}")
    assertEquals(code, error.errorCode, "$status/$code lost its raw code")
    assertEquals(status, error.httpStatus, "$status/$code lost its status")
  }
}
