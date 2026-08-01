package id.tracive.sdk.client

import id.tracive.sdk.TraciveException
import id.tracive.sdk.cache.CheckCache
import id.tracive.sdk.cache.DisabledCheckCache
import id.tracive.sdk.model.CachePolicy
import id.tracive.sdk.model.CheckResult
import id.tracive.sdk.model.CreditsInfo
import id.tracive.sdk.model.Detail
import id.tracive.sdk.model.Origin
import id.tracive.sdk.model.ProfileInfo
import id.tracive.sdk.model.Source
import id.tracive.sdk.model.TraciveEnvironment
import id.tracive.sdk.model.TraciveMode
import id.tracive.sdk.oauth.TokenStore
import id.tracive.sdk.oauth.TraciveOAuth
import id.tracive.sdk.platform.AppPresenceChecker
import id.tracive.sdk.platform.AuthorizeLauncher
import id.tracive.sdk.query.TraciveQuery
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.serialization.DeserializationStrategy

/**
 * The Tracive SDK's entry point: check an identifier, read the user's credits, read their profile.
 *
 * ```
 * val client = TraciveClient(
 *   mode = TraciveMode.OAuth(
 *     clientId = "cli_bank_x",
 *     redirectUri = "https://bank.example/tracive/callback",
 *     scopes = listOf(TraciveScopes.SCAMMER_CHECK),
 *     partnerUserId = internalUserId,   // never a phone number or an email
 *   ),
 * )
 *
 * // Opens the consent page and returns. The outcome arrives at handleCallback, wherever the OS
 * // delivers the redirect — see TraciveOAuth.
 * client.oauth!!.authorize()
 * when (val auth = client.oauth!!.handleCallback(redirectUrl)) { /* … */ }
 *
 * val result = client.checkScammer(TraciveQuery.phone("+6281234567891"))
 * // Always show result.disclaimer next to result.riskTier.
 * ```
 *
 * ### What this class guarantees
 *
 * - **No API key.** There is no API-key parameter here, in [TraciveMode], or anywhere else in the
 *   public surface, in any mode. A key shipped inside a mobile binary is a key that has been
 *   published; the design invariant is that the SDK never holds one.
 * - **Nothing is logged.** No identifier, no folded query, no token, no response body. The types
 *   that hold those values all have redacted `toString`s, and this class adds no logging of its
 *   own.
 * - **A query is validated before a request is sent.** `TraciveQuery` rejects unusable input at
 *   construction, because the server bills a *miss* as well as a hit.
 * - **A check is never blindly retried.** See [checkScammer].
 *
 * ### phase 1 scope
 *
 * The backend Open API is not live yet, and only [TraciveMode.OAuth] is implemented. Every call in
 * [TraciveMode.Proxy] throws [TraciveException.NotImplemented]; the mode exists in the surface now
 * so that adding it in phase 2 breaks no one.
 *
 * @param transport the HTTP seam. Injecting one is how the whole SDK is tested without a server —
 *   which matters more than usual here, because there is no server to test against yet. The default
 *   builds a [KtorTransport] lazily, on first use.
 * @param clock test seam for TTL and token-expiry arithmetic.
 * @param cache where results are kept between calls. Defaults to [DisabledCheckCache]: the
 *   persistent cache needs a platform database and a platform key store, which a host wires up and
 *   passes here. Nothing about correctness depends on it — a disabled cache only costs credits.
 * @param tokenStore where the grant is kept. Defaults to the platform's hardware-backed store.
 */
class TraciveClient(
  /** How the client authenticates. phase 1 implements [TraciveMode.OAuth] only. */
  val mode: TraciveMode,

  /** Which deployment to talk to. */
  val environment: TraciveEnvironment = TraciveEnvironment.Production,

  /** How long results stay usable, per risk tier. */
  val cachePolicy: CachePolicy = CachePolicy.Default,

  /** The [Source] used by [checkScammer] when the caller does not name one. */
  val defaultSource: Source = Source.CACHE_FIRST,
  transport: HttpTransport? = null,
  clock: Clock? = null,
  cache: CheckCache? = null,
  tokenStore: TokenStore? = null,

  /** When the SDK may repeat a request. Read [RetryPolicy] before widening it. */
  val retryPolicy: RetryPolicy = RetryPolicy.Default,
  appPresenceChecker: AppPresenceChecker? = null,
  authorizeLauncher: AuthorizeLauncher? = null,

  /**
   * Replaces the page a user without the Tracive app is sent to. Null uses the default.
   *
   * Handed to [TraciveOAuth.installUrlOverride] unchanged, and it follows the same rule: null means
   * "use the default", any non-blank value is used verbatim, and a blank one is rejected rather
   * than quietly replaced by the default.
   *
   * @throws IllegalArgumentException if a blank string is passed. Leaving the parameter alone can
   *   never raise it.
   */
  installUrl: String? = null,
  authorizeEndpoint: String? = null,
) {

  init {
    // Checked here as well as in TraciveOAuth, because the flow below is built lazily.
    // Without this guard a blank would not surface until the host first reached for `oauth`,
    // and not at all in a run that never authorizes. A caller who leaves `installUrl` alone
    // passes null and is unaffected.
    TraciveOAuth.requireUsableInstallUrl(installUrl)
  }

  private val clock: Clock = clock ?: Clock.System

  private val ownsTransport: Boolean = transport == null

  /** Lazy: constructing the platform HTTP client must not be a side effect of building a client. */
  private val transportProvider = lazy { transport ?: KtorTransport() }

  /**
   * The local result cache, reachable so a host can clear it on sign-out and prune it on a
   * schedule. [checkScammer] reads and writes it on its own.
   */
  val cache: CheckCache =
    if (!cachePolicy.enabled) DisabledCheckCache else cache ?: DisabledCheckCache

  /**
   * Built on first use so that the two halves share one HTTP client: the flow is handed
   * [transportProvider]'s transport rather than making a second one, and it is therefore not the
   * owner — [close] here releases it exactly once.
   */
  private val oauthProvider =
    lazy<TraciveOAuth?> {
      when (mode) {
        is TraciveMode.OAuth ->
          TraciveOAuth(
            mode = mode,
            environment = environment,
            transport = transportProvider.value,
            tokenStore = tokenStore,
            appPresenceChecker = appPresenceChecker,
            authorizeLauncher = authorizeLauncher,
            clock = this.clock,
            installUrlOverride = installUrl,
            authorizeEndpointOverride = authorizeEndpoint,
          )
        is TraciveMode.Proxy -> null
      }
    }

  /**
   * The OAuth flow, or null in [TraciveMode.Proxy] — a proxied client has no OAuth grant of its own
   * because the partner's backend holds the credentials.
   */
  val oauth: TraciveOAuth?
    get() = oauthProvider.value

  // ----------------------------------------------------------------- calls ----

  /**
   * Checks one identifier.
   *
   * ### Cost
   *
   * Every request that reaches the server is billed, **including a miss** — the server did the
   * lookup either way. That single fact drives the rest of this method's behaviour: the query is
   * validated before anything is sent, a live cache entry is served without a request, and a failed
   * check is not repeated unless the caller made repeating it safe.
   *
   * ### Retries and idempotency
   *
   * A 5xx or a timeout does not say whether the server processed the check before it failed, so a
   * blind retry is a request to be charged twice. The SDK therefore retries a check **only when
   * [idempotencyKey] is set** — the server collapses the repeat onto the original charge, and there
   * is nothing to collapse onto without a key. Generate one key per logical check (a UUID is fine),
   * reuse it only to retry *that* check, and expect [TraciveException.IdempotencyConflict] if it is
   * reused with different content.
   *
   * A 429 is never auto-retried at all; [TraciveException.RateLimited.retryAfter] carries the
   * server's own hint so the app can decide where to wait.
   *
   * ### Cache and detail
   *
   * The cache is keyed on the query alone and holds only the fields that do not vary with `detail`
   * or `locale`, so it can answer a [Detail.MINIMAL] request and nothing else. A [Detail.FULL]
   * request always goes to the server, and [Source.CACHE] with [Detail.FULL] raises
   * [TraciveException.CacheMiss] rather than returning a result missing the narratives that were
   * asked for.
   *
   * @param source where to read from. Defaults to [defaultSource].
   * @param detail how much to return. [Detail.FULL] needs a scope most integrations do not have.
   * @param locale language for narrative text; ignored for [Detail.MINIMAL] and not sent there.
   * @throws TraciveException.CacheMiss for [Source.CACHE] with no live entry — never a silent empty
   *   result, which would read as "this identifier is clean".
   * @throws TraciveException.NotImplemented in [TraciveMode.Proxy].
   */
  suspend fun checkScammer(
    query: TraciveQuery,
    source: Source = defaultSource,
    detail: Detail = Detail.MINIMAL,
    locale: String? = null,
    idempotencyKey: String? = null,
  ): CheckResult {
    requireOAuthMode()

    // A cached entry can only satisfy a minimal request; full-detail content is never stored.
    val cacheReadable = detail == Detail.MINIMAL

    if (source == Source.CACHE) {
      if (!cacheReadable) {
        throw TraciveException.CacheMiss(
          "Full-detail results are never cached, so Source.CACHE cannot serve detail = FULL."
        )
      }
      return cache.get(query) ?: throw TraciveException.CacheMiss()
    }

    if (source == Source.CACHE_FIRST && cacheReadable) {
      cache.get(query)?.let {
        return it
      }
    }

    return if (source == Source.SERVER_FIRST) {
      try {
        fetchCheck(query, detail, locale, idempotencyKey)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (failure: TraciveException) {
        // Falling back is the point of SERVER_FIRST: the caller wanted freshness but still needs an
        // answer offline. A stale answer beats no answer for a fraud check; no answer beats a wrong
        // one, which is why a miss rethrows the original failure rather than inventing a result.
        //
        // "The server failed" here means a transport-shaped failure only. [isTransient] says
        // which ones qualify, and why every other failure has to reach the caller instead.
        (if (cacheReadable && failure.isTransient) cache.get(query) else null) ?: throw failure
      }
    } else {
      fetchCheck(query, detail, locale, idempotencyKey)
    }
  }

  /**
   * The authorised user's remaining check allowance.
   *
   * **Do not call this before each check.** Handle [TraciveException.InsufficientCredits] instead:
   * a pre-flight call doubles the requests per transaction and still races with anything else
   * spending the same balance, so it can report credits that are gone by the time the check runs.
   * It is worth saying because pre-checking is the default instinct.
   */
  suspend fun credits(): CreditsInfo {
    requireOAuthMode()
    val response = send(HttpMethod.GET, PATH_CREDITS, retryable = true)
    return decode(response, CreditsInfo.serializer())
  }

  /**
   * The authorised user, as the grant sees them. Only `sub` is guaranteed; the rest need scopes.
   */
  suspend fun profile(): ProfileInfo {
    requireOAuthMode()
    val response = send(HttpMethod.GET, PATH_PROFILE, retryable = true)
    return decode(response, ProfileInfo.serializer())
  }

  /**
   * Revokes the grant best-effort, clears the stored tokens, and empties the cache.
   *
   * The cache goes too, and that is not housekeeping: its entries were fetched under the grant of
   * the user who just signed out, and the next person to use this device must not inherit their
   * check history.
   */
  suspend fun signOut() {
    oauth?.signOut()
    cache.clear()
  }

  /** Releases the HTTP client, when this instance created it. Idempotent. */
  fun close() {
    // Deliberately does not touch `oauth`: building the flow just to close it would construct the
    // very HTTP client this method exists to avoid leaving behind.
    if (ownsTransport && transportProvider.isInitialized()) transportProvider.value.close()
  }

  // -------------------------------------------------------------- internals ----

  private suspend fun fetchCheck(
    query: TraciveQuery,
    detail: Detail,
    locale: String?,
    idempotencyKey: String?,
  ): CheckResult {
    val body =
      SdkJson.encodeToString(
        CheckRequestBody.serializer(),
        CheckRequestBody(
          creditSource = CheckRequestBody.CREDIT_SOURCE_USER,
          detail = detail,
          // The server ignores a locale for a minimal check, so it is not sent.
          locale = if (detail == Detail.FULL) locale else null,
          query = query.searchQueryIndex,
        ),
      )

    val response =
      send(
        method = HttpMethod.POST,
        path = PATH_SCAMMER_CHECKS,
        body = body,
        contentType = JSON_CONTENT_TYPE,
        idempotencyKey = idempotencyKey,
        // Without an idempotency key a repeat can be charged twice, so there is no retry.
        retryable = idempotencyKey != null,
      )

    val result =
      decode(response, CheckResult.serializer())
        .copy(origin = Origin.SERVER, fetchedAt = clock.now())

    // A full-detail answer is never stored. The old reasoning here — "`put` keeps only the
    // cacheable subset, so this is safe for a full-detail result too" — was wrong, and wrong in a
    // way the subset itself hides: `toCacheable()` drops `topChronologies`, `comments`, `billing`
    // and `checkId`, but it KEEPS `tags` and `disclaimer`, and both of those vary with `locale`.
    // The row key is `SHA-256(searchQueryIndex)` with no locale in it, and only a FULL request
    // sends a locale at all. So storing a full-detail answer files locale-specific text under a
    // locale-less key, and the next MINIMAL caller — which sends no locale — reads it straight back
    // as if it were the default. `disclaimer` is the one string this SDK asks every integrator to
    // put on screen, so the visible failure is a legal notice in a language nobody requested.
    //
    // Gating the read alone does not help: the row is poisoned on the way IN, by the FULL call,
    // and collected later by a MINIMAL call that is reading the cache quite legitimately. Relaxing
    // either half needs the key to become (query, detail, locale) first.
    if (detail != Detail.FULL) {
      cache.put(query, result)
    }
    return result
  }

  /**
   * Performs one API call: attaches the bearer token, applies the retry policy, and handles the one
   * 401 that is worth handling.
   *
   * The 401 path is deliberately narrow:
   * - `expired_token` → refresh **once**, single-flight, and repeat the request. A second 401 after
   *   a fresh token is not a token problem, so it surfaces.
   * - `grant_revoked` → clear the stored tokens and surface it. Never retry, never refresh: the
   *   grant is gone, and hammering it looks like a replay to the server.
   * - anything else → surface it.
   */
  private suspend fun send(
    method: HttpMethod,
    path: String,
    body: String? = null,
    contentType: String? = null,
    idempotencyKey: String? = null,
    retryable: Boolean,
  ): HttpResponse {
    val flow = oauth ?: throw notImplementedInProxyMode()
    var attempt = 1
    var refreshAttempted = false
    var token = flow.accessToken()

    while (true) {
      val headers = LinkedHashMap<String, String>()
      headers["Authorization"] = "Bearer $token"
      headers["Accept"] = JSON_CONTENT_TYPE
      if (idempotencyKey != null) headers[HEADER_IDEMPOTENCY_KEY] = idempotencyKey

      val request =
        HttpRequest(
          method = method,
          url = environment.baseUrl + path,
          headers = headers,
          body = body,
          contentType = contentType,
        )

      val response =
        try {
          transportProvider.value.execute(request)
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (failure: Throwable) {
          val error =
            if (failure is TraciveException) failure else TraciveException.Network(cause = failure)
          if (retryable && attempt < retryPolicy.maxAttempts && error is TraciveException.Network) {
            attempt += 1
            waitBeforeAttempt(attempt)
            continue
          }
          throw error
        }

      if (response.isSuccessful) return response

      if (response.status == 401) {
        val envelope = ApiErrors.parseEnvelope(response.body)
        when (envelope.code) {
          TraciveException.CODE_EXPIRED_TOKEN -> {
            if (refreshAttempted) throw ApiErrors.toException(response)
            refreshAttempted = true
            // Single-flight: concurrent 401s produce exactly one refresh. Several would spend the
            // rotating refresh token more than once, which the server reads as theft and answers by
            // revoking the whole grant.
            token = flow.refreshedToken(token)
            continue
          }
          TraciveException.CODE_GRANT_REVOKED -> {
            flow.clearTokens()
            throw ApiErrors.toException(response)
          }
          else -> throw ApiErrors.toException(response)
        }
      }

      if (retryable && response.status in 500..599 && attempt < retryPolicy.maxAttempts) {
        attempt += 1
        waitBeforeAttempt(attempt)
        continue
      }

      throw ApiErrors.toException(response)
    }
  }

  private suspend fun waitBeforeAttempt(attempt: Int) {
    val wait = retryPolicy.backoffBefore(attempt)
    if (wait > Duration.ZERO) delay(wait)
  }

  private fun <T> decode(response: HttpResponse, serializer: DeserializationStrategy<T>): T =
    try {
      SdkJson.decodeFromString(serializer, response.body)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Throwable) {
      // The body is never quoted into the message: a full-detail result contains reporter-written
      // text about named people, which does not belong in a crash report.
      throw TraciveException.Unknown(
        status = response.status,
        message = "The Tracive API returned a reply the SDK could not read.",
      )
    }

  private fun requireOAuthMode() {
    if (mode !is TraciveMode.OAuth) throw notImplementedInProxyMode()
  }

  private fun notImplementedInProxyMode(): TraciveException.NotImplemented =
    TraciveException.NotImplemented(PROXY_MODE_MESSAGE)

  companion object {
    /** `POST` here to run a check. Relative to the environment's base URL. */
    const val PATH_SCAMMER_CHECKS: String = "/scammer-checks"

    /** `GET` here for the user's remaining allowance. */
    const val PATH_CREDITS: String = "/credits"

    /** `GET` here for the authorised user. */
    const val PATH_PROFILE: String = "/profile"

    /** Header carrying a caller-supplied idempotency key. */
    const val HEADER_IDEMPOTENCY_KEY: String = "Idempotency-Key"

    /** Content type of every request body the API takes, apart from the OAuth endpoints. */
    const val JSON_CONTENT_TYPE: String = "application/json"

    /** The single wording for the phase 2 gap, so callers can match on it. */
    const val PROXY_MODE_MESSAGE: String = "Proxy mode arrives in phase 2"
  }
}

/**
 * Whether a cached verdict is allowed to stand in for this failure under [Source.SERVER_FIRST].
 *
 * Only a **transport-shaped** failure qualifies: the request did not reach a server that had an
 * opinion, or reached one that could not answer right now. Those are the cases where a stale answer
 * is better than none, because the answer the server would have given is unchanged and merely
 * unreachable.
 *
 * Everything else is a real answer *about the caller*. A 402 says the account cannot pay for this
 * check; a 403 `missing_scope` or `partner_suspended` says it may no longer ask; a revoked grant
 * says it is no longer anyone. Serving a cached verdict on top of any of those hides the finding
 * behind an answer that looks completely normal — a partner whose access was withdrawn keeps
 * receiving fraud verdicts until the entries age out, which for a `HIGH` result is thirty days, and
 * nothing anywhere reports it. The failure has to reach the caller.
 *
 * Three shapes and no others. That is the agreed rule for every Tracive SDK, so widening it here
 * would not be a local choice — it would be this SDK answering a withdrawn grant differently from
 * the others on the same account.
 */
private val TraciveException.isTransient: Boolean
  get() =
    when (this) {
      // Never reached a server: DNS, TLS, connect, read timeout.
      is TraciveException.Network -> true
      // 429. The server is deferring the answer, not refusing to give one.
      is TraciveException.RateLimited -> true
      // 5xx. `fromApiError` only builds this for 500..599, but the class is public and a caller
      // could construct one, so the status is checked rather than assumed.
      is TraciveException.Server -> (httpStatus ?: 500) >= 500
      else -> false
    }
