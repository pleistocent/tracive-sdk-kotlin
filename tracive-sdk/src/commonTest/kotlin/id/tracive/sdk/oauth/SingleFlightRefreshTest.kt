package id.tracive.sdk.oauth

import id.tracive.sdk.TraciveException
import id.tracive.sdk.client.FakeHttpTransport
import id.tracive.sdk.client.HttpResponse
import id.tracive.sdk.client.checkResultJson
import id.tracive.sdk.client.errorJson
import id.tracive.sdk.client.jsonResponse
import id.tracive.sdk.client.runSdkTest
import id.tracive.sdk.client.storedTokens
import id.tracive.sdk.client.testClient
import id.tracive.sdk.client.testQuery
import id.tracive.sdk.client.tokenJson
import id.tracive.sdk.query.TraciveQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async

/**
 * Concurrent 401s must produce exactly **one** refresh.
 *
 * This is the most expensive thing in the SDK to get wrong, and the failure is invisible in a
 * single-threaded test. The refresh token rotates: the server issues a new one on every refresh and
 * treats a *reused* one as evidence that it was stolen. Two coroutines that each hit an expired
 * access token at the same moment and each POST the same refresh token therefore do not merely
 * waste a request — the second one looks like theft, and the server's answer is to revoke the whole
 * grant and sign the user out of an app that did nothing wrong.
 *
 * The tests below force the interleaving deterministically rather than hoping for it: the token
 * endpoint parks on a [CompletableDeferred] the test itself completes, so both callers are provably
 * inside the refresh window at the same time.
 */
class SingleFlightRefreshTest {

  @Test
  fun concurrentExpiredTokensTriggerExactlyOneRefresh() = runSdkTest {
    val gate = CompletableDeferred<Unit>()
    val store = storedTokens(accessToken = "access-1", refreshToken = "refresh-1")
    val transport = FakeHttpTransport { request ->
      if (request.url.endsWith("/oauth/token")) {
        // Hold the refresh open so the second caller is guaranteed to arrive while it is in
        // flight. Without this the two would serialise by accident and prove nothing.
        gate.await()
        jsonResponse(tokenJson(accessToken = "access-2", refreshToken = "refresh-2"))
      } else if (request.header("Authorization") == "Bearer access-1") {
        HttpResponse(401, errorJson("expired_token"))
      } else {
        jsonResponse(checkResultJson())
      }
    }
    val client = testClient(transport, tokenStore = store)

    val first = async(start = CoroutineStart.UNDISPATCHED) { client.checkScammer(testQuery()) }
    val second =
      async(start = CoroutineStart.UNDISPATCHED) {
        client.checkScammer(TraciveQuery.email("someone@example.com"))
      }

    // Both are now parked: one inside the refresh, the other behind the single-flight lock.
    gate.complete(Unit)

    assertTrue(first.await().found)
    assertTrue(second.await().found)

    assertEquals(
      1,
      transport.requestsTo("/oauth/token").size,
      "the refresh token was spent more than once, which the server reads as theft",
    )
    // Two checks each ran twice: once with the expired token, once with the fresh one.
    assertEquals(4, transport.requestsTo("/scammer-checks").size)

    val stored = assertNotNull(store.load())
    assertEquals("access-2", stored.accessToken)
    assertEquals("refresh-2", stored.refreshToken)
  }

  /**
   * The caller that loses the race must not perform a refresh of its own. It recognises that the
   * stored access token is no longer the one it failed with and reuses the winner's result.
   */
  @Test
  fun theLoserOfTheRaceReusesTheWinnersTokenWithoutASecondRequest() = runSdkTest {
    val gate = CompletableDeferred<Unit>()
    val store = storedTokens(accessToken = "access-1", refreshToken = "refresh-1")
    val transport = FakeHttpTransport { request ->
      if (request.url.endsWith("/oauth/token")) {
        gate.await()
        jsonResponse(tokenJson(accessToken = "access-2", refreshToken = "refresh-2"))
      } else if (request.header("Authorization") == "Bearer access-1") {
        HttpResponse(401, errorJson("expired_token"))
      } else {
        jsonResponse(checkResultJson())
      }
    }
    val client = testClient(transport, tokenStore = store)

    val first = async(start = CoroutineStart.UNDISPATCHED) { client.checkScammer(testQuery()) }
    val second =
      async(start = CoroutineStart.UNDISPATCHED) {
        client.checkScammer(TraciveQuery.iban("GB33BUKB20201555555555"))
      }
    gate.complete(Unit)
    first.await()
    second.await()

    assertEquals(
      1,
      transport.requestsTo("/oauth/token").size,
      "the caller that lost the race performed a refresh of its own",
    )
    // Each check ran twice: once with the expired token, once with the token the winner fetched.
    val checks = transport.requestsTo("/scammer-checks")
    assertEquals(4, checks.size)
    assertEquals(2, checks.count { it.header("Authorization") == "Bearer access-1" })
    assertEquals(2, checks.count { it.header("Authorization") == "Bearer access-2" })
  }

  /**
   * Five callers, one refresh. The count is what matters: any implementation that refreshes
   * per-caller passes the two-caller test by luck often enough to be worth widening.
   */
  @Test
  fun fiveConcurrentCallersStillProduceOneRefresh() = runSdkTest {
    val gate = CompletableDeferred<Unit>()
    val store = storedTokens(accessToken = "access-1", refreshToken = "refresh-1")
    val transport = FakeHttpTransport { request ->
      if (request.url.endsWith("/oauth/token")) {
        gate.await()
        jsonResponse(tokenJson(accessToken = "access-2", refreshToken = "refresh-2"))
      } else if (request.header("Authorization") == "Bearer access-1") {
        HttpResponse(401, errorJson("expired_token"))
      } else {
        jsonResponse(checkResultJson())
      }
    }
    val client = testClient(transport, tokenStore = store)

    val queries =
      listOf(
        TraciveQuery.phone("+6281234567891"),
        TraciveQuery.phone("+6281234567892"),
        TraciveQuery.phone("+6281234567893"),
        TraciveQuery.phone("+6281234567894"),
        TraciveQuery.phone("+6281234567895"),
      )
    val calls = queries.map { query ->
      async(start = CoroutineStart.UNDISPATCHED) { client.checkScammer(query) }
    }
    gate.complete(Unit)
    calls.forEach { assertTrue(it.await().found) }

    assertEquals(1, transport.requestsTo("/oauth/token").size)
  }

  /**
   * A revoked grant is terminal. Concurrent callers must not each try a refresh — the grant is
   * gone, and repeated attempts with a retired token are indistinguishable from an attack.
   */
  @Test
  fun aRevokedGrantIsNeverRefreshedByAnyConcurrentCaller() = runSdkTest {
    val gate = CompletableDeferred<Unit>()
    val store = storedTokens()
    // Both checks are held in flight until the gate opens, so neither can have finished — and
    // cleared the grant — before the other one starts. Without the gate the first call would run to
    // completion synchronously and the second would merely find an empty token store, which proves
    // nothing about what two genuinely concurrent callers do.
    val transport = FakeHttpTransport {
      gate.await()
      HttpResponse(401, errorJson("grant_revoked"))
    }
    val client = testClient(transport, tokenStore = store)

    // The failures are captured inside each coroutine rather than allowed to escape: an `async`
    // that throws cancels its parent, which would take the sibling and the test itself down with
    // it and hide what actually happened.
    val first =
      async(start = CoroutineStart.UNDISPATCHED) {
        runCatching { client.checkScammer(testQuery()) }
      }
    val second =
      async(start = CoroutineStart.UNDISPATCHED) {
        runCatching { client.checkScammer(TraciveQuery.email("someone@example.com")) }
      }
    gate.complete(Unit)

    assertIs<TraciveException.GrantRevoked>(first.await().exceptionOrNull())
    assertIs<TraciveException.GrantRevoked>(second.await().exceptionOrNull())

    assertTrue(transport.requestsTo("/oauth/token").isEmpty())
    assertNull(store.load(), "a revoked grant must not be left on the device")
  }
}
