package id.tracive.sdk.client

import id.tracive.sdk.TraciveException
import id.tracive.sdk.model.CachePolicy
import id.tracive.sdk.model.CheckResult
import id.tracive.sdk.model.Detail
import id.tracive.sdk.model.Origin
import id.tracive.sdk.model.RiskTier
import id.tracive.sdk.model.Source
import id.tracive.sdk.model.TraciveEnvironment
import id.tracive.sdk.model.TraciveMode
import id.tracive.sdk.oauth.InMemoryTokenStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the client puts on the wire, and what it does with the reply.
 *
 * Every test here runs against [FakeHttpTransport]. That is a hard requirement rather than a
 * preference: the Tracive Open API does not exist yet, so there is no host to talk to, and the SDK
 * has to be provably correct before there is one.
 */
class TraciveClientTest {

  @Test
  fun aCheckSendsTheFoldedQueryAndBillsTheUser() = runSdkTest {
    val transport = FakeHttpTransport.always(jsonResponse(checkResultJson()))
    val client = testClient(transport)

    client.checkScammer(testQuery())

    val request = transport.requests.single()
    assertEquals(HttpMethod.POST, request.method)
    assertEquals("$TEST_BASE_URL/scammer-checks", request.url)
    assertEquals("Bearer access-1", request.header("Authorization"))
    assertEquals("application/json", request.contentType)

    val body = request.body ?: ""
    // `query`, not `input`: the SDK folded the key itself, and that same folded string is what the
    // cache hashes — sending anything else would let the two disagree about what was asked.
    assertTrue(body.contains("\"query\":\"PHONE_+6281234567891\""), body)
    assertTrue(body.contains("\"creditSource\":\"USER\""), body)
    assertTrue(body.contains("\"detail\":\"minimal\""), body)
  }

  /** The server ignores a locale for a minimal check, so sending one is noise on every request. */
  @Test
  fun localeIsSentOnlyWithFullDetail() = runSdkTest {
    val transport = FakeHttpTransport.always(jsonResponse(checkResultJson()))
    val client = testClient(transport)

    client.checkScammer(testQuery(), detail = Detail.MINIMAL, locale = "id")
    assertFalse(transport.requests[0].body!!.contains("locale"), transport.requests[0].body!!)

    client.checkScammer(testQuery(), detail = Detail.FULL, locale = "id")
    assertTrue(transport.requests[1].body!!.contains("\"locale\":\"id\""))
    assertTrue(transport.requests[1].body!!.contains("\"detail\":\"full\""))
  }

  /**
   * Every check carries a key, because the SDK re-sends the identical POST after a 401 refresh and
   * the server has to be able to collapse the two. A caller's own key is used verbatim; otherwise
   * one is generated per call, marked so it can be told apart in a server-side log.
   */
  @Test
  fun everyCheckCarriesAnIdempotencyKey() = runSdkTest {
    val transport = FakeHttpTransport.always(jsonResponse(checkResultJson()))
    val client = testClient(transport)

    client.checkScammer(testQuery())
    client.checkScammer(testQuery())
    client.checkScammer(testQuery(), idempotencyKey = "idem-42")
    // A blank key is not a key. Sending it as one would make every unkeyed call collide.
    client.checkScammer(testQuery(), idempotencyKey = "  ")

    val keys = transport.requests.map { it.header("Idempotency-Key") }
    assertTrue(keys.all { it != null && it.isNotBlank() }, "a check went out with no key: $keys")
    assertEquals("idem-42", keys[2], "a caller's own key must be sent exactly as given")

    val generated = listOf(keys[0]!!, keys[1]!!, keys[3]!!)
    assertTrue(
      generated.all { it.startsWith(TraciveClient.GENERATED_IDEMPOTENCY_KEY_PREFIX) },
      "a generated key must be recognisable as one: $generated",
    )
    assertEquals(
      generated.size,
      generated.toSet().size,
      "two checks shared a generated key, so the server would collapse them into one charge",
    )
  }

  /** A key the SDK invented is not permission to retry. Only the caller's own key is. */
  @Test
  fun aGeneratedKeyDoesNotBuyARetry() = runSdkTest {
    val transport = FakeHttpTransport.always(HttpResponse(503, errorJson("upstream_down")))
    val client = testClient(transport)

    assertFailsWith<TraciveException.Server> { client.checkScammer(testQuery()) }

    assertEquals(1, transport.requests.size, "a generated key was treated as a retry budget")
  }

  /**
   * The SDK holds no API key in any mode, and this is the assertion that keeps it that way: it
   * looks at the bytes that actually left, not at the type signatures.
   */
  @Test
  fun noRequestEverCarriesAnApiKey() = runSdkTest {
    val transport = FakeHttpTransport { request ->
      when {
        request.url.endsWith("/credits") -> jsonResponse("{\"subscriptionCredits\":7}")
        request.url.endsWith("/profile") -> jsonResponse("{\"sub\":\"usr_1\"}")
        else -> jsonResponse(checkResultJson())
      }
    }
    val client = testClient(transport)

    client.checkScammer(testQuery(), idempotencyKey = "idem-1")
    client.credits()
    client.profile()

    for (request in transport.requests) {
      for (name in request.headers.keys) {
        val lowered = name.lowercase()
        assertFalse(lowered.contains("api-key"), "an API key header reached the wire: $name")
        assertFalse(lowered.contains("apikey"), "an API key header reached the wire: $name")
        assertFalse(lowered == "x-api-key", "an API key header reached the wire: $name")
      }
      assertFalse(request.url.contains("api_key"), request.url)
      assertFalse(request.body?.contains("api_key") == true, "an API key reached a request body")
    }
  }

  @Test
  fun aServerResultIsStampedWithItsOriginAndFetchTime() = runSdkTest {
    val clock = FixedClock()
    val transport = FakeHttpTransport.always(jsonResponse(checkResultJson()))
    val client = testClient(transport, clock = clock)

    val result = client.checkScammer(testQuery())

    assertEquals(Origin.SERVER, result.origin)
    assertEquals(clock.now(), result.fetchedAt)
    assertEquals(RiskTier.HIGH, result.riskTier)
    assertEquals(12, result.reportTotal)
    assertEquals(4, result.relatedScammerCount)
    assertEquals(mapOf("IDR" to 25_000_000.0), result.fraudAmount)
    assertTrue(result.disclaimer!!.isNotBlank(), "the disclaimer must survive decoding")
  }

  /** Forward compatibility: a phase 2 field must not break an app already in the field. */
  @Test
  fun unknownResponseFieldsAreIgnored() = runSdkTest {
    val body =
      "{\"found\":true,\"riskTier\":\"CAUTION\",\"futureVersionSignal\":{\"nested\":[1,2]}," +
        "\"reportTotal\":2,\"unknownScalar\":\"whatever\"}"
    val client = testClient(FakeHttpTransport.always(jsonResponse(body)))

    val result = client.checkScammer(testQuery())

    assertEquals(RiskTier.CAUTION, result.riskTier)
    assertEquals(2, result.reportTotal)
  }

  @Test
  fun aBodyTheSdkCannotReadBecomesATypedErrorRatherThanACrash() = runSdkTest {
    val client = testClient(FakeHttpTransport.always(jsonResponse("{\"riskTier\":\"BANNED\"}")))

    val error = assertFailsWith<TraciveException> { client.checkScammer(testQuery()) }

    assertTrue(error is TraciveException.Unknown)
    // A tier this release has never heard of must not be coerced to NONE — "no evidence found" is
    // the most dangerous wrong answer this SDK can give.
    assertFalse(error.message!!.contains("BANNED"), "a response body reached an error message")
  }

  @Test
  fun creditsAndProfileDecode() = runSdkTest {
    val transport =
      FakeHttpTransport.sequence(
        jsonResponse("{\"subscriptionCredits\":42,\"tier\":\"pro\"}"),
        jsonResponse("{\"sub\":\"usr_9\",\"country\":\"IDN\"}"),
      )
    val client = testClient(transport)

    assertEquals(42, client.credits().subscriptionCredits)
    assertEquals("usr_9", client.profile().sub)

    assertEquals(HttpMethod.GET, transport.requests[0].method)
    assertEquals("$TEST_BASE_URL/credits", transport.requests[0].url)
    assertEquals("$TEST_BASE_URL/profile", transport.requests[1].url)
  }

  // ------------------------------------------------------------------ cache ----

  @Test
  fun cacheFirstServesALiveEntryWithNoNetworkAndNoCharge() = runSdkTest {
    val cache = FakeCheckCache()
    val query = testQuery()
    val transport = FakeHttpTransport.always(jsonResponse(checkResultJson()))
    val client = testClient(transport, cache = cache)

    // Seed as a previous check would have.
    val stored =
      client.checkScammer(query, source = Source.SERVER).toCacheable().copy(origin = Origin.CACHE)
    cache.seed(query, stored)
    val networkCallsSoFar = transport.requests.size

    val hit = client.checkScammer(query, source = Source.CACHE_FIRST)

    assertEquals(Origin.CACHE, hit.origin)
    assertNull(hit.billing, "a cache hit costs nothing, so it carries no billing")
    assertEquals(networkCallsSoFar, transport.requests.size, "a cache hit did network I/O")
  }

  /**
   * A miss on a cache-only read has to be loud. An empty [id.tracive.sdk.model.CheckResult] reads
   * as "this identifier is clean", which is the worst answer this SDK could invent.
   */
  @Test
  fun cacheOnlyRaisesCacheMissRatherThanAnEmptyResult() = runSdkTest {
    val transport = FakeHttpTransport.always(jsonResponse(checkResultJson()))
    val client = testClient(transport, cache = FakeCheckCache())

    assertFailsWith<TraciveException.CacheMiss> {
      client.checkScammer(testQuery(), source = Source.CACHE)
    }
    assertTrue(transport.requests.isEmpty(), "Source.CACHE must never touch the network")
  }

  /** The cache holds only `detail`-invariant fields, so it cannot answer a full-detail request. */
  @Test
  fun fullDetailNeverReadsTheCache() = runSdkTest {
    val cache = FakeCheckCache()
    val transport = FakeHttpTransport.always(jsonResponse(checkResultJson()))
    val client = testClient(transport, cache = cache)

    client.checkScammer(testQuery(), source = Source.CACHE_FIRST, detail = Detail.FULL)
    assertEquals(0, cache.reads, "a full-detail check read the cache")

    assertFailsWith<TraciveException.CacheMiss> {
      client.checkScammer(testQuery(), source = Source.CACHE, detail = Detail.FULL)
    }
  }

  /**
   * A full-detail answer must not be WRITTEN either, not merely skipped on read.
   *
   * [CheckResult.toCacheable] drops `topChronologies`, `comments`, `billing` and `checkId` — but it
   * keeps `tags` and `disclaimer`, and both of those vary with `locale`. The cache key is
   * `SHA-256(searchQueryIndex)` with no locale in it, and only a FULL request sends a locale at
   * all. So storing a full-detail answer files locale-specific text under a locale-less key, and
   * the next MINIMAL caller — which sends no locale — reads it back as though it were the default.
   * `disclaimer` is the one string this SDK asks every integrator to render.
   *
   * Gating only the read cannot prevent this: the row is poisoned on the way IN.
   */
  @Test
  fun fullDetailNeverWritesTheCache() = runSdkTest {
    val cache = FakeCheckCache()
    val transport = FakeHttpTransport.always(jsonResponse(checkResultJson()))
    val client = testClient(transport, cache = cache)

    client.checkScammer(testQuery(), source = Source.SERVER, detail = Detail.FULL, locale = "id")

    assertEquals(0, cache.writes, "a full-detail answer was stored under a locale-less key")
  }

  @Test
  fun serverFirstFallsBackToTheCacheWhenTheServerFails() = runSdkTest {
    val cache = FakeCheckCache()
    val query = testQuery()
    val transport = FakeHttpTransport.always(HttpResponse(status = 503, body = ""))
    val client = testClient(transport, cache = cache)

    cache.seed(
      query,
      CheckResult(
        found = true,
        riskTier = RiskTier.ELEVATED,
        origin = Origin.CACHE,
        fetchedAt = FixedClock.DEFAULT,
      ),
    )

    val result = client.checkScammer(query, source = Source.SERVER_FIRST)

    assertEquals(Origin.CACHE, result.origin)
    assertEquals(RiskTier.ELEVATED, result.riskTier)
  }

  /** 429 and a dead socket are the other two shapes a cached answer may stand in for. */
  @Test
  fun serverFirstAlsoFallsBackOnRateLimitingAndOnADeadSocket() = runSdkTest {
    for (transport in
      listOf(
        FakeHttpTransport.always(HttpResponse(429, errorJson("rate_limited"))),
        FakeHttpTransport { throw IllegalStateException("connection reset") },
      )) {
      val cache = FakeCheckCache()
      val query = testQuery()
      val client = testClient(transport, cache = cache)
      cache.seed(query, cachedVerdict())

      val result = client.checkScammer(query, source = Source.SERVER_FIRST)

      assertEquals(Origin.CACHE, result.origin)
    }
  }

  /**
   * A refusal aimed at the caller is never masked by a cached verdict.
   *
   * This is the whole reason the fallback is filtered. A 402, a 403 or a revoked grant is not "the
   * server is unreachable" — it is the server saying something about this account, and it is the
   * one thing the integrator has to be told. Answering from the cache instead makes a withdrawn
   * partner's app carry on serving fraud verdicts for up to thirty days, with nothing in either
   * side's logs to show it, because from the app's point of view every call succeeded.
   *
   * A cache entry is seeded in every case, so a test that passes because the cache was empty cannot
   * exist here.
   */
  @Test
  fun serverFirstNeverMasksARefusalAimedAtTheCallerWithACachedVerdict() = runSdkTest {
    val refusals =
      listOf(
        HttpResponse(403, errorJson("missing_scope")),
        HttpResponse(403, errorJson("partner_suspended")),
        HttpResponse(402, errorJson("insufficient_user_credits")),
        HttpResponse(401, errorJson("grant_revoked")),
      )

    for (refusal in refusals) {
      val cache = FakeCheckCache()
      val query = testQuery()
      val client = testClient(FakeHttpTransport.always(refusal), cache = cache)
      cache.seed(query, cachedVerdict())

      val error =
        assertFailsWith<TraciveException>("${refusal.status} was served from the cache instead") {
          client.checkScammer(query, source = Source.SERVER_FIRST)
        }

      assertEquals(refusal.status, error.httpStatus, "the original refusal must be what surfaces")
    }
  }

  @Test
  fun serverFirstRethrowsWhenThereIsNothingCachedEither() = runSdkTest {
    val client =
      testClient(
        FakeHttpTransport.always(HttpResponse(status = 503, body = errorJson("boom"))),
        cache = FakeCheckCache(),
      )

    val error =
      assertFailsWith<TraciveException> {
        client.checkScammer(testQuery(), source = Source.SERVER_FIRST)
      }
    assertTrue(error is TraciveException.Server, "the original failure must survive the fallback")
  }

  @Test
  fun aServerResultIsWrittenToTheCache() = runSdkTest {
    val cache = FakeCheckCache()
    val client =
      testClient(FakeHttpTransport.always(jsonResponse(checkResultJson())), cache = cache)

    client.checkScammer(testQuery(), source = Source.SERVER)

    assertEquals(1, cache.writes)
  }

  @Test
  fun disablingTheCachePolicyTurnsTheCacheOff() = runSdkTest {
    val cache = FakeCheckCache()
    val client =
      TraciveClient(
        mode = testOAuthMode(),
        environment = TraciveEnvironment.Custom(TEST_BASE_URL),
        cachePolicy = CachePolicy(enabled = false),
        transport = FakeHttpTransport.always(jsonResponse(checkResultJson())),
        cache = cache,
        tokenStore = storedTokens(),
        retryPolicy = NO_WAIT_RETRIES,
      )

    client.checkScammer(testQuery(), source = Source.SERVER)

    assertEquals(0, cache.writes, "a disabled policy must not write through to the cache")
  }

  // ------------------------------------------------------------- proxy mode ----

  /**
   * Proxy mode is phase 2. The type exists so adding it later breaks nobody; every call through it
   * must say so rather than half-working.
   */
  @Test
  fun everyCallInProxyModeThrowsNotImplemented() = runSdkTest {
    val client =
      testClient(
        FakeHttpTransport.always(jsonResponse(checkResultJson())),
        mode = TraciveMode.Proxy("https://proxy.partner.test", mapOf("Authorization" to "Bearer x")),
      )

    assertFailsWith<TraciveException.NotImplemented> { client.checkScammer(testQuery()) }
    assertFailsWith<TraciveException.NotImplemented> { client.credits() }
    assertFailsWith<TraciveException.NotImplemented> { client.profile() }
    assertFailsWith<TraciveException.NotImplemented> { client.authorize() }
    assertFailsWith<TraciveException.NotImplemented> {
      client.handleCallback("https://partner.test/tracive/callback?code=c&state=s")
    }
    assertNull(client.oauth, "a proxied client has no OAuth grant of its own")
    assertFalse(
      client.isAuthorized(),
      "asking a proxied client whether it holds a grant has an answer — no — rather than being a " +
        "misuse worth throwing over",
    )
  }

  @Test
  fun theClientIsTheFrontDoorToTheOAuthFlow() = runSdkTest {
    val transport = FakeHttpTransport.always(jsonResponse(checkResultJson()))

    val authorized = testClient(transport)
    assertTrue(authorized.isAuthorized(), "a stored grant is a grant")

    val notAuthorized = testClient(transport, tokenStore = InMemoryTokenStore())
    assertFalse(notAuthorized.isAuthorized(), "an empty store is not a grant")

    // Delegation is faithful rather than reinterpreted: a URL that is not this client's redirect
    // is "not mine" at both levels, which is a null and not a failure.
    assertNull(authorized.handleCallback("https://elsewhere.test/callback?code=c&state=s"))
    assertEquals(
      authorized.handleCallback("https://elsewhere.test/callback?code=c&state=s"),
      authorized.oauth?.handleCallback("https://elsewhere.test/callback?code=c&state=s"),
      "the front door and the flow answer the same thing, because one calls the other",
    )
  }

  @Test
  fun clearCacheEmptiesTheCacheAndLeavesTheGrantAlone() = runSdkTest {
    val cache = FakeCheckCache()
    val transport = FakeHttpTransport.always(jsonResponse(checkResultJson()))
    val client = testClient(transport, cache = cache)

    client.checkScammer(testQuery(), source = Source.SERVER)
    assertEquals(1, cache.size(), "the check should have written a row to clear")

    client.clearCache()

    assertEquals(0, cache.size())
    assertTrue(
      client.isAuthorized(),
      "this is the difference between clearCache and signOut: the user stays signed in",
    )
  }

  @Test
  fun closingTheClientDoesNotCloseATransportItDoesNotOwn() = runSdkTest {
    val transport = FakeHttpTransport.always(jsonResponse(checkResultJson()))
    val client = testClient(transport)

    client.close()

    assertFalse(transport.closed, "the SDK closed an HTTP client the host owns")
  }

  // ---------------------------------------------------------- the install link ----

  /**
   * The client passes the caller's own `installUrl` straight through and never invents one, so the
   * blank rule must not touch anyone who left the parameter alone. Every other test in this file
   * omits it; this one says so on purpose, because a regression here would break construction for
   * every integration at once.
   */
  @Test
  fun aClientThatNamesNoInstallUrlStillBuildsAndGetsTheDefault() {
    val client = testClient(FakeHttpTransport.always(jsonResponse(checkResultJson())))

    assertEquals(
      "https://tracive.chottu.link/install?ref=cli_test&utm_source=tracive-sdk-kotlin",
      assertNotNull(client.oauth).installUrl,
    )
  }

  /**
   * A blank one is refused as the client is built, not later when something first touches `oauth`.
   * The flow itself is created lazily, so without this guard the mistake would surface at whatever
   * moment the host happened to reach for the OAuth object — much further from the line that made
   * it, and skippable in any run that never authorizes.
   */
  @Test
  fun aBlankInstallUrlIsRefusedWhenTheClientIsBuilt() {
    val failure =
      assertFailsWith<IllegalArgumentException> {
        TraciveClient(
          mode = testOAuthMode(),
          environment = TraciveEnvironment.Custom(TEST_BASE_URL),
          transport = FakeHttpTransport.always(jsonResponse(checkResultJson())),
          installUrl = "   ",
        )
      }
    assertTrue(failure.message.orEmpty().contains("blank_install_url"), failure.message)
  }

  /** A verdict sitting in the cache, as a previous check would have left it. */
  private fun cachedVerdict(): CheckResult =
    CheckResult(
      found = true,
      riskTier = RiskTier.ELEVATED,
      origin = Origin.CACHE,
      fetchedAt = FixedClock.DEFAULT,
    )
}
