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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * The authorization flow, end to end, with no browser and no server.
 *
 * The two assertions that matter most here are negative ones, because both failures are silent:
 * - a callback whose `state` does not match **must not** have its code redeemed, and
 * - a device without the Tracive app **must not** be sent an authorize URL.
 *
 * A `state` mismatch means the callback came from somewhere other than the request this SDK
 * started. Redeeming its code anyway is login-CSRF: the partner app ends up operating on the
 * attacker's Tracive account, and nothing in the UI would look wrong. Opening an authorize URL that
 * nothing claims drops the user on a web page asking for Tracive credentials, which trains exactly
 * the habit the app-to-app flow exists to prevent.
 */
class TraciveOAuthTest {

  // ------------------------------------------------------- app not installed ----

  @Test
  fun aDeviceWithoutTheAppGetsACleanInstallLinkAndNothingElse() = runSdkTest {
    val transport = FakeHttpTransport { fail("nothing may be sent before the app is installed") }
    val launcher = RecordingAuthorizeLauncher()
    val oauth = oauthUnderTest(transport, launcher, installed = false)

    val result = oauth.authorize()

    assertEquals(AuthResult.AppNotInstalled, result)
    assertTrue(launcher.authorizeUrls.isEmpty(), "the flow started without the Tracive app")
    assertTrue(transport.requests.isEmpty(), "an uninstalled device reached the network")

    // The link is the one every Tracive SDK opens, carrying only the two agreed non-sensitive tags:
    // the partner's client id, and which SDK opened it. Written out rather than built from the
    // constants, so that changing a constant fails here instead of quietly redefining the contract.
    val installUrl = launcher.installUrls.single()
    assertTrue(
      installUrl.startsWith("https://tracive.chottu.link/install?"),
      "the install link left the agreed base URL: $installUrl",
    )
    val installParams = TraciveOAuth.parseQueryParameters(installUrl)
    assertEquals("cli_test", installParams["ref"], "the client id is the attribution tag")
    assertEquals("tracive-sdk-kotlin", installParams["utm_source"])
    assertEquals(setOf("ref", "utm_source"), installParams.keys, "an extra parameter appeared")

    // The hard invariant: no OAuth parameter at all. They would be stale by the time the user
    // returned — the server-side request expires in minutes, and nothing is resumed — and this
    // link transits a third-party link service, so whatever rides on it is handed to someone
    // else's servers.
    for (parameter in
      listOf(
        "code_challenge",
        "code_challenge_method",
        "code_verifier",
        "state",
        "redirect_uri",
        "scope",
        "partner_user_id",
      )) {
      assertFalse(
        installUrl.contains(parameter),
        "the install link carried $parameter: $installUrl",
      )
    }
  }

  // ------------------------------------------------------ the install override ----

  /**
   * A blank override is a configuration mistake, and it has to be refused where it is loudest.
   *
   * Silently substituting the default was the worst of the available behaviours, because the link
   * it produced *worked*: a partner whose config pipeline yielded an empty string shipped a build
   * that sent their users to Tracive's own install page, and nothing in anyone's logs said so. A
   * constructor throw runs once, in the host app's own code, on every run, long before an end user
   * is involved.
   *
   * The reason token is spelled out here rather than read from a constant. It is a contract shared
   * with the other Tracive SDKs, so renaming the constant has to fail this test instead of quietly
   * redefining the token.
   */
  @Test
  fun aBlankInstallUrlOverrideIsRefusedAtConstruction() {
    for (blank in listOf("", " ", "   ", "\t", "\n", " \t\n ")) {
      val failure =
        assertFailsWith<IllegalArgumentException>("a blank override was accepted: <$blank>") {
          oauthUnderTest(installUrlOverride = blank)
        }
      assertTrue(
        failure.message.orEmpty().contains("blank_install_url"),
        "the reason token is a cross-SDK contract: ${failure.message}",
      )
    }
  }

  /** Absence is a real answer, and it keeps its old meaning: use the default link. */
  @Test
  fun anAbsentInstallUrlOverrideStillGetsTheDefault() {
    val oauth = oauthUnderTest(installUrlOverride = null)

    assertEquals(TraciveOAuth.defaultInstallUrl("cli_test"), oauth.installUrl)
    assertTrue(
      oauth.installUrl.startsWith("https://tracive.chottu.link/install?"),
      oauth.installUrl,
    )
  }

  /**
   * Anything non-blank is passed through untouched — no trimming, no repair, no vetting.
   *
   * These three values are all things the SDK could plausibly have "fixed", and it deliberately
   * fixes none of them: the surrounding spaces stay, the private-use scheme stays, and the third is
   * the schemeless query fragment a blank override produced in a sibling SDK when the blank was let
   * through instead of refused. Vetting a partner's link is not this SDK's job; the only thing it
   * refuses is the one value that carries no intent at all.
   */
  @Test
  fun aNonBlankInstallUrlOverrideSurvivesVerbatim() = runSdkTest {
    for (odd in
      listOf(
        "  https://partner.example/get-the-app  ",
        "partner-app://install",
        "?ref=cli_test&utm_source=tracive-sdk-kotlin",
      )) {
      val launcher = RecordingAuthorizeLauncher()
      val oauth =
        oauthUnderTest(
          transport = FakeHttpTransport { fail("nothing may be sent before the app is installed") },
          launcher = launcher,
          installed = false,
          installUrlOverride = odd,
        )

      assertEquals(odd, oauth.installUrl, "the override was rewritten")
      assertEquals(AuthResult.AppNotInstalled, oauth.authorize())
      assertEquals(odd, launcher.installUrls.single(), "the launcher was handed a rewritten link")
    }
  }

  // --------------------------------------------------------- the authorize URL ----

  @Test
  fun theAuthorizeUrlIsS256AndCarriesNoSecret() = runSdkTest {
    val launcher = RecordingAuthorizeLauncher()
    val oauth = oauthUnderTest(FakeHttpTransport { fail("no request yet") }, launcher)

    val job = launch(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }
    val parameters = TraciveOAuth.parseQueryParameters(launcher.authorizeUrls.single())

    assertEquals("code", parameters["response_type"])
    assertEquals("cli_test", parameters["client_id"])
    assertEquals("https://partner.test/tracive/callback", parameters["redirect_uri"])
    assertEquals("scammer.check", parameters["scope"])
    assertEquals("S256", parameters["code_challenge_method"], "plain is never acceptable")
    assertTrue(parameters["code_challenge"]!!.isNotBlank())
    assertTrue(parameters["state"]!!.isNotBlank())

    // The verifier is the half that must stay on the device; sending it would defeat PKCE outright.
    val url = launcher.authorizeUrls.single()
    assertFalse(url.contains("code_verifier"), url)
    assertFalse(url.contains("client_secret"), url)
    assertFalse(url.contains("api_key"), url)

    oauth.cancelPendingAuthorization()
    job.join()
  }

  // ------------------------------------------------------------ state checking ----

  /** The security assertion of this whole file. */
  @Test
  fun aStateMismatchIsRejectedAndTheCodeIsNeverRedeemed() = runSdkTest {
    val transport = FakeHttpTransport { fail("a mismatched callback must never be redeemed") }
    val launcher = RecordingAuthorizeLauncher()
    val store = InMemoryTokenStore()
    val oauth = oauthUnderTest(transport, launcher, tokenStore = store)

    val outcome = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }
    val handled = oauth.handleCallback("$REDIRECT_URI?code=stolen-code&state=not-the-state")

    assertTrue(handled, "the callback belonged to this SDK and should have been consumed")
    val result = assertIs<AuthResult.Failed>(outcome.await())
    assertIs<TraciveException.InvalidRequest>(result.error)
    assertTrue(transport.requests.isEmpty(), "the SDK exchanged a code from a mismatched callback")
    assertNull(store.load(), "a mismatched callback stored a grant")
  }

  @Test
  fun aCallbackWithoutAStateIsRejectedToo() = runSdkTest {
    val transport = FakeHttpTransport { fail("no exchange may happen") }
    val launcher = RecordingAuthorizeLauncher()
    val oauth = oauthUnderTest(transport, launcher)

    val outcome = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }
    oauth.handleCallback("$REDIRECT_URI?code=abc")

    assertIs<AuthResult.Failed>(outcome.await())
    assertTrue(transport.requests.isEmpty())
  }

  /**
   * A duplicated parameter is a smuggling attempt: the SDK compares against the first `state` it
   * reads and never lets a later copy overwrite it.
   */
  @Test
  fun aDuplicatedStateParameterCannotOverrideTheFirst() = runSdkTest {
    val transport = FakeHttpTransport { fail("no exchange may happen") }
    val launcher = RecordingAuthorizeLauncher()
    val oauth = oauthUnderTest(transport, launcher)

    val outcome = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }
    val state = stateOf(launcher.authorizeUrls.single())
    oauth.handleCallback("$REDIRECT_URI?code=abc&state=forged&state=$state")

    assertIs<AuthResult.Failed>(outcome.await())
    assertTrue(transport.requests.isEmpty())
  }

  /**
   * A link that is not this client's redirect, or carries no OAuth parameters, must be left alone.
   *
   * Hosts are told to route every incoming deep link through `handleCallback`, and the sample's
   * redirect is a private-use scheme — which any other installed app may declare and fire. Treating
   * such a link as a failed callback let any app on the device abort a user's authorization with
   * one empty deep link, repeatedly and undetectably, and misfired benignly whenever an unrelated
   * link arrived while the browser was open.
   */
  @Test
  fun anUnrelatedDeepLinkLeavesAPendingAuthorizationAlone() = runSdkTest {
    val transport = FakeHttpTransport { jsonResponse(tokenJson()) }
    val launcher = RecordingAuthorizeLauncher()
    val oauth = oauthUnderTest(transport, launcher)

    val outcome = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }

    assertFalse(
      oauth.handleCallback("tracive-other://oauth/callback?code=abc&state=whatever"),
      "a link addressed elsewhere was claimed",
    )
    assertFalse(oauth.handleCallback(REDIRECT_URI), "our redirect with no query at all was claimed")
    assertFalse(
      oauth.handleCallback("$REDIRECT_URI?utm_source=newsletter"),
      "our redirect carrying no OAuth parameter was claimed",
    )

    // None of that disturbed the request, so the real redirect still resolves it.
    val state = stateOf(launcher.authorizeUrls.single())
    assertTrue(oauth.handleCallback("$REDIRECT_URI?code=abc&state=$state"))
    assertIs<AuthResult.Authorized>(outcome.await())
  }

  @Test
  fun aCallbackWithNothingPendingIsNotOurs() = runSdkTest {
    val oauth =
      oauthUnderTest(FakeHttpTransport { fail("no request") }, RecordingAuthorizeLauncher())

    assertFalse(
      oauth.handleCallback("$REDIRECT_URI?code=abc&state=whatever"),
      "a stray link was claimed by a flow that never started",
    )
  }

  // ----------------------------------------------------------------- outcomes ----

  @Test
  fun aPublicClientExchangesTheCodeAndStoresTheGrant() = runSdkTest {
    val transport = FakeHttpTransport {
      jsonResponse(tokenJson(scope = "scammer.check profile.read"))
    }
    val launcher = RecordingAuthorizeLauncher()
    val store = InMemoryTokenStore()
    val oauth = oauthUnderTest(transport, launcher, tokenStore = store)

    val outcome = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }
    val authorizeUrl = launcher.authorizeUrls.single()
    oauth.handleCallback("$REDIRECT_URI?code=auth-code-1&state=${stateOf(authorizeUrl)}")

    val result = assertIs<AuthResult.Authorized>(outcome.await())
    assertEquals(listOf("scammer.check", "profile.read"), result.grantedScopes)

    val exchange = transport.requestsTo("/oauth/token").single()
    assertEquals("application/x-www-form-urlencoded", exchange.contentType)
    val body = assertNotNull(exchange.body)
    assertTrue(body.contains("grant_type=authorization_code"), body)
    assertTrue(body.contains("code=auth-code-1"), body)
    assertTrue(body.contains("client_id=cli_test"), body)
    // The verifier proves this SDK started the request that produced the code.
    val challenge = TraciveOAuth.parseQueryParameters(authorizeUrl)["code_challenge"]
    val verifier = verifierFrom(body)
    assertEquals(challenge, PkceGenerator.challengeFor(verifier), "the PKCE pair did not match")
    // A public client has no secret, and the SDK has nowhere to put one.
    assertFalse(body.contains("client_secret"), body)
    assertFalse(body.contains("api_key"), body)

    val stored = assertNotNull(store.load())
    assertEquals("access-2", stored.accessToken)
    assertEquals("refresh-2", stored.refreshToken)
  }

  /**
   * A confidential client's backend holds the secret, so the SDK stops at the code. It must perform
   * no exchange and store nothing — anything else would mean the SDK held credentials it was
   * designed never to see.
   */
  @Test
  fun aConfidentialClientStopsAtTheCode() = runSdkTest {
    val transport = FakeHttpTransport { fail("a confidential client exchanges on its own backend") }
    val launcher = RecordingAuthorizeLauncher()
    val store = InMemoryTokenStore()
    val oauth =
      oauthUnderTest(transport, launcher, tokenStore = store, clientType = ClientType.CONFIDENTIAL)

    val outcome = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }
    val authorizeUrl = launcher.authorizeUrls.single()
    oauth.handleCallback("$REDIRECT_URI?code=auth-code-2&state=${stateOf(authorizeUrl)}")

    val result = assertIs<AuthResult.CodeReceived>(outcome.await())
    assertEquals("auth-code-2", result.code)
    assertEquals(REDIRECT_URI, result.redirectUri)
    assertEquals(
      TraciveOAuth.parseQueryParameters(authorizeUrl)["code_challenge"],
      PkceGenerator.challengeFor(result.codeVerifier),
      "the verifier handed back does not match the challenge that was sent",
    )
    assertTrue(transport.requests.isEmpty())
    assertNull(store.load())
    assertFalse(result.toString().contains("auth-code-2"), "a toString leaked the code")
  }

  @Test
  fun theUserDecliningIsNotAnError() = runSdkTest {
    val launcher = RecordingAuthorizeLauncher()
    val oauth = oauthUnderTest(FakeHttpTransport { fail("no exchange") }, launcher)

    val outcome = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }
    oauth.handleCallback(
      "$REDIRECT_URI?error=access_denied&state=${stateOf(launcher.authorizeUrls.single())}"
    )

    assertEquals(AuthResult.Denied, outcome.await())
  }

  @Test
  fun anErrorCallbackOtherThanDenialSurfacesTyped() = runSdkTest {
    val launcher = RecordingAuthorizeLauncher()
    val oauth = oauthUnderTest(FakeHttpTransport { fail("no exchange") }, launcher)

    val outcome = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }
    oauth.handleCallback(
      "$REDIRECT_URI?error=invalid_request&error_description=bad%20scope" +
        "&state=${stateOf(launcher.authorizeUrls.single())}"
    )

    val result = assertIs<AuthResult.Failed>(outcome.await())
    assertIs<TraciveException.InvalidRequest>(result.error)
    assertEquals("bad scope", result.error.message)
  }

  @Test
  fun aCallbackCarryingNoCodeFails() = runSdkTest {
    val launcher = RecordingAuthorizeLauncher()
    val oauth = oauthUnderTest(FakeHttpTransport { fail("no exchange") }, launcher)

    val outcome = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }
    oauth.handleCallback("$REDIRECT_URI?state=${stateOf(launcher.authorizeUrls.single())}")

    assertIs<AuthResult.Failed>(outcome.await())
  }

  @Test
  fun aFailedExchangeSurfacesTheServersError() = runSdkTest {
    val launcher = RecordingAuthorizeLauncher()
    val store = InMemoryTokenStore()
    val transport = FakeHttpTransport {
      HttpResponse(400, "{\"error\":\"invalid_grant\",\"error_description\":\"already used\"}")
    }
    val oauth = oauthUnderTest(transport, launcher, tokenStore = store)

    val outcome = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }
    oauth.handleCallback(
      "$REDIRECT_URI?code=spent&state=${stateOf(launcher.authorizeUrls.single())}"
    )

    assertIs<AuthResult.Failed>(outcome.await())
    assertNull(store.load(), "a failed exchange must not leave a grant behind")
  }

  /**
   * Only one `state` can be outstanding, so a second `authorize()` supersedes the first. The first
   * has to resolve rather than wait forever — a coroutine parked on a callback that can no longer
   * arrive is a leak the host cannot see.
   */
  @Test
  fun aSecondAuthorizationSupersedesThePendingOne() = runSdkTest {
    val launcher = RecordingAuthorizeLauncher()
    val oauth = oauthUnderTest(FakeHttpTransport { fail("no exchange") }, launcher)

    val first = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }
    val second = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }

    assertIs<AuthResult.Failed>(first.await())
    oauth.cancelPendingAuthorization()
    assertEquals(AuthResult.Denied, second.await())
    assertEquals(2, launcher.authorizeUrls.size)
  }

  @Test
  fun cancellingAPendingAuthorizationResolvesIt() = runSdkTest {
    val launcher = RecordingAuthorizeLauncher()
    val oauth = oauthUnderTest(FakeHttpTransport { fail("no exchange") }, launcher)

    val outcome = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }
    oauth.cancelPendingAuthorization()

    assertEquals(AuthResult.Denied, outcome.await())
  }

  // ----------------------------------------------------------------- sign out ----

  @Test
  fun signOutRevokesTheRefreshTokenAndThenClearsLocally() = runSdkTest {
    val store = InMemoryTokenStore(TokenSet(accessToken = "access-1", refreshToken = "refresh-1"))
    val transport = FakeHttpTransport { HttpResponse(200, "") }
    val oauth = oauthUnderTest(transport, RecordingAuthorizeLauncher(), tokenStore = store)

    oauth.signOut()

    val revoke = transport.requestsTo("/oauth/revoke").single()
    val body = assertNotNull(revoke.body)
    // Revoking the refresh token ends the grant; revoking only the access token would leave the
    // "signed out" device able to mint a new session.
    assertTrue(body.contains("token=refresh-1"), body)
    assertTrue(body.contains("token_type_hint=refresh_token"), body)
    assertNull(store.load())
  }

  /** Offline is not a reason to leave a live refresh token on a device the user signed out of. */
  @Test
  fun signOutClearsTheGrantEvenWhenTheRevokeFails() = runSdkTest {
    val store = InMemoryTokenStore(TokenSet(accessToken = "access-1", refreshToken = "refresh-1"))
    val transport = FakeHttpTransport { throw IllegalStateException("offline") }
    val oauth = oauthUnderTest(transport, RecordingAuthorizeLauncher(), tokenStore = store)

    oauth.signOut()

    assertNull(store.load())
  }

  @Test
  fun signOutWithNoGrantSendsNothing() = runSdkTest {
    val transport = FakeHttpTransport { fail("there is no grant to revoke") }
    val oauth = oauthUnderTest(transport, RecordingAuthorizeLauncher())

    oauth.signOut()

    assertTrue(transport.requests.isEmpty())
  }

  // ------------------------------------------------------------ cancellation ----

  /**
   * A cancel raised for an earlier authorization must not land on a newer one.
   *
   * The double-tap: `authorize()` runs twice, the second supersedes the first, and only then does
   * the first browser's close event reach the Activity. Cancelling unconditionally at that point
   * abandons the request the user is actually looking at, and the sample's `launchGuarded` busy
   * flag does not cover it because the cancel comes from the Activity rather than the ViewModel.
   */
  @Test
  fun aCancelForASupersededAuthorizationLeavesTheCurrentOneAlone() = runSdkTest {
    val transport = FakeHttpTransport { jsonResponse(tokenJson()) }
    val launcher = RecordingAuthorizeLauncher()
    val oauth = oauthUnderTest(transport, launcher)

    val first = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }
    val firstId = assertNotNull(oauth.pendingAuthorizationId)

    val second = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }
    val secondId = assertNotNull(oauth.pendingAuthorizationId)
    assertNotEquals(firstId, secondId)
    // The first call is already resolved: starting a second one supersedes it.
    assertIs<AuthResult.Failed>(first.await())

    assertFalse(
      oauth.cancelPendingAuthorization(firstId),
      "a cancel naming the superseded request cancelled the current one",
    )

    val state = stateOf(launcher.authorizeUrls.last())
    assertTrue(oauth.handleCallback("$REDIRECT_URI?code=abc&state=$state"))
    assertIs<AuthResult.Authorized>(second.await())
  }

  @Test
  fun cancellingReportsWhetherThereWasAnythingToCancel() = runSdkTest {
    val launcher = RecordingAuthorizeLauncher()
    val oauth = oauthUnderTest(FakeHttpTransport { fail("no request") }, launcher)

    assertFalse(oauth.cancelPendingAuthorization(), "nothing is pending yet")

    val outcome = async(start = CoroutineStart.UNDISPATCHED) { oauth.authorize() }
    assertTrue(oauth.cancelPendingAuthorization())
    assertEquals(AuthResult.Denied, outcome.await())
    assertNull(oauth.pendingAuthorizationId)
  }

  // ----------------------------------------------------------- token storage ----

  /**
   * A refresh whose result cannot be stored must fail, not return the new token.
   *
   * Returning it would mean the caller carries on with an access token that exists only in memory
   * while the device's stored refresh token is the one the server has just retired. The next
   * refresh presents the retired token, the server reads that as a replay, and the whole grant is
   * revoked. Failing here costs one visible error instead.
   */
  @Test
  fun aRefreshThatCannotBeStoredFailsInsteadOfReturningTheNewToken() = runSdkTest {
    val store = RefusingTokenStore(TokenSet(accessToken = "access-1", refreshToken = "refresh-1"))
    val transport = FakeHttpTransport {
      jsonResponse(tokenJson(accessToken = "access-2", refreshToken = "refresh-2"))
    }
    val oauth = oauthUnderTest(transport, RecordingAuthorizeLauncher(), tokenStore = store)

    assertFailsWith<TraciveException.TokenStorageFailed> { oauth.refreshedToken("access-1") }
    // The grant on the device is untouched, which is the only state the server still agrees with.
    assertEquals("access-1", assertNotNull(store.load()).accessToken)
  }

  /** A [TokenStore] whose backing store is broken, as a locked Keychain or a full disk would be. */
  private class RefusingTokenStore(private val stored: TokenSet) : TokenStore {
    override suspend fun load(): TokenSet = stored

    override suspend fun save(tokens: TokenSet): Unit =
      throw TraciveException.TokenStorageFailed(platformStatus = -25308)

    override suspend fun clear() = Unit
  }

  // ---------------------------------------------------------------- internals ----

  private fun oauthUnderTest(
    transport: FakeHttpTransport = FakeHttpTransport { fail("no request expected") },
    launcher: RecordingAuthorizeLauncher = RecordingAuthorizeLauncher(),
    tokenStore: TokenStore = InMemoryTokenStore(),
    installed: Boolean = true,
    clientType: ClientType = ClientType.PUBLIC,
    installUrlOverride: String? = null,
  ): TraciveOAuth =
    TraciveOAuth(
      mode = testOAuthMode(redirectUri = REDIRECT_URI, clientType = clientType),
      environment = TraciveEnvironment.Custom(TEST_BASE_URL),
      transport = transport,
      tokenStore = tokenStore,
      appPresenceChecker = FakeAppPresenceChecker(installed = installed),
      authorizeLauncher = launcher,
      clock = FixedClock(),
      installUrlOverride = installUrlOverride,
    )

  private fun stateOf(authorizeUrl: String): String =
    TraciveOAuth.parseQueryParameters(authorizeUrl)[AuthorizeUrlBuilder.PARAM_STATE]
      ?: fail("the authorize URL carried no state")

  /** Pulls `code_verifier` back out of a form-encoded token-exchange body. */
  private fun verifierFrom(formBody: String): String =
    formBody
      .split('&')
      .firstOrNull { it.startsWith("code_verifier=") }
      ?.substringAfter('=')
      ?.let { TraciveOAuth.percentDecode(it) } ?: fail("the exchange sent no code_verifier")

  private fun fail(message: String): Nothing = throw AssertionError(message)

  private companion object {
    const val REDIRECT_URI = "https://partner.test/tracive/callback"
  }
}
