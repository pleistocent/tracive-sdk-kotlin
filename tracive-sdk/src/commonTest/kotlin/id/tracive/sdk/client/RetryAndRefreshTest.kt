package id.tracive.sdk.client

import id.tracive.sdk.TraciveException
import id.tracive.sdk.oauth.InMemoryTokenStore
import id.tracive.sdk.oauth.TraciveOAuth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The two policies that decide whether a request is sent twice, and what that costs.
 *
 * Both are money questions rather than plumbing:
 * - A check that is repeated without an `Idempotency-Key` can be **charged twice**, because a 5xx
 *   or a timeout never says whether the server processed it before failing.
 * - A refresh that runs twice with the same rotating refresh token looks like **theft** to the
 *   server, which answers by revoking the whole grant — so the SDK refreshes once and repeats the
 *   original request once, and never turns a 401 into a loop.
 */
class RetryAndRefreshTest {

  @Test
  fun anExpiredTokenIsRefreshedOnceAndTheCheckIsRepeated() = runSdkTest {
    val store = storedTokens(accessToken = "access-1", refreshToken = "refresh-1")
    var checks = 0
    val transport = FakeHttpTransport { request ->
      when {
        request.url.endsWith("/oauth/token") -> jsonResponse(tokenJson())
        else -> {
          checks += 1
          if (checks == 1) HttpResponse(401, errorJson("expired_token"))
          else jsonResponse(checkResultJson())
        }
      }
    }
    val client = testClient(transport, tokenStore = store)

    val result = client.checkScammer(testQuery())

    assertEquals(3, transport.requests.size, "expected check, refresh, check")
    assertEquals("Bearer access-1", transport.requests[0].header("Authorization"))
    assertEquals("Bearer access-2", transport.requests[2].header("Authorization"))
    assertTrue(result.found)

    // The rotated refresh token replaced the old one, atomically. Sending the superseded one again
    // is what trips the server's reuse detection.
    val stored = assertNotNull(store.load())
    assertEquals("access-2", stored.accessToken)
    assertEquals("refresh-2", stored.refreshToken)
  }

  /**
   * The re-send after a refresh is the whole reason an unkeyed check still carries a key.
   *
   * The SDK repeats the identical POST once the token is fresh. Without a key on it, those two
   * attempts are indistinguishable to the server, so anything billed before or during authorisation
   * is charged twice and the caller has no way to detect it. The argument that used to make this
   * safe — "a 401 is rejected before billing" — is an assumption about a server that does not exist
   * yet, and it is the one assumption this SDK otherwise refuses to make. Sending the same key on
   * both attempts means it does not have to be made.
   */
  @Test
  fun theReplayAfterARefreshRepeatsTheSameIdempotencyKey() = runSdkTest {
    var checks = 0
    val transport = FakeHttpTransport { request ->
      if (request.url.endsWith(TraciveOAuth.PATH_TOKEN)) {
        jsonResponse(tokenJson())
      } else {
        checks += 1
        if (checks == 1) HttpResponse(401, errorJson("expired_token"))
        else jsonResponse(checkResultJson())
      }
    }
    val client = testClient(transport)

    // No key from the caller: the interesting case, and the common one.
    client.checkScammer(testQuery())

    val sent = transport.requestsTo(TraciveClient.PATH_SCAMMER_CHECKS)
    assertEquals(2, sent.size, "expected the check, then the replay after the refresh")
    val key = assertNotNull(sent[0].header(TraciveClient.HEADER_IDEMPOTENCY_KEY))
    assertTrue(
      key.startsWith(TraciveClient.GENERATED_IDEMPOTENCY_KEY_PREFIX),
      "the SDK sent no key of its own on an unkeyed check: $key",
    )
    assertEquals(
      key,
      sent[1].header(TraciveClient.HEADER_IDEMPOTENCY_KEY),
      "the replay carried a different key, so the server cannot collapse it onto the first charge",
    )
  }

  @Test
  fun theRefreshBodyIsFormEncodedAndCarriesNoSecret() = runSdkTest {
    val transport = FakeHttpTransport { request ->
      if (request.url.endsWith("/oauth/token")) jsonResponse(tokenJson())
      else HttpResponse(401, errorJson("expired_token"))
    }
    val client = testClient(transport)

    assertFailsWith<TraciveException> { client.checkScammer(testQuery()) }

    val refresh = transport.requestsTo("/oauth/token").single()
    assertEquals("application/x-www-form-urlencoded", refresh.contentType)
    val body = refresh.body ?: ""
    assertTrue(body.contains("grant_type=refresh_token"), body)
    assertTrue(body.contains("refresh_token=refresh-1"), body)
    assertTrue(body.contains("client_id=cli_test"), body)
    // A public client has no secret to send, and the SDK has nowhere to hold one.
    assertTrue(!body.contains("client_secret"), body)
    assertTrue(!body.contains("api_key"), body)
  }

  /** One refresh, one repeat. A second 401 is not a token problem, so it surfaces. */
  @Test
  fun aSecondExpiredTokenIsNotRefreshedAgain() = runSdkTest {
    val transport = FakeHttpTransport { request ->
      if (request.url.endsWith("/oauth/token")) jsonResponse(tokenJson())
      else HttpResponse(401, errorJson("expired_token"))
    }
    val client = testClient(transport)

    assertFailsWith<TraciveException.ExpiredToken> { client.checkScammer(testQuery()) }

    assertEquals(1, transport.requestsTo("/oauth/token").size, "the SDK refreshed more than once")
    assertEquals(2, transport.requestsTo("/scammer-checks").size, "the check ran more than twice")
  }

  @Test
  fun aRevokedGrantIsClearedAndNeverRefreshed() = runSdkTest {
    val store = storedTokens()
    val transport = FakeHttpTransport.always(HttpResponse(401, errorJson("grant_revoked")))
    val client = testClient(transport, tokenStore = store)

    assertFailsWith<TraciveException.GrantRevoked> { client.checkScammer(testQuery()) }

    assertNull(store.load(), "a revoked grant must not be left on the device")
    assertTrue(
      transport.requestsTo("/oauth/token").isEmpty(),
      "a revoked grant must never be refreshed — the attempt looks like a replay",
    )
  }

  /**
   * RFC 6749 answers a retired or **reused** refresh token with `invalid_grant`, which means the
   * same thing as the Tracive envelope's `grant_revoked` and must be treated the same way.
   */
  @Test
  fun aRefreshRejectedAsInvalidGrantClearsTheGrantLocally() = runSdkTest {
    val store = storedTokens()
    val transport = FakeHttpTransport { request ->
      if (request.url.endsWith("/oauth/token"))
        HttpResponse(400, "{\"error\":\"invalid_grant\",\"error_description\":\"reused\"}")
      else HttpResponse(401, errorJson("expired_token"))
    }
    val client = testClient(transport, tokenStore = store)

    assertFailsWith<TraciveException.GrantRevoked> { client.checkScammer(testQuery()) }
    assertNull(store.load())
  }

  @Test
  fun aCallWithoutAGrantFailsBeforeAnythingIsSent() = runSdkTest {
    val transport = FakeHttpTransport.always(jsonResponse(checkResultJson()))
    val client = testClient(transport, tokenStore = InMemoryTokenStore())

    assertFailsWith<TraciveException.InvalidToken> { client.checkScammer(testQuery()) }
    assertTrue(transport.requests.isEmpty(), "an unauthorised call must not reach the network")
  }

  // ----------------------------------------------------------------- retries ----

  /** No key, no retry: the repeat could be a second charge and nothing would collapse it. */
  @Test
  fun aCheckWithoutAnIdempotencyKeyIsNeverRetried() = runSdkTest {
    val transport = FakeHttpTransport.always(HttpResponse(503, errorJson("upstream_down")))
    val client = testClient(transport)

    assertFailsWith<TraciveException.Server> { client.checkScammer(testQuery()) }

    assertEquals(1, transport.requests.size, "a check was repeated without an idempotency key")
  }

  @Test
  fun aCheckWithAnIdempotencyKeyIsRetriedUnderTheSameKey() = runSdkTest {
    val transport =
      FakeHttpTransport.sequence(
        HttpResponse(503, ""),
        HttpResponse(500, ""),
        jsonResponse(checkResultJson()),
      )
    val client = testClient(transport)

    val result = client.checkScammer(testQuery(), idempotencyKey = "idem-7")

    assertTrue(result.found)
    assertEquals(3, transport.requests.size)
    // The same key on every attempt is what lets the server collapse them onto one charge.
    assertTrue(transport.requests.all { it.header("Idempotency-Key") == "idem-7" })
  }

  @Test
  fun theRetryBudgetIsBounded() = runSdkTest {
    val transport = FakeHttpTransport.always(HttpResponse(500, ""))
    val client = testClient(transport)

    assertFailsWith<TraciveException.Server> {
      client.checkScammer(testQuery(), idempotencyKey = "idem-8")
    }
    assertEquals(NO_WAIT_RETRIES.maxAttempts, transport.requests.size)
  }

  /** Reads cannot be double-charged, so they are safe to repeat with no key at all. */
  @Test
  fun readsAreRetried() = runSdkTest {
    val transport =
      FakeHttpTransport.sequence(HttpResponse(500, ""), jsonResponse("{\"subscriptionCredits\":5}"))
    val client = testClient(transport)

    assertEquals(5, client.credits().subscriptionCredits)
    assertEquals(2, transport.requests.size)
  }

  /**
   * A 429 is never slept through inside the SDK. The server has said how long to wait, and hiding
   * that wait inside a suspended call hides a queueing problem the app needs to see.
   */
  @Test
  fun rateLimitingIsSurfacedWithItsWaitAndNotRetried() = runSdkTest {
    val transport =
      FakeHttpTransport.always(
        HttpResponse(429, errorJson("rate_limited"), mapOf("retry-after" to "30"))
      )
    val client = testClient(transport)

    val error =
      assertFailsWith<TraciveException.RateLimited> {
        client.checkScammer(testQuery(), idempotencyKey = "idem-9")
      }

    assertEquals(30.seconds, error.retryAfter)
    assertEquals(1, transport.requests.size, "a rate limit must not be auto-retried")
  }

  @Test
  fun aTransportFailureBecomesANetworkError() = runSdkTest {
    val transport = FakeHttpTransport { throw IllegalStateException("socket closed") }
    val client = testClient(transport)

    val error = assertFailsWith<TraciveException.Network> { client.checkScammer(testQuery()) }

    assertEquals("socket closed", error.cause?.message)
    assertEquals(1, transport.requests.size, "a check must not be repeated after a timeout")
  }

  @Test
  fun aTransportFailureIsRetriedWhenTheCallerMadeItSafe() = runSdkTest {
    var attempts = 0
    val transport = FakeHttpTransport {
      attempts += 1
      if (attempts < 3) throw IllegalStateException("connection reset")
      jsonResponse(checkResultJson())
    }
    val client = testClient(transport)

    val result = client.checkScammer(testQuery(), idempotencyKey = "idem-10")

    assertTrue(result.found)
    assertEquals(3, attempts)
  }
}
