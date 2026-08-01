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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async

/**
 * The pending authorization: where it lives, how long it lives, when it dies, and what it rescues.
 *
 * Consent happens in the Tracive app, so the host process is in the background for the whole of it
 * and on Android is an ordinary candidate for being killed. The PKCE verifier is the half of the
 * pair that never leaves the device, so a verifier held only in a field dies with the process, and
 * the returning callback then names a `state` nothing can match. Every test here follows from that.
 *
 * The positive assertion is that a redirect arriving in a **new process** completes the
 * authorization from the stored record, which is the only reason the record is worth writing.
 *
 * Three of the assertions are negative and all of them cover silent failures:
 * - a spent or abandoned attempt **must not** be left on disk for a later redirect to replay,
 * - an attempt older than the authorization code it is waiting for **must not** be exchanged,
 *   because that round trip can only come back `invalid_grant` — with the user watching, and
 * - a redirect this SDK did not ask for **must not** be able to end one it did. Any app on the
 *   device can send a URL into `handleCallback`, and a consent the user is still giving has to
 *   survive that.
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

    assertNull(oauth.authorize(), "the authorization page was not opened")

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
  }

  /**
   * A redirect reaching a process that never started the flow completes it anyway.
   *
   * This is the whole reason the record is written down. The first half checks what survives: a new
   * process finds `state`, the verifier and the redirect URI exactly as they were sent, so nothing
   * the exchange needs has been lost. The second half checks that it is actually *used* — the code
   * goes out with the recovered verifier, tokens come back, and the caller is handed
   * `AuthResult.Authorized` from `handleCallback` rather than from the `authorize()` call that no
   * longer exists.
   *
   * This assertion used to say the opposite, because `authorize()` suspended until its redirect
   * arrived and a suspended call does not survive the process that owns it. Delivering the outcome
   * from `handleCallback` is what removed that limit; if this test ever goes back to expecting a
   * refusal, the recovery went with it.
   */
  @Test
  fun aRedirectDeliveredInANewProcessCompletesTheAuthorization() = runSdkTest {
    // One store, two TraciveOAuth instances. The platform store is what actually spans a process
    // death; InMemoryTokenStore stands in for it, holding its contents across the two objects.
    val store = InMemoryTokenStore()
    val launcher = RecordingAuthorizeLauncher()
    val doomed = oauthUnderTest(launcher = launcher, tokenStore = store)

    // Started, then abandoned without cancelling. A cancel is a terminal outcome and would erase
    // the record; a process death runs no cleanup at all, which is what this simulates.
    assertNull(doomed.authorize())
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
    val transport = FakeHttpTransport { jsonResponse(tokenJson()) }
    val relaunched =
      oauthUnderTest(
        transport = transport,
        launcher = RecordingAuthorizeLauncher(),
        tokenStore = store,
      )
    assertNotNull(store.loadPending(), "building a new instance consumed the stored record")

    val outcome = relaunched.handleCallback("$REDIRECT_URI?code=abc&state=${recovered.state}")

    assertIs<AuthResult.Authorized>(
      outcome,
      "a redirect arriving in a new process did not complete the authorization",
    )
    // The proof that the *recovered* verifier is what redeemed the code, rather than a fresh one
    // this instance generated: only the record on disk knew it.
    val body = assertNotNull(transport.requestsTo("/oauth/token").single().body)
    assertTrue(
      body.contains("code_verifier=${recovered.codeVerifier}"),
      "the exchange did not send the verifier the previous process persisted",
    )
    assertNotNull(store.load(), "the recovered authorization stored no grant")
    assertNull(store.loadPending(), "a completed authorization was left on disk")
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
    assertIs<AuthResult.Authorized>(
      inside.deliverCode("still-good"),
      "a callback one second inside the window was refused",
    )

    val outsideTransport = FakeHttpTransport { jsonResponse(tokenJson()) }
    val outside = startFlow(transport = outsideTransport)
    outside.clock.advance(10.minutes + 1.seconds)
    val outcome = outside.deliverCode("long-dead")
    // Asserted before the result is inspected, because this is the sentence that names the bug: a
    // window wider than ten minutes shows up here first, as a dead code going out on the wire.
    assertTrue(
      outsideTransport.requests.isEmpty(),
      "a callback one second outside the window was exchanged",
    )
    val refused = assertIs<AuthResult.Failed>(outcome)
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

    assertIs<AuthResult.Failed>(
      flow.deliverCode("just-too-late"),
      "the narrowed window was not applied",
    )
    assertNull(flow.store.loadPending(), "an expired attempt was left on disk")
  }

  // ------------------------------------------------------------------- clearing ----

  /**
   * Every way an authorization is *consumed* erases the record. All of them, in one test, on
   * purpose.
   *
   * "A spent attempt cannot be replayed" is a single property, and a suite that covered four of the
   * six exits would read as though it held. What is left behind is a live PKCE verifier next to the
   * `state` it answers to, which is exactly the pair a later redirect would need.
   *
   * Each case below is an outcome of the attempt the user actually started: it succeeded, they
   * declined it, it ran out of time, the host abandoned it, or its single-use code has been spent.
   * A `state` mismatch is none of those — it is a *different* URL arriving while the attempt is
   * still live — so it is not here; see [aForgedRedirectIsRefusedAndTheGenuineOneStillCompletes].
   */
  @Test
  fun everyOutcomeThatConsumesTheAttemptErasesTheRecord() = runSdkTest {
    val authorized = startFlow(transport = FakeHttpTransport { jsonResponse(tokenJson()) })
    assertIs<AuthResult.Authorized>(authorized.deliverCode("auth-code"))
    assertNull(authorized.store.loadPending(), "a completed authorization was left on disk")

    val denied = startFlow()
    assertEquals(AuthResult.Denied, denied.deliver("error=access_denied"))
    assertNull(denied.store.loadPending(), "a declined authorization was left on disk")

    val expired = startFlow()
    expired.clock.advance(11.minutes)
    assertIs<AuthResult.Failed>(expired.deliverCode("long-dead"))
    assertNull(expired.store.loadPending(), "an expired attempt was left on disk")

    val cancelled = startFlow()
    assertTrue(cancelled.oauth.cancelPendingAuthorization())
    assertNull(cancelled.store.loadPending(), "a cancelled authorization was left on disk")

    val failedExchange =
      startFlow(
        transport = FakeHttpTransport { HttpResponse(400, "{\"error\":\"invalid_grant\"}") }
      )
    assertIs<AuthResult.Failed>(failedExchange.deliverCode("spent"))
    assertNull(failedExchange.store.loadPending(), "a failed exchange left the verifier on disk")

    val confidential =
      startFlow(
        transport =
          FakeHttpTransport { fail("a confidential client exchanges on its own backend") },
        clientType = ClientType.CONFIDENTIAL,
      )
    assertIs<AuthResult.CodeReceived>(confidential.deliverCode("handed-back"))
    assertNull(confidential.store.loadPending(), "a handed-back code left the verifier on disk")
  }

  /**
   * A forged redirect is refused, and the authorization it interrupted still completes.
   *
   * Any app on the device can hand this one a URL — a private-use redirect scheme is one any
   * installed app may declare, and even an App Link is only a link. Erasing the record on a `state`
   * that does not match therefore handed whichever app sent it a one-call cancel button: the user
   * is still inside the Tracive app consenting, and the redirect they eventually come back with
   * finds nothing left to complete. Nothing anywhere reports it, because from this SDK's side the
   * forged URL was refused exactly as it should have been.
   *
   * The objection this replaces is that a forged redirect must not leave a live verifier parked on
   * the device, and it is a real concern that resolves the other way. The forgery does not make the
   * verifier sit there any *longer*: it was already going to, for the ten minutes the user has to
   * finish consenting, and the expiry still ends it on time. What the erase bought was not a
   * shorter window, only the cancel button.
   *
   * The second half of this test is the point. That the record survives proves nothing on its own —
   * what says the user can still finish is that the genuine redirect afterwards is answered with
   * `AuthResult.Authorized`.
   */
  @Test
  fun aForgedRedirectIsRefusedAndTheGenuineOneStillCompletes() = runSdkTest {
    val flow = startFlow(transport = FakeHttpTransport { jsonResponse(tokenJson()) })

    val forged =
      assertIs<AuthResult.Failed>(
        flow.oauth.handleCallback("$REDIRECT_URI?code=stolen&state=not-the-state"),
        "a redirect on this client's own URI carrying a foreign state was not answered",
      )
    assertIs<TraciveException.InvalidRequest>(forged.error)
    assertNotNull(
      flow.store.loadPending(),
      "a forged redirect erased the attempt the user is still consenting to",
    )

    assertIs<AuthResult.Authorized>(
      flow.deliverCode("the-genuine-one"),
      "the user's own redirect could no longer complete the authorization",
    )
    assertNull(flow.store.loadPending(), "a completed authorization was left on disk")
  }

  /**
   * The same protection when the record on disk is all that is left.
   *
   * This is the case with the most to lose and its own code path: the host process was killed while
   * the user was consenting, so nothing is held in memory and `handleCallback` answers entirely
   * from the store. A forged redirect erasing the record there destroys the only copy of the
   * verifier there is, and the flow becomes unrecoverable rather than merely interrupted.
   */
  @Test
  fun aForgedRedirectDoesNotEraseARecoveredRecordEither() = runSdkTest {
    val store = InMemoryTokenStore()
    val launcher = RecordingAuthorizeLauncher()
    val doomed = oauthUnderTest(launcher = launcher, tokenStore = store)

    assertNull(doomed.authorize())
    val state =
      TraciveOAuth.parseQueryParameters(launcher.authorizeUrls.single())[
          AuthorizeUrlBuilder.PARAM_STATE]

    // A new process over the same store, holding no attempt of its own.
    val relaunched =
      oauthUnderTest(
        transport = FakeHttpTransport { jsonResponse(tokenJson()) },
        launcher = RecordingAuthorizeLauncher(),
        tokenStore = store,
      )

    assertIs<AuthResult.Failed>(
      relaunched.handleCallback("$REDIRECT_URI?code=stolen&state=not-the-state"),
      "a redirect on this client's own URI carrying a foreign state was not answered",
    )
    assertNotNull(store.loadPending(), "a forged redirect erased the only copy of the verifier")

    assertIs<AuthResult.Authorized>(
      relaunched.handleCallback("$REDIRECT_URI?code=abc&state=$state"),
      "the recovered authorization could no longer be completed",
    )
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
   * A rolled-back authorization must not delete the record that replaced it.
   *
   * Two authorizations overlap — a double tap is enough — and the first one's browser then refuses
   * to open. Its rollback runs the same cleanup as any other terminal outcome, and without an
   * ownership guard it erases the record the *second* call has already written. The flow the user
   * is actually looking at would then be unrecoverable after a process death, silently, with
   * nothing anywhere reporting it.
   *
   * The interleaving is produced rather than hoped for: `launchAuthorize` is a suspending seam, so
   * the launcher parks the first call inside it, the second call runs to completion, and only then
   * is the first one failed. Nothing here depends on timing.
   */
  @Test
  fun aRolledBackAuthorizationLeavesTheRecordThatReplacedItAlone() = runSdkTest {
    val store = InMemoryTokenStore()
    val launcher = ParkingLauncher()
    val oauth = oauthUnderTest(launcher = launcher, tokenStore = store)

    val first = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }
    assertEquals(1, launcher.authorizeUrls.size, "the first call is not parked in the launcher")

    assertNull(oauth.authorize(), "the second authorization did not open its page")
    val secondState =
      TraciveOAuth.parseQueryParameters(launcher.authorizeUrls.last())[
          AuthorizeUrlBuilder.PARAM_STATE]
    assertEquals(
      secondState,
      assertNotNull(store.loadPending()).state,
      "the newer call did not take over the record on disk",
    )

    launcher.refuseTheParkedBrowser()

    assertIs<AuthResult.Failed>(
      first.await(),
      "a browser that would not open did not fail its call",
    )
    val survivor =
      assertNotNull(store.loadPending(), "the rolled-back call erased the newer call's record")
    assertEquals(secondState, survivor.state, "the record on disk is no longer the newer call's")
  }

  /** Sign-out means nothing of the user's stays here, a half-finished authorization included. */
  @Test
  fun signOutErasesAPendingAuthorizationToo() = runSdkTest {
    val flow = startFlow(transport = FakeHttpTransport { HttpResponse(200, "") })
    assertNotNull(flow.store.loadPending())

    flow.oauth.signOut()

    assertNull(flow.store.loadPending(), "sign-out left a pending authorization on the device")
    assertIs<AuthResult.Failed>(
      flow.deliverCode("too-late"),
      "a redirect arriving after sign-out was still honoured",
    )
  }

  // --------------------------------------------------------- storage that fails ----

  /**
   * A store that cannot hold the record does not stop the authorization.
   *
   * Only the ability to survive a process death is lost: a callback reaching the process that
   * started the flow is completed from the attempt held in memory, so refusing here would trade a
   * working flow for a failure. It would also be unworkable for a confidential client, whose
   * backend holds the tokens and whose store may legitimately keep nothing at all.
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

    assertNull(oauth.authorize())
    val state =
      TraciveOAuth.parseQueryParameters(launcher.authorizeUrls.single())[
          AuthorizeUrlBuilder.PARAM_STATE]

    assertIs<AuthResult.Authorized>(
      oauth.handleCallback("$REDIRECT_URI?code=abc&state=$state"),
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
    val state: String,
    val store: InMemoryTokenStore,
    val clock: FixedClock,
  ) {
    /** Delivers a redirect carrying [query] plus this flow's own `state`. */
    suspend fun deliver(query: String): AuthResult? =
      oauth.handleCallback("$REDIRECT_URI?$query&state=$state")

    suspend fun deliverCode(code: String): AuthResult? = deliver("code=$code")
  }

  private suspend fun startFlow(
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
    if (oauth.authorize() != null) fail("the authorization page was not opened")
    val state =
      TraciveOAuth.parseQueryParameters(launcher.authorizeUrls.single())[
          AuthorizeUrlBuilder.PARAM_STATE] ?: fail("the authorize URL carried no state")
    return StartedFlow(oauth, state, store, clock)
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
   * Holds the *first* `launchAuthorize` open until the test releases it, then fails it.
   *
   * The seam is real rather than contrived: launching the browser is a suspending call on both
   * platforms, so a second `authorize()` genuinely can run while the first is still inside it.
   * Parking there is what makes the ordering deterministic instead of a race the test hopes to win.
   */
  private class ParkingLauncher : AuthorizeLauncher {

    val authorizeUrls: MutableList<String> = mutableListOf()

    private val released = CompletableDeferred<Unit>()

    override suspend fun launchAuthorize(url: String) {
      authorizeUrls += url
      if (authorizeUrls.size == 1) {
        released.await()
        throw IllegalStateException("no browser")
      }
    }

    override suspend fun launchInstall(url: String) = Unit

    /** Resumes the parked call and gives it the failure a device with no browser would. */
    fun refuseTheParkedBrowser() {
      released.complete(Unit)
    }
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
