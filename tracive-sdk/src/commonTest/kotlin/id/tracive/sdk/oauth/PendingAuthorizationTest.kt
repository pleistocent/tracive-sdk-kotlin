package id.tracive.sdk.oauth

import id.tracive.sdk.TraciveException
import id.tracive.sdk.client.FakeAppPresenceChecker
import id.tracive.sdk.client.FakeHttpTransport
import id.tracive.sdk.client.FixedClock
import id.tracive.sdk.client.HttpResponse
import id.tracive.sdk.client.RecordingAuthorizeLauncher
import id.tracive.sdk.client.TEST_BASE_URL
import id.tracive.sdk.client.jsonResponse
import id.tracive.sdk.client.runSdkTest
import id.tracive.sdk.client.testOAuthMode
import id.tracive.sdk.client.tokenJson
import id.tracive.sdk.model.AuthResult
import id.tracive.sdk.model.ClientType
import id.tracive.sdk.model.TraciveEnvironment
import id.tracive.sdk.platform.AuthorizeLauncher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * The pending authorization: where it lives, how long it lives, and when it dies.
 *
 * Consent happens in the Tracive app, so the host process is in the background for the whole of it
 * and on Android is an ordinary candidate for being killed. The PKCE verifier is the half of the
 * pair that never leaves the device, so a verifier held only in a field dies with the process, and
 * the returning callback then names a `state` nothing can match. Every test here follows from that.
 *
 * Two of the assertions here are negative and both cover silent failures:
 * - a spent or abandoned attempt **must not** be left on disk for a later redirect to replay, and
 * - an attempt older than the authorization code it is waiting for **must not** be exchanged,
 *   because that round trip can only come back `invalid_grant` — with the user watching.
 */
class PendingAuthorizationTest {

  // ---------------------------------------------------------------- persistence ----

  /**
   * The record is on disk *before* the browser opens, not after.
   *
   * The ordering is the whole guarantee. The process can be collected the instant it drops to the
   * background, which is the instant the authorize URL is opened. A record written afterwards
   * therefore has a window in which it does not exist yet, and a process death inside that window
   * loses the very flow the record exists to rescue.
   */
  @Test
  fun theRecordIsPersistedBeforeTheAuthorizeUrlIsOpened() = runSdkTest {
    val store = InMemoryTokenStore()
    val launcher = StoreInspectingLauncher(store)
    val oauth = oauthUnderTest(launcher = launcher, tokenStore = store)

    val outcome = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }

    val atLaunch =
      assertNotNull(
        launcher.pendingAtLaunch,
        "the browser was opened before the pending authorization was stored",
      )
    assertEquals(
      TraciveOAuth.parseQueryParameters(launcher.authorizeUrls.single())[
          AuthorizeUrlBuilder.PARAM_STATE],
      atLaunch.state,
      "the stored record does not describe the request that was opened",
    )

    oauth.cancelPendingAuthorization()
    outcome.await()
  }

  /**
   * The verifier outlives the process that generated it — and the continuation does not.
   *
   * The first half is the point of persisting at all: a new process finds `state`, the verifier and
   * the redirect URI exactly as they were sent, so nothing the exchange needs has been lost.
   *
   * The second half is pinned deliberately rather than left implicit. `authorize()` suspends until
   * its redirect arrives, and a suspended call does not survive the process that owns it, so a
   * recovered record has nowhere to deliver its outcome and `handleCallback` answers `false`. That
   * is a limit of the current public surface, not of the stored record. Whoever adds a way to
   * complete a recovered authorization will have to change this assertion, which is the point of
   * writing it down.
   */
  @Test
  fun theRecordSurvivesTheProcessThatStartedItButTheContinuationDoesNot() = runSdkTest {
    // One store, two TraciveOAuth instances. The platform store is what actually spans a process
    // death; InMemoryTokenStore stands in for it, holding its contents across the two objects.
    val store = InMemoryTokenStore()
    val launcher = RecordingAuthorizeLauncher()
    val doomed = oauthUnderTest(launcher = launcher, tokenStore = store)

    // Started, then abandoned without cancelling. A cancel is a terminal outcome and would erase
    // the record; a process death runs no cleanup at all, which is what this simulates. Nothing
    // below ever resumes this coroutine.
    @Suppress("DeferredResultUnused")
    async(start = CoroutineStart.UNDISPATCHED) { doomed.authorize() }
    val authorizeUrl = launcher.authorizeUrls.single()
    val parameters = TraciveOAuth.parseQueryParameters(authorizeUrl)

    val recovered = assertNotNull(store.loadPending(), "the authorization was never persisted")
    assertEquals(parameters[AuthorizeUrlBuilder.PARAM_STATE], recovered.state)
    assertEquals(REDIRECT_URI, recovered.redirectUri)
    assertEquals(FixedClock.DEFAULT, recovered.createdAt)
    assertEquals(
      parameters["code_challenge"],
      PkceGenerator.challengeFor(recovered.codeVerifier),
      "the persisted verifier does not match the challenge that was sent",
    )
    // The record is the one thing that must never be readable from a log line.
    assertFalse(
      recovered.toString().contains(recovered.codeVerifier),
      "toString leaked the verifier",
    )
    assertFalse(recovered.toString().contains(recovered.state), "toString leaked the state")

    // A new process, over the same store. The record is still there and still unspent.
    val relaunched = oauthUnderTest(launcher = RecordingAuthorizeLauncher(), tokenStore = store)
    assertNotNull(store.loadPending(), "building a new instance consumed the stored record")

    assertFalse(
      relaunched.handleCallback("$REDIRECT_URI?code=abc&state=${recovered.state}"),
      "handleCallback claimed a redirect the new process has no continuation to resume",
    )
    assertNotNull(store.loadPending(), "a callback that resolved nothing still spent the record")
  }

  // --------------------------------------------------------------------- expiry ----

  /**
   * The window is ten minutes, and this test can tell ten from any other number.
   *
   * Both halves are needed and both are one second from the edge: a test that only advanced the
   * clock by some comfortably large amount would pass at ten minutes, at thirty, and at two hours,
   * and would therefore assert nothing about the window at all. Nine minutes fifty-nine seconds
   * must still complete; ten minutes one second must not.
   */
  @Test
  fun theWindowIsTenMinutesAndBothSidesOfItAreChecked() = runSdkTest {
    val insideTransport = FakeHttpTransport { jsonResponse(tokenJson()) }
    val inside = startFlow(transport = insideTransport)
    inside.clock.advance(10.minutes - 1.seconds)
    assertTrue(inside.deliverCode("still-good"))
    assertIs<AuthResult.Authorized>(
      inside.outcome.await(),
      "a callback one second inside the window was refused",
    )

    val outsideTransport = FakeHttpTransport { jsonResponse(tokenJson()) }
    val outside = startFlow(transport = outsideTransport)
    outside.clock.advance(10.minutes + 1.seconds)
    assertTrue(outside.deliverCode("long-dead"))
    // Asserted before the result is inspected, because this is the sentence that names the bug: a
    // window wider than ten minutes shows up here first, as a dead code going out on the wire.
    assertTrue(
      outsideTransport.requests.isEmpty(),
      "a callback one second outside the window was exchanged",
    )
    val refused = assertIs<AuthResult.Failed>(outside.outcome.await())
    assertIs<TraciveException.InvalidRequest>(refused.error)
    assertNull(outside.store.load(), "an expired attempt stored a grant")
  }

  /**
   * The default is ten minutes, spelled out rather than read from the constant.
   *
   * Ten is the authorization code's own server-side lifetime: past it the code the callback carries
   * is already dead, so holding the verifier longer buys no recovery and only leaves a secret on
   * disk. It is also the value the React Native SDK defaults to, as 600 seconds. Renaming or
   * re-tuning the constant has to fail here rather than quietly move a cross-SDK number.
   */
  @Test
  fun theDefaultWindowIsTenMinutes() {
    assertEquals(10.minutes, TraciveOAuth.DEFAULT_PENDING_AUTHORIZATION_MAX_AGE)
  }

  /** The window is a constructor parameter, as it is in the Flutter and React Native SDKs. */
  @Test
  fun theWindowCanBeNarrowedByTheHost() = runSdkTest {
    val transport = FakeHttpTransport { fail("a dead authorization code reached the server") }
    val flow = startFlow(transport = transport, maxAge = 30.seconds)

    flow.clock.advance(31.seconds)
    assertTrue(flow.deliverCode("just-too-late"))

    assertIs<AuthResult.Failed>(flow.outcome.await(), "the narrowed window was not applied")
    assertNull(flow.store.loadPending(), "an expired attempt was left on disk")
  }

  // ------------------------------------------------------------------- clearing ----

  /**
   * Every way an authorization can end erases the record. All of them, in one test, on purpose.
   *
   * "A spent attempt cannot be replayed" is a single property, and a suite that covered four of the
   * six exits would read as though it held. What is left behind is a live PKCE verifier next to the
   * `state` it answers to, which is exactly the pair a later redirect would need.
   */
  @Test
  fun everyTerminalOutcomeErasesTheRecord() = runSdkTest {
    val authorized = startFlow(transport = FakeHttpTransport { jsonResponse(tokenJson()) })
    authorized.deliverCode("auth-code")
    assertIs<AuthResult.Authorized>(authorized.outcome.await())
    assertNull(authorized.store.loadPending(), "a completed authorization was left on disk")

    val denied = startFlow()
    assertTrue(denied.deliver("error=access_denied"))
    assertEquals(AuthResult.Denied, denied.outcome.await())
    assertNull(denied.store.loadPending(), "a declined authorization was left on disk")

    val mismatched = startFlow()
    assertTrue(mismatched.oauth.handleCallback("$REDIRECT_URI?code=stolen&state=not-the-state"))
    assertIs<AuthResult.Failed>(mismatched.outcome.await())
    assertNull(mismatched.store.loadPending(), "a mismatched callback left the verifier on disk")

    val expired = startFlow()
    expired.clock.advance(11.minutes)
    assertTrue(expired.deliverCode("long-dead"))
    assertIs<AuthResult.Failed>(expired.outcome.await())
    assertNull(expired.store.loadPending(), "an expired attempt was left on disk")

    val cancelled = startFlow()
    assertTrue(cancelled.oauth.cancelPendingAuthorization())
    assertEquals(AuthResult.Denied, cancelled.outcome.await())
    assertNull(cancelled.store.loadPending(), "a cancelled authorization was left on disk")

    val failedExchange =
      startFlow(
        transport = FakeHttpTransport { HttpResponse(400, "{\"error\":\"invalid_grant\"}") }
      )
    failedExchange.deliverCode("spent")
    assertIs<AuthResult.Failed>(failedExchange.outcome.await())
    assertNull(failedExchange.store.loadPending(), "a failed exchange left the verifier on disk")

    val confidential =
      startFlow(
        transport =
          FakeHttpTransport { fail("a confidential client exchanges on its own backend") },
        clientType = ClientType.CONFIDENTIAL,
      )
    confidential.deliverCode("handed-back")
    assertIs<AuthResult.CodeReceived>(confidential.outcome.await())
    assertNull(confidential.store.loadPending(), "a handed-back code left the verifier on disk")
  }

  /**
   * A browser that will not open leaves nothing behind.
   *
   * The record is written first, so this is the one path where it exists and the flow never starts.
   * Rolling it back matters: a redirect arriving afterwards — from a stray link, or from a browser
   * that opened after all — would otherwise find a record to match.
   */
  @Test
  fun aBrowserThatRefusesToOpenRollsTheRecordBack() = runSdkTest {
    val store = InMemoryTokenStore()
    val oauth = oauthUnderTest(launcher = RefusingLauncher(), tokenStore = store)

    assertIs<AuthResult.Failed>(oauth.authorize())

    assertNull(store.loadPending(), "a flow that never opened left a verifier on disk")
    assertNull(oauth.pendingAuthorizationId)
  }

  /**
   * A superseding `authorize()` owns the record, and the call it replaced must not delete it.
   *
   * The superseded call resolves immediately and runs the same cleanup as any other terminal
   * outcome. Without a guard that cleanup erases the record the *newer* call has just written — and
   * the flow the user is actually looking at becomes unrecoverable, silently, on nothing worse than
   * a double tap.
   */
  @Test
  fun supersedingAnAuthorizationLeavesTheNewRecordInPlace() = runSdkTest {
    val store = InMemoryTokenStore()
    val launcher = RecordingAuthorizeLauncher()
    val oauth =
      oauthUnderTest(
        transport = FakeHttpTransport { jsonResponse(tokenJson()) },
        launcher = launcher,
        tokenStore = store,
      )

    val first = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }
    val second = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }

    assertIs<AuthResult.Failed>(first.await(), "the first call should have been superseded")

    val live = assertNotNull(store.loadPending(), "the superseded call erased the newer record")
    val secondState =
      TraciveOAuth.parseQueryParameters(launcher.authorizeUrls.last())[
          AuthorizeUrlBuilder.PARAM_STATE]
    assertEquals(secondState, live.state, "the record on disk belongs to the superseded call")

    assertTrue(oauth.handleCallback("$REDIRECT_URI?code=abc&state=$secondState"))
    assertIs<AuthResult.Authorized>(second.await())
    assertNull(store.loadPending())
  }

  /** Sign-out means nothing of the user's stays here, a half-finished authorization included. */
  @Test
  fun signOutErasesAPendingAuthorizationToo() = runSdkTest {
    val flow = startFlow(transport = FakeHttpTransport { HttpResponse(200, "") })
    assertNotNull(flow.store.loadPending())

    flow.oauth.signOut()

    assertNull(flow.store.loadPending(), "sign-out left a pending authorization on the device")
    assertEquals(AuthResult.Denied, flow.outcome.await())
  }

  // --------------------------------------------------------- storage that fails ----

  /**
   * A store that cannot hold the record does not stop the authorization.
   *
   * Only the ability to survive a process death is lost, and the in-memory attempt still resolves
   * this call — so refusing here would trade a working flow for a failure. It would also be
   * unworkable for a confidential client, whose backend holds the tokens and whose store may
   * legitimately keep nothing at all.
   */
  @Test
  fun anUnwritablePendingRecordDoesNotBreakTheFlow() = runSdkTest {
    val launcher = RecordingAuthorizeLauncher()
    val oauth =
      oauthUnderTest(
        transport = FakeHttpTransport { jsonResponse(tokenJson()) },
        launcher = launcher,
        tokenStore = PendingRefusingTokenStore(),
      )

    val outcome = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }
    val state =
      TraciveOAuth.parseQueryParameters(launcher.authorizeUrls.single())[
          AuthorizeUrlBuilder.PARAM_STATE]
    assertTrue(oauth.handleCallback("$REDIRECT_URI?code=abc&state=$state"))

    assertIs<AuthResult.Authorized>(
      outcome.await(),
      "a pending-record write failure aborted an otherwise fine authorization",
    )
  }

  // ------------------------------------------------------------------ the codec ----

  @Test
  fun aPendingRecordSurvivesEncodingUnchanged() {
    val original =
      PendingAuthorization(
        state = "state-1",
        codeVerifier = "verifier-1",
        redirectUri = REDIRECT_URI,
        createdAt = FixedClock.DEFAULT,
      )

    val decoded =
      assertNotNull(PendingAuthorizationCodec.decode(PendingAuthorizationCodec.encode(original)))

    assertEquals(original.state, decoded.state)
    assertEquals(original.codeVerifier, decoded.codeVerifier)
    assertEquals(original.redirectUri, decoded.redirectUri)
    assertEquals(original.createdAt, decoded.createdAt)
  }

  /**
   * An unreadable blob reads as "nothing pending", never as half a record.
   *
   * Half a PKCE pair completes nothing, and an exception on a cold start is worse than a retry: the
   * user can act on "authorize again" and cannot act on a decode failure.
   */
  @Test
  fun anUnreadablePendingBlobIsTreatedAsAbsent() {
    assertNull(PendingAuthorizationCodec.decode(""))
    assertNull(PendingAuthorizationCodec.decode("not json at all"))
    assertNull(
      PendingAuthorizationCodec.decode(
        "{\"v\":999,\"st\":\"s\",\"cv\":\"v\",\"ru\":\"r\",\"ts\":1}"
      )
    )
    assertNull(
      PendingAuthorizationCodec.decode("{\"v\":1,\"st\":\"\",\"cv\":\"v\",\"ru\":\"r\",\"ts\":1}"),
      "a record with no state was accepted",
    )
    assertNull(
      PendingAuthorizationCodec.decode("{\"v\":1,\"st\":\"s\",\"cv\":\"\",\"ru\":\"r\",\"ts\":1}"),
      "a record with no verifier was accepted",
    )
  }

  // ---------------------------------------------------------------- internals ----

  /** One started authorization, plus everything a test needs to steer and inspect it. */
  private class StartedFlow(
    val oauth: TraciveOAuth,
    val outcome: Deferred<AuthResult>,
    val state: String,
    val store: InMemoryTokenStore,
    val clock: FixedClock,
  ) {
    /** Delivers a redirect carrying [query] plus this flow's own `state`. */
    fun deliver(query: String): Boolean = oauth.handleCallback("$REDIRECT_URI?$query&state=$state")

    fun deliverCode(code: String): Boolean = deliver("code=$code")
  }

  private fun CoroutineScope.startFlow(
    transport: FakeHttpTransport = FakeHttpTransport { fail("no request expected") },
    maxAge: Duration = TraciveOAuth.DEFAULT_PENDING_AUTHORIZATION_MAX_AGE,
    clientType: ClientType = ClientType.PUBLIC,
  ): StartedFlow {
    val clock = FixedClock()
    val store = InMemoryTokenStore()
    val launcher = RecordingAuthorizeLauncher()
    val oauth =
      oauthUnderTest(
        transport = transport,
        launcher = launcher,
        tokenStore = store,
        clock = clock,
        maxAge = maxAge,
        clientType = clientType,
      )
    val outcome = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }
    val state =
      TraciveOAuth.parseQueryParameters(launcher.authorizeUrls.single())[
          AuthorizeUrlBuilder.PARAM_STATE] ?: fail("the authorize URL carried no state")
    return StartedFlow(oauth, outcome, state, store, clock)
  }

  private fun oauthUnderTest(
    transport: FakeHttpTransport = FakeHttpTransport { fail("no request expected") },
    launcher: AuthorizeLauncher = RecordingAuthorizeLauncher(),
    tokenStore: TokenStore = InMemoryTokenStore(),
    clock: FixedClock = FixedClock(),
    maxAge: Duration = TraciveOAuth.DEFAULT_PENDING_AUTHORIZATION_MAX_AGE,
    clientType: ClientType = ClientType.PUBLIC,
  ): TraciveOAuth =
    TraciveOAuth(
      mode = testOAuthMode(redirectUri = REDIRECT_URI, clientType = clientType),
      environment = TraciveEnvironment.Custom(TEST_BASE_URL),
      transport = transport,
      tokenStore = tokenStore,
      appPresenceChecker = FakeAppPresenceChecker(installed = true),
      authorizeLauncher = launcher,
      clock = clock,
      pendingAuthorizationMaxAge = maxAge,
    )

  /** Reads the store at the moment the browser would open, so the write order can be asserted. */
  private class StoreInspectingLauncher(private val store: TokenStore) : AuthorizeLauncher {

    val authorizeUrls: MutableList<String> = mutableListOf()

    /** What was on disk when `launchAuthorize` was called. Null means nothing was. */
    var pendingAtLaunch: PendingAuthorization? = null
      private set

    override suspend fun launchAuthorize(url: String) {
      pendingAtLaunch = store.loadPending()
      authorizeUrls += url
    }

    override suspend fun launchInstall(url: String) = Unit
  }

  /** A device with no browser at all — `startActivity` throwing `ActivityNotFoundException`. */
  private class RefusingLauncher : AuthorizeLauncher {
    override suspend fun launchAuthorize(url: String): Unit =
      throw IllegalStateException("no browser")

    override suspend fun launchInstall(url: String) = Unit
  }

  /**
   * A store whose tokens work but whose pending slot is unwritable, as a locked Keychain would be.
   */
  private class PendingRefusingTokenStore : TokenStore {
    private var tokens: TokenSet? = null

    override suspend fun load(): TokenSet? = tokens

    override suspend fun save(tokens: TokenSet) {
      this.tokens = tokens
    }

    override suspend fun clear() {
      tokens = null
    }

    override suspend fun loadPending(): PendingAuthorization? = null

    override suspend fun savePending(pending: PendingAuthorization?): Unit =
      throw TraciveException.TokenStorageFailed(platformStatus = -25308)
  }

  private fun fail(message: String): Nothing = throw AssertionError(message)

  private companion object {
    const val REDIRECT_URI = "https://partner.test/tracive/callback"
  }
}
