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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A refresh token that has already gone out must never go out a second time.
 *
 * The refresh token rotates, and the server treats a re-presented one as theft: it revokes the
 * whole grant rather than failing the one call. The single-flight lock only makes concurrent
 * callers safe, and this hazard is not concurrent. A refresh that fails **with no reply at all** —
 * a socket timeout, a connection dropped on the way back — is indistinguishable from a success
 * whose answer was lost, so the server may already have rotated the token and be waiting to see the
 * new one. Meanwhile the store still holds the old one, and the next 401, minutes later and on
 * nobody else's thread, reads it back and sends it. That second presentation is exactly what reuse
 * detection is looking for.
 *
 * The rule the tests below pin, in the order they pin it:
 * - no reply → the token stays marked and the SDK refuses to send it again;
 * - a complete reply, whatever its status → the token was **not** consumed (RFC 6749 §5.2), the
 *   mark comes off, and a later refresh with it still goes out;
 * - sign-out → the history is emptied with the grant it describes.
 */
class SpentRefreshTokenTest {

  /**
   * The discriminating case. Without the guard the second attempt puts the same refresh token back
   * on the wire, and the user loses the grant instead of one call.
   */
  @Test
  fun aRefreshThatGotNoAnswerIsNeverPresentedAgain() = runSdkTest {
    val store = storedTokens(accessToken = "access-1", refreshToken = "refresh-1")
    val transport = FakeHttpTransport { request ->
      if (request.url.endsWith(TraciveOAuth.PATH_TOKEN)) {
        // No reply at all. The server may have rotated the token and lost the answer on the way
        // back, or may never have seen the request; from here the two look identical.
        throw TraciveException.Network("the connection dropped before any reply arrived")
      }
      HttpResponse(401, errorJson("expired_token"))
    }
    val client = testClient(transport, tokenStore = store)

    // How the first call reports a request that got no answer is not this test's subject, so it is
    // swallowed. What the SDK does *afterwards* is the subject.
    runCatching { client.checkScammer(testQuery()) }
    val second = runCatching { client.checkScammer(testQuery()) }

    assertEquals(
      1,
      transport.requestsTo(TraciveOAuth.PATH_TOKEN).size,
      "a refresh token whose fate was never learned was presented a second time — the server " +
        "reads that as theft and revokes the whole grant, not just the call",
    )
    assertIs<TraciveException.InvalidToken>(
      second.exceptionOrNull(),
      "the second refresh must fail locally, telling the caller to authorize again",
    )
    // The guard is local and does not touch the device's grant; only authorizing again, or signing
    // out, replaces it.
    assertEquals("refresh-1", assertNotNull(store.load()).refreshToken)
  }

  /**
   * A complete reply settles the question even when it is a failure: a token endpoint that answers
   * with an error has issued no tokens, so the refresh token was not consumed. Without releasing
   * the mark here, one 500 would end the grant for good.
   */
  @Test
  fun anErrorResponseReleasesTheTokenSoALaterRefreshStillGoesOut() = runSdkTest {
    val store = storedTokens(accessToken = "access-1", refreshToken = "refresh-1")
    var refreshes = 0
    val transport = FakeHttpTransport { request ->
      when {
        request.url.endsWith(TraciveOAuth.PATH_TOKEN) -> {
          refreshes += 1
          if (refreshes == 1) HttpResponse(500, errorJson("internal_error"))
          else jsonResponse(tokenJson(accessToken = "access-2", refreshToken = "refresh-2"))
        }
        request.header("Authorization") == "Bearer access-1" ->
          HttpResponse(401, errorJson("expired_token"))
        else -> jsonResponse(checkResultJson())
      }
    }
    val client = testClient(transport, tokenStore = store)

    val first = runCatching { client.checkScammer(testQuery()) }
    val second = client.checkScammer(testQuery())

    assertIs<TraciveException.Server>(first.exceptionOrNull())
    assertTrue(second.found, "the grant was still refreshable after the token endpoint's 500")
    val bodies = transport.requestsTo(TraciveOAuth.PATH_TOKEN).map { assertNotNull(it.body) }
    assertEquals(2, bodies.size)
    assertTrue(
      bodies.all { it.contains("refresh_token=refresh-1") },
      "the second refresh sent something other than the token the 500 left unspent",
    )
    val stored = assertNotNull(store.load())
    assertEquals("access-2", stored.accessToken)
    assertEquals("refresh-2", stored.refreshToken)
  }

  /** The history describes the grant that is being thrown away, and nothing about the next one. */
  @Test
  fun signOutForgetsWhichTokensWereSpent() = runSdkTest {
    val store = storedTokens(accessToken = "access-1", refreshToken = "refresh-1")
    var refreshes = 0
    val transport = FakeHttpTransport { request ->
      when {
        request.url.endsWith(TraciveOAuth.PATH_TOKEN) -> {
          refreshes += 1
          if (refreshes == 1)
            throw TraciveException.Network("the connection dropped before any reply arrived")
          else jsonResponse(tokenJson(accessToken = "access-2", refreshToken = "refresh-2"))
        }
        request.url.endsWith(TraciveOAuth.PATH_REVOKE) -> HttpResponse(200, "")
        request.header("Authorization") == "Bearer access-1" ->
          HttpResponse(401, errorJson("expired_token"))
        else -> jsonResponse(checkResultJson())
      }
    }
    val client = testClient(transport, tokenStore = store)
    val oauth = assertNotNull(client.oauth)

    runCatching { client.checkScammer(testQuery()) }
    client.signOut()
    // The new grant deliberately carries the same token *value*. The history is keyed on the token
    // itself, so reusing the string is the only way a test can see whether sign-out emptied it; a
    // server would never hand the same one back.
    oauth.adoptTokens(
      accessToken = "access-1",
      refreshToken = "refresh-1",
      grantedScopes = listOf("scammer.check"),
    )

    val result = client.checkScammer(testQuery())

    assertTrue(result.found)
    assertEquals(
      2,
      transport.requestsTo(TraciveOAuth.PATH_TOKEN).size,
      "sign-out left the previous grant's spent tokens behind, so the new grant could not refresh",
    )
  }

  /**
   * The ordinary path, twice in a row. Marking a token spent must not stand in the way of the
   * rotated one that replaces it — which is the whole grant's ability to keep working.
   */
  @Test
  fun consecutiveRefreshesEachSendTheirOwnRotatedToken() = runSdkTest {
    val store = storedTokens(accessToken = "access-1", refreshToken = "refresh-1")
    var issued = 1
    // Expires one bearer token at a time, on demand, so each check drives a refresh of its own.
    // Expiring every first sight of a token instead would 401 the replay as well, and the SDK
    // refreshes at most once per request by design.
    var expireNextBearer = true
    val transport = FakeHttpTransport { request ->
      when {
        request.url.endsWith(TraciveOAuth.PATH_TOKEN) -> {
          issued += 1
          jsonResponse(tokenJson(accessToken = "access-$issued", refreshToken = "refresh-$issued"))
        }
        expireNextBearer -> {
          expireNextBearer = false
          HttpResponse(401, errorJson("expired_token"))
        }
        else -> jsonResponse(checkResultJson())
      }
    }
    val client = testClient(transport, tokenStore = store)

    assertTrue(client.checkScammer(testQuery()).found)
    expireNextBearer = true
    assertTrue(client.checkScammer(testQuery()).found)

    val bodies = transport.requestsTo(TraciveOAuth.PATH_TOKEN).map { assertNotNull(it.body) }
    assertEquals(2, bodies.size)
    assertTrue(bodies[0].contains("refresh_token=refresh-1"), bodies[0])
    assertTrue(bodies[1].contains("refresh_token=refresh-2"), bodies[1])
    assertEquals("refresh-3", assertNotNull(store.load()).refreshToken)
  }
}
