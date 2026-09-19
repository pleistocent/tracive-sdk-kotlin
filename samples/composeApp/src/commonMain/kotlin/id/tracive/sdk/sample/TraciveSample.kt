package id.tracive.sdk.sample

import id.tracive.sdk.cache.CheckCache
import id.tracive.sdk.client.TraciveClient
import id.tracive.sdk.model.CachePolicy
import id.tracive.sdk.model.ClientType
import id.tracive.sdk.model.Source
import id.tracive.sdk.model.TraciveMode
import id.tracive.sdk.model.TraciveScopes

/**
 * Everything about the client that is the same on both platforms — which is all of it except the
 * cache.
 *
 * A [TraciveClient] owns an HTTP client, a token store and a database handle, so it is built once
 * per process and shared, not once per screen. Each platform's `traciveClient(...)` calls this once
 * and holds the result.
 *
 * The split is the point of the sample. Key storage and the SQLite driver are genuinely
 * platform-specific — Keystore and `AndroidSqliteDriver` on one side, Keychain and
 * `NativeSqliteDriver` on the other — and everything else, including the whole OAuth configuration
 * and the entire UI, is written once.
 *
 * Which Tracive environment it talks to is the build's choice, not this file's: see [SampleFlavor].
 *
 * Using this sample as your own experiment ground? Everything you have to supply is marked
 * `TODO(partner)`. `grep -rn "TODO(partner)" samples` lists all of them — here, in each app shell's
 * build configuration, manifest and `Info.plist`, and in [CheckViewModel].
 */
object TraciveSample {

  /*
   * Sandbox and production are separate registrations — two deployments with two client
   * databases — so a sandbox client id is unknown to production and the other way round.
   *
   * Note what is *not* here, in any mode: an API key. The SDK has no API-key parameter anywhere in
   * its public surface, which is a deliberate design invariant rather than an omission. A key
   * shipped inside an app is a key that has been published.
   */
  // TODO(partner): the client id Tracive issued you in sandbox, with the sandbox flavor's redirect
  // registered on it.
  private const val SANDBOX_CLIENT_ID = "REPLACE_WITH_YOUR_SANDBOX_CLIENT_ID"

  // TODO(partner): the client id Tracive issued you in production, with the production flavor's
  // redirect registered on it.
  private const val PRODUCTION_CLIENT_ID = "REPLACE_WITH_YOUR_PRODUCTION_CLIENT_ID"

  /**
   * Builds the client around a cache the platform supplied.
   *
   * [redirectUri] comes from the app shell because the shell is what registers it: the Android
   * manifest's `<data>` element and the iOS `CFBundleURLSchemes` entry are generated from the same
   * per-flavor value, so the three cannot drift apart. A mismatch would otherwise only surface at
   * the very end of the OAuth round trip.
   */
  internal fun client(cache: CheckCache, flavor: SampleFlavor, redirectUri: String): TraciveClient =
    TraciveClient(
      mode =
        TraciveMode.OAuth(
          clientId =
            when (flavor) {
              SampleFlavor.SANDBOX -> SANDBOX_CLIENT_ID
              SampleFlavor.PRODUCTION -> PRODUCTION_CLIENT_ID
            },
          redirectUri = redirectUri,
          // TODO(partner): ask for the narrowest set your integration needs — an unused scope is
          // one more permission the user has to accept. Constants, not strings: the server rejects
          // the whole consent request when it does not recognise a name, so a typo is not a
          // narrower grant, it is an integration that cannot authorize at all.
          scopes = listOf(TraciveScopes.SCAMMER_CHECK, TraciveScopes.PROFILE_READ),
          // The partner's own id for this user, so the same person keeps one grant across
          // reinstalls, and so `grant.revoked` can be mapped back to the partner's own customer.
          // Required: the Tracive app refuses a consent without one, client-side and with no
          // redirect, so leaving it out looks like nothing happening at all. Opaque — never a
          // phone number or an email.
          // TODO(partner): your own stable id for this user — an account id or a UUID you store.
          partnerUserId = "sample-user-1",
          // PUBLIC: this app performs the PKCE token exchange itself.
          // TODO(partner): CONFIDENTIAL if your backend holds the client secret. The SDK then stops
          // at the authorization code, and CheckViewModel's CodeReceived branch is where you hand
          // it over.
          clientType = ClientType.PUBLIC,
        ),
      environment = flavor.environment,
      cachePolicy = CachePolicy.Default,
      // Cache first: a hit costs nothing and works offline, and every server check is billed —
      // a miss included.
      defaultSource = Source.CACHE_FIRST,
      cache = cache,
    )
}
