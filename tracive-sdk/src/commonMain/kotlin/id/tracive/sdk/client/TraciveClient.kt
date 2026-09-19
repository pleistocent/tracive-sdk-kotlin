package id.tracive.sdk.client

import id.tracive.sdk.TraciveException
import id.tracive.sdk.cache.CheckCache
import id.tracive.sdk.cache.DisabledCheckCache
import id.tracive.sdk.cache.cacheKeyFor
import id.tracive.sdk.model.AuthResult
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
import id.tracive.sdk.platform.Base64Url
import id.tracive.sdk.platform.CryptoProvider
import id.tracive.sdk.query.TraciveQuery
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * // delivers the redirect — see TraciveOAuth, which both of these delegate to.
 * client.authorize()
 * when (val auth = client.handleCallback(redirectUrl)) { /* … */ }
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
 * Only [TraciveMode.OAuth] is implemented. Every call in [TraciveMode.Proxy] throws
 * [TraciveException.NotImplemented]; the mode is in the surface already, so adding it in phase 2
 * breaks no one.
 *
 * @param transport the HTTP seam. Injecting one is how the whole SDK is tested without a server, so
 *   no test depends on either environment being up. The default builds a [KtorTransport] lazily, on
 *   first use.
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

  /** Checks that have gone out and not yet answered, keyed as [latchedCheck] describes. */
  private val inFlightChecks = HashMap<String, InFlightCheck>()

  /**
   * Guards [inFlightChecks]. Held only across map reads and writes, never across a request. A lock
   * held for a whole round trip would serialise checks for *different* identifiers, which is the
   * opposite of the point.
   */
  private val inFlightMutex = Mutex()

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
   * The request nonetheless always carries **some** `Idempotency-Key`: when the caller supplies
   * none, the SDK generates a per-call one prefixed [GENERATED_IDEMPOTENCY_KEY_PREFIX], so that its
   * own re-send after a 401 token refresh can be collapsed server-side. That does not hand the
   * caller a retry budget — only their own key does — and it is not a substitute for a key derived
   * from the transaction if what is wanted is retries or deduplication across processes.
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
   * ### Concurrent identical checks
   *
   * Overlapping calls for the same identifier, at the same detail level and locale and under the
   * same [idempotencyKey] — including none at all — share **one** request and one charge. A
   * double-tapped button and a list screen rendering the same identifier in two cells are the
   * everyday cases, and before the latch existed each of them was billed twice — the cache write is
   * the only serialisation point, and it happens after both requests have already gone out.
   *
   * Two overlapping checks carrying **different** caller keys are two deliberate transactions and
   * are sent separately, and a call that supplies a key never joins a flight started by one that
   * did not: the key is the caller's own statement about what is being billed, and only their key
   * grants the retry budget above. See [latchedCheck] for what else the sharing deliberately does
   * not do.
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
        latchedCheck(query, detail, locale, idempotencyKey)
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
      latchedCheck(query, detail, locale, idempotencyKey)
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
   * Asks the user for consent, by delegating to [oauth].
   *
   * The whole flow lives on [TraciveOAuth], and everything it documents applies unchanged — this is
   * the front door, not a second implementation. Reach for [oauth] directly when you need the parts
   * that are not mirrored here: [TraciveOAuth.cancelPendingAuthorization],
   * [TraciveOAuth.pendingAuthorizationId], [TraciveOAuth.grantedScopes],
   * [TraciveOAuth.adoptTokens].
   *
   * The Swift, Flutter and React Native SDKs all put consent on the client, and an integrator who
   * reads only this class should not have to already know that another one exists.
   *
   * @throws TraciveException.NotImplemented in [TraciveMode.Proxy], which has no grant of its own.
   */
  suspend fun authorize(extraAuthorizeParams: Map<String, String> = emptyMap()): AuthResult? {
    val flow = oauth ?: throw notImplementedInProxyMode()
    return flow.authorize(extraAuthorizeParams)
  }

  /**
   * Hands a redirect back to the flow, by delegating to [oauth].
   *
   * See [TraciveOAuth.handleCallback] for what a null return means, and for why a redirect must be
   * delivered once rather than replayed on every recreation.
   *
   * @throws TraciveException.NotImplemented in [TraciveMode.Proxy], which has no grant of its own.
   */
  suspend fun handleCallback(url: String): AuthResult? {
    val flow = oauth ?: throw notImplementedInProxyMode()
    return flow.handleCallback(url)
  }

  /**
   * Whether a grant is stored, by delegating to [oauth].
   *
   * False in [TraciveMode.Proxy] rather than throwing: a proxied client genuinely holds no grant,
   * which is an answer to the question rather than a misuse of it.
   */
  suspend fun isAuthorized(): Boolean = oauth?.isAuthorized() ?: false

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

  /**
   * Empties the result cache, and nothing else.
   *
   * The same call as `client.cache.clear()`, named where a reader looks for it. [signOut] already
   * does this; reach for it on its own when a user stays signed in and you want the history gone —
   * a "clear data" button, or a privacy setting.
   */
  suspend fun clearCache() {
    cache.clear()
  }

  /** Releases the HTTP client, when this instance created it. Idempotent. */
  fun close() {
    // Deliberately does not touch `oauth`: building the flow just to close it would construct the
    // very HTTP client this method exists to avoid leaving behind.
    if (ownsTransport && transportProvider.isInitialized()) transportProvider.value.close()
  }

  // -------------------------------------------------------------- internals ----

  /**
   * Runs [fetchCheck] **single-flight**, because the unit here is currency.
   *
   * Two overlapping checks for the same identifier both read the cache, both missed, both POSTed
   * and both were charged: the cache write is the only serialisation point, and by the time it runs
   * both requests have already gone out. A double-tapped button, or a list screen rendering the
   * same identifier in two cells, doubled the bill. [TraciveOAuth.refreshedToken] has had the
   * correct shape all along; this is the same latch in the one place where a mistake costs money
   * rather than a round trip.
   *
   * The key is the record key **plus the detail level and the locale**, not the record key alone. A
   * [Detail.MINIMAL] answer must never be handed back to a caller who asked for [Detail.FULL] — it
   * carries no `topChronologies` and no `comments`, and those read as "there is nothing on file"
   * rather than as "this field was not fetched". The locale is in the key for the same reason one
   * step further in: `tags` and `disclaimer` come back in the language that was asked for.
   *
   * And the caller's own idempotency key, whenever there is one. That key is an explicit statement
   * about a billable transaction, and leaving it out of the latch overrode it twice over. A caller
   * who supplied a key could join a flight started by one who did not, and lose the retry budget
   * that only a caller-supplied key grants — silently, since the joined call looks like an ordinary
   * success right up to the 5xx it is no longer allowed to repeat. And two overlapping checks
   * carrying *different* keys are the caller saying these are two billable transactions; collapsing
   * them into one sent the first caller's key for both. It is the same rule
   * [generatedIdempotencyKey] states for the neighbouring case — a key must never be derived from
   * the query, because that collapses two deliberate re-checks of one identifier into a single
   * answer — reaching the same place through another door.
   *
   * A caller who supplies no key changes nothing: the latch key is exactly what it was, so the
   * everyday cases this exists for still share one flight. A double-tapped button and a list
   * rendering the same identifier twice both go through one code path with one set of options, so
   * neither carries a key the other does not.
   *
   * The registration and the lookup happen under [inFlightMutex] with no suspension between them,
   * which is the whole mechanism — two callers that both observed an empty map would both start a
   * flight, and the latch would be decorative.
   *
   * The flight starts **undispatched**, which is to say it runs on the caller's own thread until
   * the request suspends, exactly as an unlatched check did. Dispatching it instead would hand the
   * request to the scheduler and let the caller carry on, so on a single-threaded dispatcher a
   * check would leave only once whatever else is queued has run. The cost is that [inFlightMutex]
   * is held for the first few lines of a new flight rather than for map access alone — up to the
   * point where the request suspends, never across the round trip.
   *
   * ### What sharing must not take away
   *
   * A shared request has one lifetime and several callers, so it cannot simply belong to whoever
   * started it. React Native states the rule as a carve-out — a call carrying an `AbortSignal`
   * neither joins nor starts a shared flight — because there a signal is an explicit, per-call
   * object. Kotlin has no such parameter: cancellation is ambient, every `suspend` call is
   * cancellable, and an opt-out on that basis would exempt every caller and latch nothing. The same
   * rule therefore lands here as a lifetime rather than as an exemption, and it has two halves:
   * - The flight is nobody's child. It runs under a [Job] of its own, not under the job of whoever
   *   started it, so a caller that gives up — a screen closing, a `withTimeout` firing — cannot
   *   cancel a check another caller is still waiting for.
   * - The flight lives exactly as long as someone is waiting for it. [InFlightCheck.waiters] counts
   *   them, and the last one to leave cancels the request on the way out. Without that half,
   *   sharing would quietly remove a caller's ability to stop a check at all: everyone could
   *   abandon a request and it would still run to completion and still be billed, which is
   *   precisely the credit this exists to save.
   */
  private suspend fun latchedCheck(
    query: TraciveQuery,
    detail: Detail,
    locale: String?,
    idempotencyKey: String?,
  ): CheckResult {
    // Blank is not a key, and [fetchCheck] already reads it that way. The two have to agree: a
    // latch that treated a blank string as a distinguishing value would split a flight for a caller
    // who gains nothing from the split, because a blank key grants no retry budget either.
    val callerKey = idempotencyKey?.takeIf { it.isNotBlank() }
    // The hashed record key, not the identifier: a phone number or a wallet address does not belong
    // in a long-lived map key, for the same reason it is not a column in the cache database.
    val sharedKey = "${cacheKeyFor(query)}|${detail.name}|${locale.orEmpty()}"
    val latchKey = if (callerKey == null) sharedKey else "$sharedKey|$callerKey"
    val callerContext = currentCoroutineContext()

    val flight = inFlightMutex.withLock {
      val joined =
        inFlightChecks[latchKey]
          ?: InFlightCheck(
              CoroutineScope(callerContext + Job()).async(start = CoroutineStart.UNDISPATCHED) {
                fetchCheck(query, detail, locale, callerKey)
              }
            )
            .also { inFlightChecks[latchKey] = it }
      joined.waiters += 1
      joined
    }

    try {
      return flight.deferred.await()
    } finally {
      // [NonCancellable] because this runs on the way out of a cancellation as well as a normal
      // return, and a plain `withLock` in a cancelled coroutine throws before it takes the lock —
      // leaving the flight registered for ever and every later check for that identifier waiting on
      // a request nobody is running.
      withContext(NonCancellable) {
        inFlightMutex.withLock {
          flight.waiters -= 1
          val abandoned = flight.waiters == 0
          // Out of the map once it has answered. A check arriving a moment later then starts a
          // fresh request instead of joining a finished flight and taking its result.
          if (abandoned || flight.deferred.isCompleted) {
            if (inFlightChecks[latchKey] === flight) inFlightChecks.remove(latchKey)
          }
          // A no-op on a flight that has already answered. It matters when the last caller gave up
          // first: the request is theirs to stop, exactly as it was before the latch existed.
          if (abandoned) flight.deferred.cancel()
        }
      }
    }
  }

  /**
   * One check on the wire.
   *
   * [callerKey] is the caller's own idempotency key, already reduced to null by [latchedCheck] when
   * they supplied none or supplied a blank one. Both the latch and the retry rule turn on that same
   * reading, so it is made in one place rather than in two that could drift apart.
   */
  private suspend fun fetchCheck(
    query: TraciveQuery,
    detail: Detail,
    locale: String?,
    callerKey: String?,
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
        // Every check carries a key, whether the caller supplied one or not. [send] re-sends this
        // exact POST after a 401 refresh. Without a key the server cannot tell those two attempts
        // apart, so anything billed before or during authorisation is charged twice and the caller
        // has no way to notice. What used to make that safe was "a 401 is rejected before billing"
        // — an assumption about a server that does not exist yet. A key means nothing is assumed.
        idempotencyKey = callerKey ?: generatedIdempotencyKey(),
        // A generated key is not a licence to retry. Retrying is still gated on the *caller's* key,
        // because a retry is only safe if the caller can reuse the same key on a later call of
        // their own; a key this SDK invented lives for one call and would collapse nothing.
        retryable = callerKey != null,
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
   * Performs one API call: attaches the bearer token, applies the retry policy, and handles the
   * 401s that are worth handling.
   *
   * The 401 path is defined by what is **terminal**, not by one exact code:
   * - `grant_revoked` → clear the stored tokens and surface it. Never retry, never refresh: the
   *   grant is gone, and presenting it again looks like a replay to the server.
   * - `invalid_token` → surface it. The server is saying the credential is not usable at all, so a
   *   fresh one earns nothing.
   * - **any other 401** → refresh **once**, single-flight, and repeat the request. A second 401
   *   after a fresh token is not a token problem, so it surfaces.
   *
   * That last rule used to require the body to carry exactly `expired_token`. A bare `401
   * Unauthorized` — no body at all, or nothing but a `WWW-Authenticate` header — is what CDNs, API
   * gateways and misconfigured reverse proxies emit, and this SDK is explicitly designed to sit
   * behind a partner gateway. Requiring the code left a client in that state permanently stuck: it
   * never refreshed, so every call failed identically until the app happened to call `authorize()`
   * again, and nothing in the failure said which of the two problems it was.
   *
   * Widening it costs nothing that was not already spent: the refresh is capped at one per request
   * either way, and the replay carries an `Idempotency-Key` — [fetchCheck] attaches one even when
   * the caller supplied none — so a check that was billed after all is collapsed rather than
   * charged twice.
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
        when {
          // Terminal: the grant is gone. Clear it and surface it — never refresh, because
          // presenting a retired token again is what an attacker's replay looks like.
          envelope.code == TraciveException.CODE_GRANT_REVOKED -> {
            flow.clearTokens()
            throw ApiErrors.toException(response)
          }
          // Terminal at one remove: the server is saying this credential is not usable at all, so
          // obtaining a fresh one earns nothing. It is also the answer to an empty bearer, which
          // `TraciveOAuth.accessToken` repairs before the request rather than here.
          envelope.code == TraciveException.CODE_INVALID_TOKEN ->
            throw ApiErrors.toException(response)
          // A second 401 after a fresh token is not a token problem.
          refreshAttempted -> throw ApiErrors.toException(response)
          else -> {
            refreshAttempted = true
            // Single-flight: concurrent 401s produce exactly one refresh. Several would spend the
            // rotating refresh token more than once, which the server reads as theft and answers by
            // revoking the whole grant.
            token = flow.refreshedToken(token)
            continue
          }
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

  /**
   * A key for a check the caller did not key itself. One per call, never reused.
   *
   * Prefixed so that a key this SDK invented is distinguishable from a partner's own in a
   * server-side log, which is what someone reconciling a disputed charge needs. It is deliberately
   * **not** derived from the query: two genuine re-checks of the same identifier are two billable
   * answers, and a derived key would collapse them into one.
   *
   * 16 bytes from the platform CSPRNG, base64url — the same source
   * [id.tracive.sdk.oauth .PkceGenerator] uses. Uniqueness is the only property required here;
   * unguessability is free and costs nothing to keep.
   */
  private fun generatedIdempotencyKey(): String =
    GENERATED_IDEMPOTENCY_KEY_PREFIX + Base64Url.encode(CryptoProvider.randomBytes(16))

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

    /** Header carrying the idempotency key. Every check sends one. */
    const val HEADER_IDEMPOTENCY_KEY: String = "Idempotency-Key"

    /**
     * Marks a key the SDK generated for a caller who supplied none.
     *
     * Stable and public so that a partner reconciling a charge against their own records can tell
     * the two apart, and so the same prefix means the same thing in every Tracive SDK.
     */
    const val GENERATED_IDEMPOTENCY_KEY_PREFIX: String = "sdk-"

    /** Content type of every request body the API takes, apart from the OAuth endpoints. */
    const val JSON_CONTENT_TYPE: String = "application/json"

    /** The single wording for the phase 2 gap, so callers can match on it. */
    const val PROXY_MODE_MESSAGE: String = "Proxy mode arrives in phase 2"
  }
}

/**
 * One shared check, and how many callers still want its answer.
 *
 * [waiters] is only ever read and written under `TraciveClient.inFlightMutex`, which is what makes
 * a plain `Int` enough on every target.
 */
private class InFlightCheck(val deferred: Deferred<CheckResult>) {
  var waiters: Int = 0
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
