package id.tracive.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The wire-error table from the frozen spec.
 *
 * Every branch matters to a caller's control flow: `ExpiredToken` triggers a single-flight refresh,
 * `GrantRevoked` must clear the tokens and stop, `InsufficientCredits` is a prompt to top up, and
 * `RateLimited` carries a wait. Getting one of them wrong is not a cosmetic bug — mapping
 * `grant_revoked` to something retryable, for instance, would drive repeated refresh attempts into
 * the server's token-reuse detection.
 *
 * These are pure function calls on a parsed envelope. Nothing here needs a server.
 */
class ErrorMappingTest {

  @Test
  fun everyDocumentedCodeMapsToItsType() {
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
    assertMaps(403, "user_credit_not_permitted") { it is TraciveException.UserCreditNotPermitted }
    assertMaps(403, "translation_not_permitted") { it is TraciveException.TranslationNotPermitted }
    assertMaps(409, "idempotency_conflict") { it is TraciveException.IdempotencyConflict }
    assertMaps(429, "rate_limited") { it is TraciveException.RateLimited }
    assertMaps(429, "daily_quota_exceeded") { it is TraciveException.RateLimited }
  }

  /**
   * A spent daily quota stays [TraciveException.RateLimited], so a caller's existing handler keeps
   * working, and says which it is: its wait runs to 00:00 UTC, not a few seconds.
   */
  @Test
  fun aDailyQuotaIsARateLimitThatSaysSo() {
    val error =
      TraciveException.fromApiError(429, "daily_quota_exceeded", retryAfter = 43_200.seconds)

    assertTrue(error is TraciveException.RateLimited)
    assertTrue(error.isDailyQuotaExceeded)
    assertEquals("daily_quota_exceeded", error.errorCode)
    assertEquals(43_200.seconds, error.retryAfter)

    val burst = TraciveException.fromApiError(429, "rate_limited") as TraciveException.RateLimited
    assertFalse(burst.isDailyQuotaExceeded)
    val unlabelled = TraciveException.fromApiError(429, null) as TraciveException.RateLimited
    assertFalse(unlabelled.isDailyQuotaExceeded)
    assertFalse(TraciveException.RateLimited().isDailyQuotaExceeded)
  }

  @Test
  fun anUnrecognised429KeepsItsRawCode() {
    val error = TraciveException.fromApiError(429, "slow_down")
    assertTrue(error is TraciveException.RateLimited)
    assertEquals("slow_down", error.errorCode)
  }

  @Test
  fun theEnvelopeIsCarriedOntoTheException() {
    val error =
      TraciveException.fromApiError(
        status = 403,
        code = "missing_scope",
        message = "Scope reports:read was not granted.",
        checkId = "chk_77",
      )

    assertEquals("missing_scope", error.errorCode)
    assertEquals(403, error.httpStatus)
    assertEquals("chk_77", error.checkId)
    assertEquals("Scope reports:read was not granted.", error.message)
  }

  @Test
  fun theSdksOwnWordingIsUsedWhenTheServerSendsNoMessage() {
    val error = TraciveException.fromApiError(status = 401, code = "expired_token")
    assertTrue(error.message?.isNotBlank() == true, "an error must always carry readable text")
    assertEquals(401, error.httpStatus)
  }

  /**
   * The three 402 codes collapse to one type, so a caller does not have to branch on which balance
   * ran out to know the check was not paid for. The raw code stays available on `which`.
   */
  @Test
  fun theThreeCreditCodesKeepTheirIdentity() {
    for (code in
      listOf("insufficient_user_credits", "insufficient_credits", "insufficient_partner_balance")) {
      val error = TraciveException.fromApiError(402, code)
      assertTrue(error is TraciveException.InsufficientCredits, "$code did not map to 402")
      assertEquals(code, (error as TraciveException.InsufficientCredits).which)
      assertEquals(code, error.errorCode)
    }
  }

  @Test
  fun rateLimitedCarriesTheRetryAfterHeader() {
    val error = TraciveException.fromApiError(429, "rate_limited", retryAfter = 30.seconds)
    assertTrue(error is TraciveException.RateLimited)
    assertEquals(30.seconds, (error as TraciveException.RateLimited).retryAfter)

    // A 429 with no recognised code is still a rate limit, and still carries the wait.
    val fallback = TraciveException.fromApiError(429, null, retryAfter = 5.seconds)
    assertTrue(fallback is TraciveException.RateLimited)
    assertEquals(5.seconds, (fallback as TraciveException.RateLimited).retryAfter)

    // The header is optional.
    val noHeader = TraciveException.fromApiError(429, "rate_limited")
    assertTrue(noHeader is TraciveException.RateLimited)
    assertNull((noHeader as TraciveException.RateLimited).retryAfter)
  }

  /**
   * An unrecognised code must never crash the caller, and must never be silently discarded either:
   * the raw code is the only clue anyone will have about a phase 2 error reaching a phase 1 app.
   */
  @Test
  fun anUnknownCodeDegradesWithoutLosingTheRawCode() {
    val serverSide = TraciveException.fromApiError(503, "region_evacuated", checkId = "chk_9")
    assertTrue(serverSide is TraciveException.Server)
    assertEquals("region_evacuated", serverSide.errorCode)
    assertEquals(503, serverSide.httpStatus)
    assertEquals("chk_9", serverSide.checkId)

    val clientSide = TraciveException.fromApiError(418, "brewing")
    assertTrue(clientSide is TraciveException.Unknown)
    assertEquals("brewing", clientSide.errorCode)
    assertEquals(418, clientSide.httpStatus)
  }

  @Test
  fun aBodyThatIsNotAnEnvelopeStillMaps() {
    val server = TraciveException.fromApiError(500, null)
    assertTrue(server is TraciveException.Server)
    assertNull(server.errorCode)

    val unknown = TraciveException.fromApiError(302, null)
    assertTrue(unknown is TraciveException.Unknown)
    assertNull(unknown.errorCode)
  }

  @Test
  fun transportFailuresKeepTheirCause() {
    val cause = IllegalStateException("socket closed")
    val error = TraciveException.Network(cause = cause)
    assertEquals(cause, error.cause)
    assertNull(error.httpStatus, "a transport failure never had a status")
  }

  /**
   * A query rejection carries the fixture's own reason token and nothing else. It must never carry
   * the rejected input: these messages end up in logs and crash reports.
   */
  @Test
  fun aQueryRejectionNamesTheReasonAndNotTheInput() {
    // Typed as the base class deliberately: a query failure must be catchable alongside every
    // other SDK error, so this assignment is itself part of the assertion.
    val error: TraciveException = TraciveQueryException(TraciveQueryException.NOT_E164)
    assertEquals("not_e164", (error as TraciveQueryException).reason)
    assertTrue(error.message?.contains("not_e164") == true)
    assertNull(error.httpStatus, "nothing was sent, so there is no status")
    assertNull(error.errorCode, "the reason token is not a server error code")
  }

  private fun assertMaps(status: Int, code: String, predicate: (TraciveException) -> Boolean) {
    val error = TraciveException.fromApiError(status, code)
    assertTrue(predicate(error), "$status/$code mapped to ${error::class.simpleName}")
    assertEquals(code, error.errorCode, "$status/$code must keep its raw code")
    assertEquals(status, error.httpStatus, "$status/$code must keep its status")
  }
}
