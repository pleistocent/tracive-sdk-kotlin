package id.tracive.sdk.sample

import id.tracive.sdk.cache.CheckCache
import id.tracive.sdk.client.TraciveClient
import id.tracive.sdk.model.CachePolicy
import id.tracive.sdk.model.ClientType
import id.tracive.sdk.model.Source
import id.tracive.sdk.model.TraciveEnvironment
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
 * **The backend Open API is not live yet.** Every network call this sample makes will fail until it
 * is, and that is expected. What is real today is the local half: query folding, the encrypted
 * cache, TTL policy, and the OAuth flow up to the point where it needs a server.
 *
 * Using this sample as your own experiment ground? Everything you have to supply is marked
 * `TODO(partner)`. `grep -rn "TODO(partner)" samples` lists all of them — here, in each app shell's
 * manifest and `Info.plist`, and in [CheckViewModel].
 */
object TraciveSample {

  /**
   * The client id a partner is issued when they register. A placeholder here — there is nothing to
   * register with yet.
   *
   * Note what is *not* on this object, in any mode: an API key. The SDK has no API-key parameter
   * anywhere in its public surface, which is a deliberate design invariant rather than an omission.
   * A key shipped inside an app is a key that has been published.
   */
  // TODO(partner): the client id you were issued at registration.
  private const val CLIENT_ID = "REPLACE_WITH_YOUR_CLIENT_ID"

  /**
   * One redirect for both platforms, which is why it is here rather than in either app shell. It
   * must match the Android manifest's `<data>` element and the iOS `CFBundleURLSchemes` entry
   * exactly.
   *
   * A private-use URI scheme, which RFC 8252 permits for a native app that controls no web domain.
   * A partner who does control one should register an https App Link / Universal Link instead: it
   * is bound to the host by `assetlinks.json` or an apple-app-site-association file, so no other
   * app on the device can claim it, whereas any app may declare this scheme.
   */
  // TODO(partner): your own redirect, registered with Tracive, and matching both app shells.
  // Prefer an App Link / Universal Link if you control a domain — docs/oauth.md covers why.
  private const val REDIRECT_URI = "tracive-sample://oauth/callback"

  /** Builds the client around a cache the platform supplied. */
  internal fun client(cache: CheckCache): TraciveClient =
    TraciveClient(
      mode =
        TraciveMode.OAuth(
          clientId = CLIENT_ID,
          redirectUri = REDIRECT_URI,
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
      // TODO(partner): TraciveEnvironment.Production before you ship. Sandbox is for building
      // against; Production is the default.
      environment = TraciveEnvironment.Sandbox,
      cachePolicy = CachePolicy.Default,
      // Cache first: a hit costs nothing and works offline, and every server check is billed —
      // a miss included.
      defaultSource = Source.CACHE_FIRST,
      cache = cache,
    )
}
