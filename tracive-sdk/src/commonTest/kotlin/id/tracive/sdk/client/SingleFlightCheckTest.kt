package id.tracive.sdk.client

import id.tracive.sdk.model.Detail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/**
 * Concurrent identical checks must produce exactly **one** request.
 *
 * This is the one latch in the SDK whose unit is currency. Every check that reaches the server is
 * billed, a miss included, and the cache write is the only thing that serialises two callers — it
 * happens after both requests have already gone out, so it prevents nothing. A double-tapped button
 * and a list screen rendering the same identifier in two cells are not edge cases; they are what a
 * fraud check looks like in an app, and each of them was charged twice.
 *
 * Every test here forces the interleaving instead of hoping for it: the transport parks on a
 * [CompletableDeferred] the test itself completes, so both callers are provably inside the request
 * window at the same time. A test that let the first call run to completion first would pass
 * against an SDK with no latch at all.
 */
class SingleFlightCheckTest {

  @Test
  fun twoConcurrentIdenticalChecksAreOneRequestAndOneCharge() = runSdkTest {
    val gate = CompletableDeferred<Unit>()
    val transport = FakeHttpTransport {
      gate.await()
      jsonResponse(checkResultJson())
    }
    val client = testClient(transport)

    val first = async(start = CoroutineStart.UNDISPATCHED) { client.checkScammer(testQuery()) }
    val second = async(start = CoroutineStart.UNDISPATCHED) { client.checkScammer(testQuery()) }
    // Both are now parked: one inside the request, the other on the latch.
    gate.complete(Unit)

    assertTrue(first.await().found)
    assertTrue(second.await().found)

    assertEquals(
      1,
      transport.requestsTo("/scammer-checks").size,
      "the same identifier was checked twice at once and billed twice",
    )
  }

  /**
   * The latch key carries the detail level. A minimal answer holds no `topChronologies` and no
   * `comments`, and handing one to a caller who asked for full detail would render as "there is
   * nothing on file" rather than as "this was not fetched".
   */
  @Test
  fun checksThatDifferInDetailDoNotShareARequest() = runSdkTest {
    val gate = CompletableDeferred<Unit>()
    val transport = FakeHttpTransport {
      gate.await()
      jsonResponse(checkResultJson())
    }
    val client = testClient(transport)

    val minimal =
      async(start = CoroutineStart.UNDISPATCHED) {
        client.checkScammer(testQuery(), detail = Detail.MINIMAL)
      }
    val full =
      async(start = CoroutineStart.UNDISPATCHED) {
        client.checkScammer(testQuery(), detail = Detail.FULL, locale = "id")
      }
    gate.complete(Unit)
    minimal.await()
    full.await()

    val bodies = transport.requestsTo("/scammer-checks").map { it.body ?: "" }
    assertEquals(2, bodies.size, "a full-detail check was answered with a minimal one")
    assertTrue(bodies.any { it.contains("\"detail\":\"minimal\"") }, "$bodies")
    assertTrue(bodies.any { it.contains("\"detail\":\"full\"") }, "$bodies")
  }

  /**
   * And the locale, because `tags` and `disclaimer` come back in the language that was asked for.
   */
  @Test
  fun checksThatDifferInLocaleDoNotShareARequest() = runSdkTest {
    val gate = CompletableDeferred<Unit>()
    val transport = FakeHttpTransport {
      gate.await()
      jsonResponse(checkResultJson())
    }
    val client = testClient(transport)

    val indonesian =
      async(start = CoroutineStart.UNDISPATCHED) {
        client.checkScammer(testQuery(), detail = Detail.FULL, locale = "id")
      }
    val english =
      async(start = CoroutineStart.UNDISPATCHED) {
        client.checkScammer(testQuery(), detail = Detail.FULL, locale = "en")
      }
    gate.complete(Unit)
    indonesian.await()
    english.await()

    val bodies = transport.requestsTo("/scammer-checks").map { it.body ?: "" }
    assertEquals(2, bodies.size, "one locale's answer was served to a caller who asked for another")
    assertTrue(bodies.any { it.contains("\"locale\":\"id\"") }, "$bodies")
    assertTrue(bodies.any { it.contains("\"locale\":\"en\"") }, "$bodies")
  }

  /**
   * The latch collapses *overlapping* checks and nothing else. A check made after the first one
   * answered is a second question, and the caller is entitled to a fresh answer to it — a latch
   * that outlived its request would turn into an invisible, unbounded cache with no TTL.
   */
  @Test
  fun aLaterCheckIsNotCollapsedOntoAFinishedOne() = runSdkTest {
    val transport = FakeHttpTransport.always(jsonResponse(checkResultJson()))
    val client = testClient(transport)

    client.checkScammer(testQuery())
    client.checkScammer(testQuery())

    assertEquals(2, transport.requestsTo("/scammer-checks").size)
  }

  /**
   * Sharing a request must not hand one caller control of another's. A screen that closes, or a
   * `withTimeout` that fires, cancels the coroutine that asked — and the check somebody else is
   * still waiting for has to survive it.
   */
  @Test
  fun oneCallerGivingUpDoesNotCancelAnothersCheck() = runSdkTest {
    val gate = CompletableDeferred<Unit>()
    val transport = FakeHttpTransport {
      gate.await()
      jsonResponse(checkResultJson())
    }
    val client = testClient(transport)

    val leaver =
      launch(start = CoroutineStart.UNDISPATCHED) {
        runCatching { client.checkScammer(testQuery()) }
      }
    val stayer = async(start = CoroutineStart.UNDISPATCHED) { client.checkScammer(testQuery()) }

    leaver.cancelAndJoin()
    gate.complete(Unit)

    assertTrue(stayer.await().found, "one caller's cancellation killed another caller's check")
    assertEquals(
      1,
      transport.requestsTo("/scammer-checks").size,
      "the two callers did not share a request",
    )
  }

  /**
   * The other half of the same rule: when the last caller gives up, the request goes with them.
   *
   * Otherwise sharing would quietly remove the ability to stop a check at all — every caller could
   * abandon a request and it would still run to completion, and still be billed.
   */
  @Test
  fun whenEveryCallerGivesUpTheRequestIsAbandonedToo() = runSdkTest {
    val gate = CompletableDeferred<Unit>()
    val transport = FakeHttpTransport {
      gate.await()
      jsonResponse(checkResultJson())
    }
    val cache = FakeCheckCache()
    val client = testClient(transport, cache = cache)

    val first =
      launch(start = CoroutineStart.UNDISPATCHED) {
        runCatching { client.checkScammer(testQuery()) }
      }
    val second =
      launch(start = CoroutineStart.UNDISPATCHED) {
        runCatching { client.checkScammer(testQuery()) }
      }

    first.cancelAndJoin()
    second.cancelAndJoin()
    // Nothing is waiting for this answer any more, so nothing may consume it.
    gate.complete(Unit)

    assertEquals(
      1,
      transport.requestsTo("/scammer-checks").size,
      "the two callers did not share a request",
    )
    assertEquals(
      0,
      cache.writes,
      "the request outlived every caller that wanted it and stored its answer anyway",
    )
  }
}
