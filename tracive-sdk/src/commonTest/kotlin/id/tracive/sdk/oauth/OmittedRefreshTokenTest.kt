package id.tracive.sdk.oauth

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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A refresh reply that omits `refresh_token` must not end the grant.
 *
 * RFC 6749 §6 lets the token endpoint leave `refresh_token` out of a refresh reply, and leaving it
 * out means "keep the one you have" — not "you no longer have one". A fully conforming server can
 * therefore answer a refresh with an access token and nothing else, and if the SDK stores that
 * literally it stores a null refresh token: the next 401 has nothing to present, and the user is
 * sent back through consent for a grant that never ended. The reasoning is the one already applied
 * to `scope` two lines away in `tokenSetFrom`, on a field that costs more when it is wrong.
 *
 * The carried-forward token then meets the spent-token history, which is why the second test is
 * here. That exact string was marked spent moments before the request went out, and a mark is only
 * released on the failure paths; a token the server declined to rotate has to come back out of the
 * history, or the guard against presenting a token twice turns into a guard against presenting the
 * grant's only token at all.
 */
class OmittedRefreshTokenTest {

  /** The reply is conforming and complete. What it omits, the SDK already has. */
  @Test
  fun aReplyWithNoRefreshTokenKeepsTheStoredOne() = runSdkTest {
    val store = storedTokens(accessToken = "access-1", refreshToken = "refresh-1")
    val transport = FakeHttpTransport { request ->
      when {
        // An access token and nothing else. The grant's refresh token is unchanged, so the server
        // says nothing about it.
        request.url.endsWith(TraciveOAuth.PATH_TOKEN) ->
          jsonResponse(tokenJson(accessToken = "access-2", refreshToken = null))
        request.header("Authorization") == "Bearer access-1" ->
          HttpResponse(401, errorJson("expired_token"))
        else -> jsonResponse(checkResultJson())
      }
    }
    val client = testClient(transport, tokenStore = store)

    assertTrue(client.checkScammer(testQuery()).found)

    val stored = assertNotNull(store.load())
    assertEquals("access-2", stored.accessToken)
    assertEquals(
      "refresh-1",
      stored.refreshToken,
      "a reply that omitted refresh_token was read as the grant no longer having one, so the " +
        "device now holds an access token it cannot renew",
    )
  }

  /**
   * The discriminating case, one refresh further on. A grant that cannot refresh a second time is a
   * grant that ends at the next expiry, which is the consent screen the user did nothing to earn.
   */
  @Test
  fun theCarriedForwardTokenStillRefreshesTheNextTime() = runSdkTest {
    val store = storedTokens(accessToken = "access-1", refreshToken = "refresh-1")
    var issued = 1
    // Expires one bearer token at a time, on demand, so each check drives a refresh of its own.
    var expireNextBearer = true
    val transport = FakeHttpTransport { request ->
      when {
        request.url.endsWith(TraciveOAuth.PATH_TOKEN) -> {
          issued += 1
          jsonResponse(tokenJson(accessToken = "access-$issued", refreshToken = null))
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
    assertTrue(
      client.checkScammer(testQuery()).found,
      "the second refresh never reached the server: the grant was left with no refresh token, or " +
        "with one the spent history was still holding against it",
    )

    val bodies = transport.requestsTo(TraciveOAuth.PATH_TOKEN).map { assertNotNull(it.body) }
    assertEquals(2, bodies.size)
    assertTrue(
      bodies.all { it.contains("refresh_token=refresh-1") },
      "a refresh presented something other than the token the server declined to rotate",
    )
    assertEquals("refresh-1", assertNotNull(store.load()).refreshToken)
  }
}
