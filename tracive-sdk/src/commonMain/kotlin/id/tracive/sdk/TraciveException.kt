package id.tracive.sdk

import kotlin.time.Duration

/**
 * Every error the Tracive SDK raises.
 *
 * The hierarchy is sealed so a caller can exhaustively `when` over it. The mapping from the wire
 * envelope `{"error":{"code","message","checkId"}}` onto these types is [fromApiError] — the one
 * place the Tracive API's documented error table is implemented. The transport layer should call it
 * rather than re-deriving the mapping.
 *
 * Messages are deliberately free of user data. No phone number, account number, email address,
 * access token or refresh token ever reaches a message string, because messages end up in crash
 * reports and log aggregators.
 *
 * Each subclass takes a nullable `message`: pass the server's `error.message` when there is one and
 * `null` to get the SDK's own wording.
 */
sealed class TraciveException(
  message: String,
  /** The raw `error.code` the server sent, when this error came from a response. */
  val errorCode: String? = null,
  /** The HTTP status this error was mapped from, when it came from a response. */
  val httpStatus: Int? = null,
  /** The server-side check id, echoed in the error envelope so support can trace the call. */
  val checkId: String? = null,
  cause: Throwable? = null,
) : Exception(message, cause) {

  // ---------------------------------------------------------------- 400 ----

  /** 400 `invalid_request` — the request was malformed or violated a documented constraint. */
  class InvalidRequest(message: String? = null, checkId: String? = null) :
    TraciveException(
      message ?: "The request was rejected as invalid.",
      CODE_INVALID_REQUEST,
      400,
      checkId,
    )

  /** 400 `unsupported_input_type` — the server does not index this kind of identifier. */
  class UnsupportedInputType(message: String? = null, checkId: String? = null) :
    TraciveException(
      message ?: "The server does not support this input type.",
      CODE_UNSUPPORTED_INPUT_TYPE,
      400,
      checkId,
    )

  /**
   * 400 `unsupported_credit_source` — the requested credit source is not enabled for this client.
   */
  class UnsupportedCreditSource(message: String? = null, checkId: String? = null) :
    TraciveException(
      message ?: "The requested credit source is not supported.",
      CODE_UNSUPPORTED_CREDIT_SOURCE,
      400,
      checkId,
    )

  // ---------------------------------------------------------------- 401 ----

  /** 401 `invalid_token` — the access token was rejected. Re-authorise. */
  class InvalidToken(message: String? = null, checkId: String? = null) :
    TraciveException(message ?: "The access token was rejected.", CODE_INVALID_TOKEN, 401, checkId)

  /**
   * 401 `expired_token` — the access token has expired.
   *
   * The client refreshes on this, single-flight: concurrent 401s must trigger exactly one refresh
   * and the rest must await its result. Firing several refreshes trips the server's refresh-token
   * reuse detection, which revokes the entire grant.
   */
  class ExpiredToken(message: String? = null, checkId: String? = null) :
    TraciveException(message ?: "The access token has expired.", CODE_EXPIRED_TOKEN, 401, checkId)

  /**
   * 401 `grant_revoked` — the user or the server revoked the grant.
   *
   * Terminal. Clear the stored tokens and surface this; never retry, and never attempt a refresh.
   */
  class GrantRevoked(message: String? = null, checkId: String? = null) :
    TraciveException(
      message ?: "The authorization grant was revoked.",
      CODE_GRANT_REVOKED,
      401,
      checkId,
    )

  /**
   * 401 `invalid_api_key`.
   *
   * The SDK never sends an API key — there is no API-key parameter anywhere in its public surface,
   * in any mode, and that is a hard design invariant. This case exists only so that a response
   * carrying the code (from a partner proxy that does use one) maps to something typed rather than
   * crashing the caller.
   */
  class InvalidApiKey(message: String? = null, checkId: String? = null) :
    TraciveException(
      message ?: "The upstream rejected an API key.",
      CODE_INVALID_API_KEY,
      401,
      checkId,
    )

  // ---------------------------------------------------------------- 402 ----

  /**
   * 402 — the check could not be paid for.
   *
   * [which] is the raw server code: [CODE_INSUFFICIENT_USER_CREDITS], [CODE_INSUFFICIENT_CREDITS]
   * or [CODE_INSUFFICIENT_PARTNER_BALANCE]. It is a `String` rather than an enum so that a phase 2
   * code added server-side degrades to a readable value instead of failing to map.
   *
   * Handle this rather than calling `credits()` before every check: a pre-flight call doubles the
   * request count per transaction and still races with concurrent spending.
   */
  class InsufficientCredits(val which: String, message: String? = null, checkId: String? = null) :
    TraciveException(
      message ?: "The account has insufficient credits for this check.",
      which,
      402,
      checkId,
    )

  // ---------------------------------------------------------------- 403 ----

  /** 403 `missing_scope` — the granted scopes do not cover this call. */
  class MissingScope(message: String? = null, checkId: String? = null) :
    TraciveException(
      message ?: "The granted scopes do not cover this call.",
      CODE_MISSING_SCOPE,
      403,
      checkId,
    )

  /** 403 `detail_not_permitted` — this client may not request `Detail.FULL`. */
  class DetailNotPermitted(message: String? = null, checkId: String? = null) :
    TraciveException(
      message ?: "This client is not permitted to request full detail.",
      CODE_DETAIL_NOT_PERMITTED,
      403,
      checkId,
    )

  /** 403 `credential_mismatch` — the credential does not belong to the calling client. */
  class CredentialMismatch(message: String? = null, checkId: String? = null) :
    TraciveException(
      message ?: "The credential does not belong to this client.",
      CODE_CREDENTIAL_MISMATCH,
      403,
      checkId,
    )

  /** 403 `partner_suspended` — the partner account is suspended. */
  class PartnerSuspended(message: String? = null, checkId: String? = null) :
    TraciveException(
      message ?: "The partner account is suspended.",
      CODE_PARTNER_SUSPENDED,
      403,
      checkId,
    )

  /**
   * 403 `user_credit_not_permitted` — this partner's contract does not allow charging the user's
   * subscription credits. Refused before any charge; retrying will not help.
   */
  class UserCreditNotPermitted(message: String? = null, checkId: String? = null) :
    TraciveException(
      message ?: "This client is not permitted to charge the user's subscription credits.",
      CODE_USER_CREDIT_NOT_PERMITTED,
      403,
      checkId,
    )

  /**
   * 403 `translation_not_permitted` — a `Detail.FULL` check asked for a non-English locale, and
   * either the partner's contract withholds translation or the user has no premium subscription.
   * Checking again without a locale returns the English result.
   */
  class TranslationNotPermitted(message: String? = null, checkId: String? = null) :
    TraciveException(
      message ?: "Translated results are not permitted for this request.",
      CODE_TRANSLATION_NOT_PERMITTED,
      403,
      checkId,
    )

  // ---------------------------------------------------------------- 409 ----

  /**
   * 409 `idempotency_conflict` — the same `Idempotency-Key` was reused with a different body.
   *
   * Generate a fresh key per logical check; reuse one only to retry that exact check.
   */
  class IdempotencyConflict(message: String? = null, checkId: String? = null) :
    TraciveException(
      message ?: "The idempotency key was reused with a different request.",
      CODE_IDEMPOTENCY_CONFLICT,
      409,
      checkId,
    )

  // ---------------------------------------------------------------- 429 ----

  /**
   * 429 `rate_limited`, or `daily_quota_exceeded` — tell them apart with [isDailyQuotaExceeded].
   *
   * [retryAfter] carries the `Retry-After` header when the server sent one. Retrying a
   * `checkScammer` without an `Idempotency-Key` risks a double charge — pass one before retrying.
   */
  class RateLimited
  internal constructor(
    val retryAfter: Duration?,
    message: String?,
    checkId: String?,
    code: String,
  ) : TraciveException(message ?: "The client is being rate limited.", code, 429, checkId) {

    constructor(
      retryAfter: Duration? = null,
      message: String? = null,
      checkId: String? = null,
    ) : this(retryAfter, message, checkId, CODE_RATE_LIMITED)

    /**
     * A daily allowance is spent — the connection's daily quota, its account reads, or the sandbox
     * org cap — rather than a per-second limit. Waiting a few seconds will not help: [retryAfter]
     * points at the reset at 00:00 UTC.
     */
    val isDailyQuotaExceeded: Boolean
      get() = errorCode == CODE_DAILY_QUOTA_EXCEEDED
  }

  // ------------------------------------------------------------ generic ----

  /**
   * A transport failure: DNS, TLS, connect, read timeout, cancelled socket. Never a server reply.
   */
  class Network(message: String? = null, cause: Throwable? = null) :
    TraciveException(
      message ?: "The request could not reach the Tracive API.",
      null,
      null,
      null,
      cause,
    )

  /** A 5xx reply. [errorCode] carries the server's raw code when it sent one. */
  class Server(
    status: Int,
    code: String? = null,
    message: String? = null,
    checkId: String? = null,
  ) : TraciveException(message ?: "The Tracive API returned a server error.", code, status, checkId)

  /**
   * A reply the SDK has no mapping for — an unrecognised `error.code`, or a status outside the
   * documented table. The raw code and status are preserved rather than discarded, so a phase 2
   * error surfaces as data instead of a crash.
   */
  class Unknown(
    status: Int? = null,
    code: String? = null,
    message: String? = null,
    checkId: String? = null,
  ) :
    TraciveException(
      message ?: "The Tracive API returned an unrecognised error.",
      code,
      status,
      checkId,
    )

  /**
   * The requested capability is not in this release.
   *
   * phase 1 ships OAuth mode only; every call made in `TraciveMode.Proxy` throws this. The Proxy
   * type exists in the surface now so that adding the behaviour in phase 2 breaks nothing.
   */
  class NotImplemented(message: String) : TraciveException(message)

  /**
   * The grant could not be written to the platform's secure store.
   *
   * Raised rather than returned quietly, and the distinction is the whole point. A refresh rotates
   * the refresh token: the server issues a new one and retires the old one at the same moment. If
   * the replacement cannot be persisted and the caller is told the save succeeded, this device is
   * left holding a token the server has already retired. Presenting it again is indistinguishable
   * from a stolen-token replay, so the server revokes the entire grant and the user is signed out
   * of an app that did nothing wrong — with nothing anywhere to explain it. Failing here means the
   * caller finds out *before* the retired token is sent.
   *
   * [platformStatus] carries the operating system's own numeric code when there was one — an
   * `OSStatus` from the iOS Keychain — and nothing else. Neither it nor the message ever holds the
   * value that failed to store, because both end up in crash reports.
   */
  class TokenStorageFailed(val platformStatus: Int? = null, message: String? = null) :
    TraciveException(message ?: "The authorization grant could not be stored on this device.")

  /**
   * `Source.CACHE` was requested and no live cache entry exists.
   *
   * A cache-only read never falls through to the network and never fabricates an empty result: an
   * empty [CheckResult][id.tracive.sdk.model.CheckResult] reads as "this identifier is clean",
   * which is the most dangerous wrong answer this SDK can give.
   */
  class CacheMiss(message: String? = null) :
    TraciveException(message ?: "No live cache entry for this query.")

  companion object {
    const val CODE_INVALID_REQUEST: String = "invalid_request"
    const val CODE_UNSUPPORTED_INPUT_TYPE: String = "unsupported_input_type"
    const val CODE_UNSUPPORTED_CREDIT_SOURCE: String = "unsupported_credit_source"
    const val CODE_INVALID_TOKEN: String = "invalid_token"
    const val CODE_EXPIRED_TOKEN: String = "expired_token"
    const val CODE_GRANT_REVOKED: String = "grant_revoked"
    const val CODE_INVALID_API_KEY: String = "invalid_api_key"
    const val CODE_INSUFFICIENT_USER_CREDITS: String = "insufficient_user_credits"
    const val CODE_INSUFFICIENT_CREDITS: String = "insufficient_credits"
    const val CODE_INSUFFICIENT_PARTNER_BALANCE: String = "insufficient_partner_balance"
    const val CODE_MISSING_SCOPE: String = "missing_scope"
    const val CODE_DETAIL_NOT_PERMITTED: String = "detail_not_permitted"
    const val CODE_CREDENTIAL_MISMATCH: String = "credential_mismatch"
    const val CODE_PARTNER_SUSPENDED: String = "partner_suspended"
    const val CODE_USER_CREDIT_NOT_PERMITTED: String = "user_credit_not_permitted"
    const val CODE_TRANSLATION_NOT_PERMITTED: String = "translation_not_permitted"
    const val CODE_IDEMPOTENCY_CONFLICT: String = "idempotency_conflict"
    const val CODE_RATE_LIMITED: String = "rate_limited"
    const val CODE_DAILY_QUOTA_EXCEEDED: String = "daily_quota_exceeded"

    /**
     * Map one parsed error envelope onto a typed exception.
     *
     * [code] is matched first, because the documented codes are unique across statuses; [status] is
     * consulted only when the code is absent or unrecognised. An unrecognised code is never fatal:
     * 5xx becomes [Server] and anything else becomes [Unknown], both carrying the raw code.
     *
     * @param status HTTP status of the reply.
     * @param code the envelope's `error.code`, or null when the body was not a Tracive envelope.
     * @param message the envelope's `error.message`; null falls back to the SDK's own wording.
     * @param checkId the envelope's `error.checkId`.
     * @param retryAfter the parsed `Retry-After` header, for [RateLimited].
     */
    fun fromApiError(
      status: Int,
      code: String?,
      message: String? = null,
      checkId: String? = null,
      retryAfter: Duration? = null,
    ): TraciveException =
      when (code) {
        CODE_INVALID_REQUEST -> InvalidRequest(message, checkId)
        CODE_UNSUPPORTED_INPUT_TYPE -> UnsupportedInputType(message, checkId)
        CODE_UNSUPPORTED_CREDIT_SOURCE -> UnsupportedCreditSource(message, checkId)
        CODE_INVALID_TOKEN -> InvalidToken(message, checkId)
        CODE_EXPIRED_TOKEN -> ExpiredToken(message, checkId)
        CODE_GRANT_REVOKED -> GrantRevoked(message, checkId)
        CODE_INVALID_API_KEY -> InvalidApiKey(message, checkId)
        CODE_INSUFFICIENT_USER_CREDITS ->
          InsufficientCredits(CODE_INSUFFICIENT_USER_CREDITS, message, checkId)
        CODE_INSUFFICIENT_CREDITS ->
          InsufficientCredits(CODE_INSUFFICIENT_CREDITS, message, checkId)
        CODE_INSUFFICIENT_PARTNER_BALANCE ->
          InsufficientCredits(CODE_INSUFFICIENT_PARTNER_BALANCE, message, checkId)
        CODE_MISSING_SCOPE -> MissingScope(message, checkId)
        CODE_DETAIL_NOT_PERMITTED -> DetailNotPermitted(message, checkId)
        CODE_CREDENTIAL_MISMATCH -> CredentialMismatch(message, checkId)
        CODE_PARTNER_SUSPENDED -> PartnerSuspended(message, checkId)
        CODE_USER_CREDIT_NOT_PERMITTED -> UserCreditNotPermitted(message, checkId)
        CODE_TRANSLATION_NOT_PERMITTED -> TranslationNotPermitted(message, checkId)
        CODE_IDEMPOTENCY_CONFLICT -> IdempotencyConflict(message, checkId)
        CODE_RATE_LIMITED -> RateLimited(retryAfter, message, checkId)
        CODE_DAILY_QUOTA_EXCEEDED ->
          RateLimited(retryAfter, message, checkId, CODE_DAILY_QUOTA_EXCEEDED)
        else ->
          when {
            status == 429 -> RateLimited(retryAfter, message, checkId, code ?: CODE_RATE_LIMITED)
            status in 500..599 -> Server(status, code, message, checkId)
            else -> Unknown(status, code, message, checkId)
          }
      }
  }
}

/**
 * A query could not be built, so no request was made and no credit was spent.
 *
 * [reason] is a stable machine-readable token from `query-grammar-vectors.json`, or — for
 * [EMPTY_COUNTRY], [UNKNOWN_COUNTRY], [EMPTY_PHONE_NUMBER] and [COUNTRY_CONFLICT], which
 * `normalizePhoneNumber` raises — from `phone-normalization-vectors.json`: [NOT_E164],
 * [NOT_AN_EMAIL], [COUNTRY_NOT_ALPHA3], [EMPTY_ACCOUNT_ID], [EMPTY_BANK_CODE], [UNKNOWN_BANK_CODE],
 * [EMPTY_VALUE], [EMPTY_HANDLE], [EMPTY_CHAIN], [EMPTY_ADDRESS], [EMPTY_PLATFORM] or
 * [INVALID_DOCUMENT_ID]. Branch on it rather than on the message: the token is identical across the
 * Kotlin, Flutter and Swift SDKs, whereas the wording is not.
 *
 * Validation always runs before the network call. A key that cannot possibly match a stored
 * document still costs a credit when the server answers "not found", so the SDK refuses to send it.
 *
 * Neither [reason] nor the message ever contains the rejected input.
 */
class TraciveQueryException(val reason: String) :
  TraciveException("The query could not be built: $reason") {

  companion object {
    /** The number is not E.164 once formatting punctuation is stripped. */
    const val NOT_E164: String = "not_e164"

    /** The country is not an ISO 3166-1 alpha-3 code. */
    const val COUNTRY_NOT_ALPHA3: String = "country_not_alpha3"

    /** `normalizePhoneNumber` was given a country or dial code with no letters and no digits. */
    const val EMPTY_COUNTRY: String = "empty_country"

    /**
     * `normalizePhoneNumber` was given something that looks like a country code but names no
     * country. An alpha-2 code such as `ID` lands here: this SDK uses alpha-3 everywhere.
     */
    const val UNKNOWN_COUNTRY: String = "unknown_country"

    /**
     * `normalizePhoneNumber` was given a number with no digits, or one that was only a trunk
     * prefix.
     */
    const val EMPTY_PHONE_NUMBER: String = "empty_phone_number"

    /**
     * The number carries a `+` whose dial code disagrees with the country named. Both cannot be
     * right, and guessing which is would produce a key that can never match — billed as a miss.
     */
    const val COUNTRY_CONFLICT: String = "country_conflict"

    /** The bank account number or IBAN folded to an empty string. */
    const val EMPTY_ACCOUNT_ID: String = "empty_account_id"

    /** The bank code folded to an empty string. */
    const val EMPTY_BANK_CODE: String = "empty_bank_code"

    /**
     * The bank short code or BIC is unknown or malformed: the country lists no bank under it, or
     * the value is not a BIC at all. Rejected locally, so it is never billed.
     */
    const val UNKNOWN_BANK_CODE: String = "unknown_bank_code"

    /**
     * The email address folded to an empty string.
     *
     * The other empty fields each have a token of their own — [EMPTY_ACCOUNT_ID],
     * [EMPTY_BANK_CODE], [EMPTY_HANDLE], [EMPTY_CHAIN], [EMPTY_ADDRESS], [EMPTY_PLATFORM] — so this
     * one names exactly one mistake rather than standing in for several.
     */
    const val EMPTY_VALUE: String = "empty_value"

    /**
     * The email address contains no `@`.
     *
     * The check is `@`-presence and nothing more. The server validates no shape at all, so a value
     * such as `budi` becomes a legal record key that is looked up, missed and billed; a fuller
     * address grammar, on the other hand, would reject addresses the server would have matched.
     */
    const val NOT_AN_EMAIL: String = "not_an_email"

    /** The social-media handle folded to an empty string. */
    const val EMPTY_HANDLE: String = "empty_handle"

    /** The crypto chain code folded to an empty string. */
    const val EMPTY_CHAIN: String = "empty_chain"

    /** The crypto wallet address folded to an empty string. */
    const val EMPTY_ADDRESS: String = "empty_address"

    /** The social-media platform folded to an empty string. */
    const val EMPTY_PLATFORM: String = "empty_platform"

    /** The folded key is not a legal record key. */
    const val INVALID_DOCUMENT_ID: String = "invalid_document_id"
  }
}
