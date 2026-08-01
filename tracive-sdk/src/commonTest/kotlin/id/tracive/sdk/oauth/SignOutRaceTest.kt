package id.tracive.sdk.oauth

import id.tracive.sdk.TraciveException
import id.tracive.sdk.client.FakeAppPresenceChecker
import id.tracive.sdk.client.FakeHttpTransport
import id.tracive.sdk.client.FixedClock
import id.tracive.sdk.client.HttpResponse
import id.tracive.sdk.client.RecordingAuthorizeLauncher
import id.tracive.sdk.client.TEST_BASE_URL
import id.tracive.sdk.client.errorJson
import id.tracive.sdk.client.jsonResponse
import id.tracive.sdk.client.runSdkTest
import id.tracive.sdk.client.storedTokens
import id.tracive.sdk.client.testClient
import id.tracive.sdk.client.testOAuthMode
import id.tracive.sdk.client.testQuery
import id.tracive.sdk.client.tokenJson
import id.tracive.sdk.model.AuthResult
import id.tracive.sdk.model.TraciveEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async

/**
 * A token round trip that was already on the wire when the user signed out must not put the grant
 * back.
 *
 * Discarding credentials is instantaneous; obtaining them is not. So a refresh or a code exchange
 * that started before `signOut()` still completes afterwards, and its write lands in a store the
 * user has just emptied. The device is then signed out as far as the UI is concerned and holds a
 * live grant as far as the server is concerned — and on the refresh path the token it holds is the
 * **rotated** one, the newest and longest-lived refresh token the grant ever had.
 *
 * Both tests below force the interleaving rather than hoping for it: the token endpoint parks on a
 * [CompletableDeferred] the test itself completes, so the sign-out provably happens while the round
 * trip is in flight. Without that they would serialise by accident and prove nothing — the failure
 * mode `CONTRIBUTING.md` warns about, where a concurrency guard sits unpinned because the harness
 * could not produce the interleaving at all.
 *
 * Waiting for the in-flight work inside `signOut()` is not the fix. It would make signing out
 * depend on a network call, and a sign-out has to be immediate and final even offline.
 */
class SignOutRaceTest {

  @Test
  fun aRefreshInFlightCannotWriteTokensBackAfterSignOut() = runSdkTest {
    val gate = CompletableDeferred<Unit>()
    val store = storedTokens(accessToken = "access-1", refreshToken = "refresh-1")
    val transport = FakeHttpTransport { request ->
      when {
        request.url.endsWith(TraciveOAuth.PATH_TOKEN) -> {
          gate.await()
          jsonResponse(tokenJson(accessToken = "access-2", refreshToken = "refresh-2"))
        }
        request.url.endsWith(TraciveOAuth.PATH_REVOKE) -> HttpResponse(200, "")
        else -> HttpResponse(401, errorJson("expired_token"))
      }
    }
    val client = testClient(transport, tokenStore = store)

    // The failure is captured inside the coroutine: an `async` that throws would cancel the test
    // itself and hide what actually happened.
    val check =
      async(start = CoroutineStart.UNDISPATCHED) {
        runCatching { client.checkScammer(testQuery()) }
      }
    assertEquals(
      1,
      transport.requestsTo(TraciveOAuth.PATH_TOKEN).size,
      "the refresh has not reached the wire yet, so the sign-out below would not race anything",
    )

    client.signOut()
    gate.complete(Unit)
    // Awaited before the store is read, and that order is the test. Completing the gate only
    // queues the parked coroutine on the event loop; reading the store first would look at it
    // before the refresh had a chance to write, and would pass against the very code this pins.
    val outcome = check.await()

    assertNull(
      store.load(),
      "a refresh that was in flight rewrote the grant after sign-out — and the refresh token it " +
        "left behind is the freshly rotated one",
    )
    assertIs<TraciveException.GrantRevoked>(
      outcome.exceptionOrNull(),
      "the refresh must fail rather than quietly return a token it was not allowed to store",
    )
  }

  @Test
  fun aCodeExchangeInFlightCannotStoreAGrantAfterSignOut() = runSdkTest {
    val gate = CompletableDeferred<Unit>()
    val store = InMemoryTokenStore()
    val launcher = RecordingAuthorizeLauncher()
    val transport = FakeHttpTransport { request ->
      when {
        request.url.endsWith(TraciveOAuth.PATH_TOKEN) -> {
          gate.await()
          jsonResponse(tokenJson())
        }
        else -> HttpResponse(200, "")
      }
    }
    val oauth = oauthUnderTest(transport, launcher, store)

    oauth.authorize()
    val state =
      TraciveOAuth.parseQueryParameters(launcher.authorizeUrls.single())[
          AuthorizeUrlBuilder.PARAM_STATE]!!
    val callback =
      async(start = CoroutineStart.UNDISPATCHED) {
        oauth.handleCallback("$REDIRECT_URI?code=auth-code-1&state=$state")
      }
    assertEquals(
      1,
      transport.requestsTo(TraciveOAuth.PATH_TOKEN).size,
      "the exchange has not reached the wire yet, so the sign-out below would not race anything",
    )

    oauth.signOut()
    gate.complete(Unit)
    // Awaited first, for the reason given in the test above: reading the store before the exchange
    // has resumed would pass whatever the exchange then went on to do.
    val result = callback.await()

    assertNull(
      store.load(),
      "the exchange stored a grant the user had already abandoned by signing out",
    )
    assertIs<TraciveException.GrantRevoked>(assertIs<AuthResult.Failed>(result).error)
  }

  private fun oauthUnderTest(
    transport: FakeHttpTransport,
    launcher: RecordingAuthorizeLauncher,
    tokenStore: TokenStore,
  ): TraciveOAuth =
    TraciveOAuth(
      mode = testOAuthMode(redirectUri = REDIRECT_URI),
      environment = TraciveEnvironment.Custom(TEST_BASE_URL),
      transport = transport,
      tokenStore = tokenStore,
      appPresenceChecker = FakeAppPresenceChecker(installed = true),
      authorizeLauncher = launcher,
      clock = FixedClock(),
    )

  private companion object {
    const val REDIRECT_URI = "https://partner.test/tracive/callback"
  }
}
