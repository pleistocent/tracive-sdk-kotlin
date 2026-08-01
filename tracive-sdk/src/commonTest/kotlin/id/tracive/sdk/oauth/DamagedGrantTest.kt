package id.tracive.sdk.oauth

import id.tracive.sdk.TraciveException
import id.tracive.sdk.client.FakeHttpTransport
import id.tracive.sdk.client.checkResultJson
import id.tracive.sdk.client.jsonResponse
import id.tracive.sdk.client.runSdkTest
import id.tracive.sdk.client.testClient
import id.tracive.sdk.client.testQuery
import id.tracive.sdk.client.tokenJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A stored grant whose access token is blank and whose refresh token is intact.
 *
 * A partial write, a keystore save interrupted by a kill, or a restored backup can all leave one
 * behind. The refresh token beside the empty field is a live credential and the grant is still
 * good, so the answer is to spend it rather than to make the user consent again — re-consent
 * discards something that still works, and it is the one repair the user cannot be asked to
 * understand.
 *
 * Nothing reactive can rescue this record. An empty bearer is not an *expired* token: the server
 * answers a malformed one with `invalid_token`, which this SDK treats as terminal, so the damaged
 * record does not heal itself on the next call. It fails permanently and spends a round trip
 * finding out. The guard therefore has to be here, before the request is built.
 */
class DamagedGrantTest {

  /**
   * The headline case, end to end: the blob a damaged store hands back, through the codec and the
   * client, to a check the caller sees succeed.
   */
  @Test
  fun aDamagedRecordWithALiveRefreshTokenIsRenewedRatherThanDiscarded() = runSdkTest {
    val recovered =
      assertNotNull(
        TokenSetCodec.decode(damagedBlob(refreshToken = "refresh-1")),
        "a blank access token must not take a live refresh token down with it",
      )
    assertEquals("refresh-1", recovered.refreshToken)

    val store = InMemoryTokenStore(recovered)
    val transport = FakeHttpTransport { request ->
      if (request.url.endsWith(TraciveOAuth.PATH_TOKEN)) {
        jsonResponse(tokenJson(accessToken = "access-2", refreshToken = "refresh-2"))
      } else {
        jsonResponse(checkResultJson())
      }
    }
    val client = testClient(transport, tokenStore = store)

    assertTrue(client.checkScammer(testQuery()).found, "the caller must not see an error at all")

    assertEquals(
      1,
      transport.requestsTo(TraciveOAuth.PATH_TOKEN).size,
      "the blank access token must be renewed exactly once",
    )
    val checks = transport.requestsTo("/scammer-checks")
    assertEquals(1, checks.size, "the check goes out once, already carrying the renewed token")
    assertEquals("Bearer access-2", checks.single().header("Authorization"))
    transport.assertNoEmptyBearer()

    // The renewed pair replaced both halves, so the rotated refresh token is the stored one.
    val stored = assertNotNull(store.load())
    assertEquals("access-2", stored.accessToken)
    assertEquals("refresh-2", stored.refreshToken)
  }

  /**
   * The same damage with nothing to repair it with. There is no credential left in the record, so
   * it reads as no grant at all and the caller is told to authorize — the existing answer for an
   * empty store, reached by the existing route.
   */
  @Test
  fun aDamagedRecordWithNoRefreshTokenIsTerminalAndSendsNothing() = runSdkTest {
    val recovered = TokenSetCodec.decode(damagedBlob(refreshToken = null))
    assertNull(
      recovered,
      "a record with neither credential is not a grant, and holding it pretends it is",
    )

    val transport = FakeHttpTransport { jsonResponse(checkResultJson()) }
    val client = testClient(transport, tokenStore = InMemoryTokenStore(recovered))

    assertFailsWith<TraciveException.InvalidToken> { client.checkScammer(testQuery()) }

    assertTrue(
      transport.requests.isEmpty(),
      "an unrepairable grant must cost no round trip: there is nothing a server could add",
    )
    transport.assertNoEmptyBearer()
  }

  /**
   * The same repair, reached without the codec. `adoptTokens` stores what a partner's backend
   * handed back and validates none of it, so a backend that answered with an empty access token
   * beside a good refresh token produces this state directly.
   */
  @Test
  fun aGrantAdoptedWithABlankAccessTokenIsRenewedOnFirstUse() = runSdkTest {
    val transport = FakeHttpTransport { request ->
      if (request.url.endsWith(TraciveOAuth.PATH_TOKEN)) {
        jsonResponse(tokenJson(accessToken = "access-2", refreshToken = "refresh-2"))
      } else {
        jsonResponse(checkResultJson())
      }
    }
    val client = testClient(transport, tokenStore = InMemoryTokenStore())
    client.oauth!!.adoptTokens(
      accessToken = "",
      refreshToken = "refresh-1",
      grantedScopes = listOf("scammer.check"),
    )

    assertTrue(client.checkScammer(testQuery()).found)

    assertEquals(1, transport.requestsTo(TraciveOAuth.PATH_TOKEN).size)
    val checks = transport.requestsTo("/scammer-checks")
    assertEquals("Bearer access-2", checks.single().header("Authorization"))
    transport.assertNoEmptyBearer()
  }

  /** A grant that is only a refresh token survives storage; one that is nothing does not. */
  @Test
  fun onlyARecordHoldingSomeLiveCredentialSurvivesDecoding() {
    val recoverable = assertNotNull(TokenSetCodec.decode(damagedBlob(refreshToken = "refresh-1")))
    assertEquals("", recoverable.accessToken)
    assertEquals("refresh-1", recoverable.refreshToken)
    assertEquals(listOf("scammer.check"), recoverable.grantedScopes)

    assertNull(TokenSetCodec.decode(damagedBlob(refreshToken = null)))
    assertNull(TokenSetCodec.decode(damagedBlob(refreshToken = "   ")))
  }

  /** The relaxed invariant is still an invariant: a token set holding nothing is refused. */
  @Test
  fun aTokenSetStillRefusesToHoldNoCredentialAtAll() {
    assertFailsWith<IllegalArgumentException> { TokenSet(accessToken = "") }
    assertFailsWith<IllegalArgumentException> { TokenSet(accessToken = " ", refreshToken = " ") }
    // One live credential is enough, whichever half of the pair it is.
    TokenSet(accessToken = "", refreshToken = "refresh-1")
    TokenSet(accessToken = "access-1", refreshToken = null)
  }

  /**
   * A stored blob with an empty `at`, exactly as a partial write leaves one. Hand-written rather
   * than produced by the codec's own encoder, because the point is to read a record this SDK would
   * not choose to write.
   */
  private fun damagedBlob(refreshToken: String?): String = buildString {
    append("{\"v\":").append(TokenSetCodec.VERSION).append(",\"at\":\"\"")
    if (refreshToken != null) append(",\"rt\":\"").append(refreshToken).append("\"")
    append(",\"scopes\":[\"scammer.check\"],\"type\":\"Bearer\"}")
  }

  /** No request may carry `Bearer` with nothing behind it, on any path. */
  private fun FakeHttpTransport.assertNoEmptyBearer() {
    for (request in requests) {
      val authorization = request.header("Authorization") ?: continue
      assertTrue(
        authorization.removePrefix("Bearer ").isNotBlank(),
        "a request went out with an empty bearer token: <$authorization>",
      )
    }
  }
}
