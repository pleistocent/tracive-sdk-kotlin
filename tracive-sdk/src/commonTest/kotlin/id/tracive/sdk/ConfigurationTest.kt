package id.tracive.sdk

import id.tracive.sdk.model.AuthResult
import id.tracive.sdk.model.CachePolicy
import id.tracive.sdk.model.ClientType
import id.tracive.sdk.model.RiskTier
import id.tracive.sdk.model.TraciveEnvironment
import id.tracive.sdk.model.TraciveMode
import id.tracive.sdk.model.TraciveScopes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration types and the secrets they must never print.
 *
 * The redaction tests are not style checks. A `toString` that leaks an access token, an OAuth
 * authorization code or a PKCE verifier puts a live credential into log aggregators and crash
 * reports, which are retained for months and readable by far more people than the token ever should
 * be. Kotlin's `data class` generates exactly that `toString` for free, so every type here that
 * holds a secret is deliberately not one.
 */
class ConfigurationTest {

  @Test
  fun environmentsExposeARootWithNoTrailingSlash() {
    assertEquals("https://api.tracive.id/v1", TraciveEnvironment.Production.baseUrl)
    assertEquals("https://api-sandbox.tracive.id/v1", TraciveEnvironment.Sandbox.baseUrl)
    // A trailing slash would produce "…/v1//scammer-checks" once a path is joined on.
    assertEquals(
      "http://localhost:8080",
      TraciveEnvironment.Custom("http://localhost:8080/").baseUrl,
    )
    assertEquals(
      "https://proxy.partner.test",
      TraciveEnvironment.Custom(" https://proxy.partner.test ").baseUrl,
    )
    assertEquals(
      TraciveEnvironment.Custom("https://a.test"),
      TraciveEnvironment.Custom("https://a.test/"),
    )
  }

  @Test
  fun aCustomEnvironmentMustBeAnAbsoluteHttpUrl() {
    assertFailsWith<IllegalArgumentException> { TraciveEnvironment.Custom("api.tracive.id") }
    assertFailsWith<IllegalArgumentException> { TraciveEnvironment.Custom("") }
    assertFailsWith<IllegalArgumentException> { TraciveEnvironment.Custom("ftp://api.tracive.id") }
  }

  /**
   * The scope names are a wire contract, not a naming preference.
   *
   * The authorization server rejects a consent request naming a scope it does not know, so a wrong
   * constant here is not a narrower grant — it is an integration that cannot authorize at all, and
   * one that fails identically whichever of the names is wrong. The strings are spelled out rather
   * than compared to the constants, so that renaming a constant fails here instead of quietly
   * redefining the contract on the SDK's side.
   */
  @Test
  fun scopeConstantsAreTheExactWireStrings() {
    assertEquals("scammer.check", TraciveScopes.SCAMMER_CHECK)
    assertEquals("credits.read", TraciveScopes.CREDITS_READ)
    assertEquals("profile.read", TraciveScopes.PROFILE_READ)

    // Separator and resource name are both easy to get wrong, and both were wrong here before: the
    // colon form and a bare `check` are what an earlier revision of this SDK actually requested.
    for (scope in
      listOf(TraciveScopes.SCAMMER_CHECK, TraciveScopes.CREDITS_READ, TraciveScopes.PROFILE_READ)) {
      assertFalse(scope.contains(':'), "scope names are dot-separated: $scope")
      assertTrue(scope.contains('.'), "scope names are dot-separated: $scope")
    }
  }

  @Test
  fun oauthModeDefaultsToAPublicClient() {
    val mode =
      TraciveMode.OAuth(
        clientId = "cli_1",
        redirectUri = "https://partner.test/oauth/callback",
        scopes = listOf("scammer.check"),
        partnerUserId = "partner-user-1",
      )
    assertEquals(ClientType.PUBLIC, mode.clientType)
    assertEquals(listOf("scammer.check"), mode.scopes)
  }

  @Test
  fun oauthModeRejectsAnEmptyClientIdOrRedirectOrPartnerUserId() {
    assertFailsWith<IllegalArgumentException> {
      TraciveMode.OAuth("  ", "https://partner.test/cb", emptyList(), "u1")
    }
    assertFailsWith<IllegalArgumentException> { TraciveMode.OAuth("cli_1", "", emptyList(), "u1") }
    // A blank partnerUserId is refused here rather than omitted from the authorize URL. The
    // Tracive app rejects a consent without one client-side and returns no redirect at all, so an
    // integrator who got this wrong would see nothing happen and have nothing to report.
    assertFailsWith<IllegalArgumentException> {
      TraciveMode.OAuth("cli_1", "https://partner.test/cb", emptyList(), "   ")
    }
  }

  @Test
  fun proxyModeNeverPrintsItsCredentials() {
    val mode =
      TraciveMode.Proxy(
        baseUrl = "https://proxy.partner.test/tracive/",
        authHeaders = mapOf("Authorization" to "Bearer super-secret-token", "X-Partner" to "p_1"),
      )

    assertEquals("https://proxy.partner.test/tracive", mode.baseUrl)

    val printed = mode.toString()
    assertFalse(printed.contains("super-secret-token"), "a credential reached toString")
    assertFalse(printed.contains("Bearer"), "a credential reached toString")
    assertTrue(printed.contains("Authorization"), "header names are safe and useful to see")
  }

  @Test
  fun proxyModeCopiesTheHeadersItWasGiven() {
    val supplied = mutableMapOf("Authorization" to "Bearer one")
    val mode = TraciveMode.Proxy("https://proxy.partner.test", supplied)
    supplied["Authorization"] = "Bearer two"
    supplied["X-Injected"] = "late"

    assertEquals(mapOf("Authorization" to "Bearer one"), mode.authHeaders)
  }

  @Test
  fun anAuthorizationCodeNeverReachesALog() {
    val result =
      AuthResult.CodeReceived(
        code = "authcode-abcdef",
        codeVerifier = "verifier-123456",
        redirectUri = "https://partner.test/cb",
      )

    val printed = result.toString()
    assertFalse(printed.contains("authcode-abcdef"), "the authorization code reached toString")
    assertFalse(printed.contains("verifier-123456"), "the PKCE verifier reached toString")
    assertTrue(printed.contains("https://partner.test/cb"), "the redirect URI is not a secret")

    // The values are still available to the caller that has to forward them.
    assertEquals("authcode-abcdef", result.code)
    assertEquals("verifier-123456", result.codeVerifier)
  }

  @Test
  fun theTerminalAuthResultsPrintReadably() {
    assertEquals("Denied", AuthResult.Denied.toString())
    assertEquals("AppNotInstalled", AuthResult.AppNotInstalled.toString())
  }

  @Test
  fun cacheTtlScalesWithTheTier() {
    val policy = CachePolicy.Default
    assertEquals(30.days, policy.ttlFor(RiskTier.HIGH))
    assertEquals(7.days, policy.ttlFor(RiskTier.ELEVATED))
    assertEquals(24.hours, policy.ttlFor(RiskTier.CAUTION))
    assertEquals(1.hours, policy.ttlFor(RiskTier.NONE))
  }

  /**
   * `cacheTtlSeconds` is the backend's only way to make already-installed apps drop a cached answer
   * without shipping a release, so it has to win over the tier default.
   */
  @Test
  fun aServerTtlOverridesTheTierDefault() {
    val policy = CachePolicy.Default
    assertEquals(90.seconds, policy.effectiveTtl(RiskTier.HIGH, 90L))
    // Zero means "do not keep this", which is the point of the lever.
    assertEquals(0.seconds, policy.effectiveTtl(RiskTier.HIGH, 0L))
    // Absent means "use the default".
    assertEquals(30.days, policy.effectiveTtl(RiskTier.HIGH, null))
    // A negative value clamps to zero rather than falling back, so it retracts the answer instead
    // of extending it. See `effectiveTtl` and `CacheTtlTest` for which way round that has to be.
    assertEquals(0.seconds, policy.effectiveTtl(RiskTier.HIGH, -1L))
    assertEquals(0.seconds, policy.effectiveTtl(RiskTier.HIGH, Long.MIN_VALUE))
  }

  @Test
  fun anAppCanOptOutOfTheServerOverride() {
    val policy = CachePolicy.Default.copy(honorServerTtlOverride = false)
    assertEquals(30.days, policy.effectiveTtl(RiskTier.HIGH, 90L))
    assertEquals(1.hours, policy.effectiveTtl(RiskTier.NONE, 90L))
  }

  @Test
  fun theCacheSchemaVersionExistsSoAGrammarChangeCanInvalidateEverything() {
    assertTrue(CachePolicy.SCHEMA_VERSION >= 1)
  }
}
