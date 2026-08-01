package id.tracive.sdk.client

import id.tracive.sdk.cache.CheckCache
import id.tracive.sdk.model.CheckResult
import id.tracive.sdk.model.ClientType
import id.tracive.sdk.model.TraciveEnvironment
import id.tracive.sdk.model.TraciveMode
import id.tracive.sdk.oauth.InMemoryTokenStore
import id.tracive.sdk.oauth.TokenSet
import id.tracive.sdk.platform.AppPresenceChecker
import id.tracive.sdk.platform.AuthorizeLauncher
import id.tracive.sdk.query.TraciveQuery
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Runs a suspending test body to completion, with no test framework dependency.
 *
 * `kotlinx-coroutines-test` is not among the dependencies this module is guaranteed, and reaching
 * for `runBlocking` is not an option either — it exists only in the platform source sets, not in
 * common code. [Dispatchers.Unconfined] covers the gap: it runs each coroutine eagerly on the
 * calling thread until it genuinely suspends, so a test whose I/O is a fake transport finishes
 * before `launch` returns, and the interleavings a concurrency test wants are deterministic rather
 * than timing-dependent.
 *
 * The one rule that comes with it: **nothing in a test body may suspend on wall-clock time.** Use
 * `RetryPolicy(initialBackoff = Duration.ZERO)`, and gate anything that must suspend on a
 * `CompletableDeferred` the test itself completes. A body that breaks the rule fails here, loudly,
 * rather than hanging.
 */
internal fun runSdkTest(block: suspend CoroutineScope.() -> Unit) {
  var finished = false
  var failure: Throwable? = null
  CoroutineScope(Dispatchers.Unconfined).launch {
    try {
      block()
    } catch (thrown: Throwable) {
      failure = thrown
    } finally {
      finished = true
    }
  }
  if (!finished) {
    throw IllegalStateException(
      "The test coroutine did not finish synchronously. Something in it waited on real time or on " +
        "another thread — see runSdkTest's contract."
    )
  }
  failure?.let { throw it }
}

/**
 * An [HttpTransport] that answers from a script instead of a socket.
 *
 * This is the seam the whole SDK is designed around, and it is not a convenience: the Tracive Open
 * API **does not exist yet**, so a test that reached for a real host would not be slow or flaky, it
 * would be impossible. Every request is recorded so a test can assert what went on the wire —
 * headers included, which is how "no API key is ever sent" is checked rather than assumed.
 */
internal class FakeHttpTransport(private val handler: suspend (HttpRequest) -> HttpResponse) :
  HttpTransport {

  /** Every request, in order. */
  val requests: MutableList<HttpRequest> = mutableListOf()

  /** Whether [close] ran. */
  var closed: Boolean = false
    private set

  override suspend fun execute(request: HttpRequest): HttpResponse {
    requests += request
    return handler(request)
  }

  override fun close() {
    closed = true
  }

  /** Requests whose URL ends with [suffix] — usually a path. */
  fun requestsTo(suffix: String): List<HttpRequest> = requests.filter { it.url.endsWith(suffix) }

  companion object {
    /** Answers every request with the same reply. */
    fun always(response: HttpResponse): FakeHttpTransport = FakeHttpTransport { response }

    /** Answers with each reply in turn, then fails rather than silently repeating the last. */
    fun sequence(vararg responses: HttpResponse): FakeHttpTransport {
      var index = 0
      return FakeHttpTransport {
        val next =
          responses.getOrNull(index)
            ?: throw IllegalStateException(
              "The transport was called ${index + 1} times but only ${responses.size} replies were scripted."
            )
        index += 1
        next
      }
    }
  }
}

/** A [Clock] that does not move, so TTL and token-expiry assertions are exact. */
internal class FixedClock(private var instant: Instant = DEFAULT) : Clock {

  override fun now(): Instant = instant

  /** Moves the clock forward. */
  fun advance(by: Duration) {
    instant += by
  }

  companion object {
    val DEFAULT: Instant = Instant.fromEpochSeconds(1_800_000_000L)
  }
}

/** Reports a fixed answer for "is the Tracive app installed", and counts the asks. */
internal class FakeAppPresenceChecker(private val installed: Boolean) : AppPresenceChecker {

  var checks: Int = 0
    private set

  override suspend fun isTraciveAppInstalled(): Boolean {
    checks += 1
    return installed
  }
}

/** Records the URLs the flow would have opened, instead of opening them. */
internal class RecordingAuthorizeLauncher(private val onAuthorize: ((String) -> Unit)? = null) :
  AuthorizeLauncher {

  val authorizeUrls: MutableList<String> = mutableListOf()
  val installUrls: MutableList<String> = mutableListOf()

  override suspend fun launchAuthorize(url: String) {
    authorizeUrls += url
    onAuthorize?.invoke(url)
  }

  override suspend fun launchInstall(url: String) {
    installUrls += url
  }
}

/** A [CheckCache] backed by a map, so cache routing can be asserted without a database. */
internal class FakeCheckCache(
  private val entries: MutableMap<String, CheckResult> = mutableMapOf()
) : CheckCache {

  var reads: Int = 0
    private set

  var writes: Int = 0
    private set

  override suspend fun get(query: TraciveQuery): CheckResult? {
    reads += 1
    return entries[query.searchQueryIndex]
  }

  override suspend fun put(query: TraciveQuery, result: CheckResult) {
    writes += 1
    entries[query.searchQueryIndex] = result.toCacheable()
  }

  override suspend fun remove(query: TraciveQuery) {
    entries.remove(query.searchQueryIndex)
  }

  override suspend fun clear() {
    entries.clear()
  }

  override suspend fun prune(): Int = 0

  override suspend fun size(): Int = entries.size

  /** Seeds an entry directly, as though a previous check had stored it. */
  fun seed(query: TraciveQuery, result: CheckResult) {
    entries[query.searchQueryIndex] = result
  }
}

/** The OAuth client the tests configure. No secret, no API key — there is nowhere to put one. */
internal fun testOAuthMode(
  clientId: String = "cli_test",
  redirectUri: String = "https://partner.test/tracive/callback",
  scopes: List<String> = listOf("check:read"),
  partnerUserId: String? = "partner-user-1",
  clientType: ClientType = ClientType.PUBLIC,
): TraciveMode.OAuth =
  TraciveMode.OAuth(
    clientId = clientId,
    redirectUri = redirectUri,
    scopes = scopes,
    partnerUserId = partnerUserId,
    clientType = clientType,
  )

/** A grant that is already in place, so a test can go straight to the API calls. */
internal fun storedTokens(
  accessToken: String = "access-1",
  refreshToken: String? = "refresh-1",
): InMemoryTokenStore =
  InMemoryTokenStore(
    TokenSet(
      accessToken = accessToken,
      refreshToken = refreshToken,
      grantedScopes = listOf("check:read"),
    )
  )

/** A client wired entirely to fakes: no sockets, no platform, no wall-clock waits. */
internal fun testClient(
  transport: HttpTransport,
  tokenStore: InMemoryTokenStore = storedTokens(),
  cache: CheckCache? = null,
  mode: TraciveMode = testOAuthMode(),
  clock: Clock = FixedClock(),
  retryPolicy: RetryPolicy = NO_WAIT_RETRIES,
): TraciveClient =
  TraciveClient(
    mode = mode,
    environment = TraciveEnvironment.Custom(TEST_BASE_URL),
    transport = transport,
    clock = clock,
    cache = cache,
    tokenStore = tokenStore,
    retryPolicy = retryPolicy,
    appPresenceChecker = FakeAppPresenceChecker(installed = true),
    authorizeLauncher = RecordingAuthorizeLauncher(),
  )

/** Retries with no backoff at all, so the retry path runs without touching the clock. */
internal val NO_WAIT_RETRIES: RetryPolicy =
  RetryPolicy(maxAttempts = 3, initialBackoff = Duration.ZERO, jitterRatio = 0.0)

/** A local origin. Nothing listens on it: every test answers through [FakeHttpTransport]. */
internal const val TEST_BASE_URL: String = "https://api.test.invalid/v1"

/** The query most tests use. Folds to `PHONE_+6281234567891`. */
internal fun testQuery(): TraciveQuery = TraciveQuery.phone("+62 812 3456 7891")

/** A minimal successful check body. */
internal fun checkResultJson(
  checkId: String = "chk_1",
  riskTier: String = "HIGH",
  cacheTtlSeconds: Long? = null,
): String = buildString {
  append("{\"checkId\":\"").append(checkId).append("\",")
  append("\"found\":true,")
  append("\"riskTier\":\"").append(riskTier).append("\",")
  append("\"reportTotal\":12,\"credibleReports\":9,\"ambiguousReports\":3,")
  append("\"fraudAmount\":{\"IDR\":25000000.0},")
  append("\"tags\":[\"investment\"],\"relatedScammerCount\":4,")
  append("\"disclaimer\":\"User-generated reports, not adjudicated findings.\"")
  if (cacheTtlSeconds != null) append(",\"cacheTtlSeconds\":").append(cacheTtlSeconds)
  append("}")
}

/** The Tracive API's documented error envelope. */
internal fun errorJson(code: String, message: String = "nope", checkId: String? = null): String =
  buildString {
    append("{\"error\":{\"code\":\"").append(code).append("\",\"message\":\"").append(message)
    append("\"")
    if (checkId != null) append(",\"checkId\":\"").append(checkId).append("\"")
    append("}}")
  }

/** An RFC 6749 token-endpoint reply. */
internal fun tokenJson(
  accessToken: String = "access-2",
  refreshToken: String? = "refresh-2",
  expiresIn: Long? = 3600,
  scope: String? = "check:read",
): String = buildString {
  append("{\"access_token\":\"").append(accessToken).append("\",\"token_type\":\"Bearer\"")
  if (expiresIn != null) append(",\"expires_in\":").append(expiresIn)
  if (refreshToken != null) append(",\"refresh_token\":\"").append(refreshToken).append("\"")
  if (scope != null) append(",\"scope\":\"").append(scope).append("\"")
  append("}")
}

/** A 200 with a JSON body. */
internal fun jsonResponse(body: String, status: Int = 200): HttpResponse =
  HttpResponse(status = status, body = body, headers = mapOf("content-type" to "application/json"))
