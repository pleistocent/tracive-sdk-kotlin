package id.tracive.sdk.sample

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import id.tracive.sdk.cache.SqlDelightCheckCache
import id.tracive.sdk.client.TraciveClient
import id.tracive.sdk.model.CachePolicy
import id.tracive.sdk.model.ClientType
import id.tracive.sdk.model.Source
import id.tracive.sdk.model.TraciveEnvironment
import id.tracive.sdk.model.TraciveMode
import id.tracive.sdk.model.TraciveScopes
import kotlinx.coroutines.Dispatchers

/**
 * The one place this sample builds a [TraciveClient].
 *
 * A [TraciveClient] owns an HTTP client, a token store and a database handle, so it is built once
 * per process and shared — not once per screen. A real app would hand it to whatever dependency
 * injection it already uses; this sample keeps it in an object so the wiring is readable in one
 * screenful.
 *
 * **The backend Open API is not live yet.** Every network call this sample makes will fail until it
 * is, and that is expected. What is real today is the local half: query folding, the encrypted
 * cache, TTL policy, and the OAuth flow up to the point where it needs a server.
 *
 * Using this sample as your own experiment ground? Everything you have to supply is marked
 * `TODO(partner)`. `grep -rn "TODO(partner)" samples` lists all of them — here, in this module's
 * `build.gradle.kts`, in `AndroidManifest.xml` and in `CheckViewModel`.
 */
object TraciveSample {

  /**
   * The client id a partner is issued when they register. A placeholder here — there is nothing to
   * register with yet.
   *
   * Note what is *not* on this object, in any mode: an API key. The SDK has no API-key parameter
   * anywhere in its public surface, which is a deliberate design invariant rather than an omission.
   * A key shipped inside an APK is a key that has been published.
   */
  // TODO(partner): the client id you were issued at registration.
  private const val CLIENT_ID = "REPLACE_WITH_YOUR_CLIENT_ID"

  /** Must match the `<data>` element in `AndroidManifest.xml` exactly, scheme and host included. */
  // TODO(partner): your own redirect, registered with Tracive. Change traciveRedirectScheme and
  // traciveRedirectHost in build.gradle.kts to match. Prefer an https App Link if you control a
  // domain — docs/oauth.md covers why.
  private const val REDIRECT_URI = "tracive-sample://oauth/callback"

  /**
   * `@Volatile` is load-bearing, not decoration.
   *
   * [client] reads this field once outside the lock, which is the whole point of double-checked
   * locking — but without `@Volatile` there is no happens-before edge between the write inside the
   * lock and that unsynchronised read. A second thread can then see a non-null reference to a
   * `TraciveClient` whose fields it does not yet see initialised. This is the textbook broken
   * double-checked lock, and it was in this file, which partners are told to copy.
   */
  @Volatile private var instance: TraciveClient? = null

  /** The shared client, built on first use. */
  fun client(context: Context): TraciveClient =
    instance
      ?: synchronized(this) { instance ?: create(context.applicationContext).also { instance = it } }

  private fun create(context: Context): TraciveClient {
    // The SQLite driver is the host app's choice, so the SDK asks for one rather than picking. The
    // schema is re-exported by the cache so this file never has to import the SDK's generated code.
    val driver =
      AndroidSqliteDriver(
        schema = SqlDelightCheckCache.Schema,
        context = context,
        name = "tracive-check-cache.db",
      )

    val cache =
      SqlDelightCheckCache(
        driver = driver,
        // Android Keystore-backed. See KeystoreCacheDataKeyStore for why the app supplies this and
        // what the wrapping pattern buys.
        // TODO(partner): copy KeystoreCacheDataKeyStore into your app as it stands. The SDK ships
        // no default because producing and protecting key material is the platform's job.
        dataKeyStore = KeystoreCacheDataKeyStore(context),
        policy = CachePolicy.Default,
        // The SDK cannot name `Dispatchers.IO` in common code, so its default is
        // `Dispatchers.Default`. On Android, pass IO: a SQLite read blocking a CPU-pool thread is
        // avoidable, and this is where it is avoided.
        ioContext = Dispatchers.IO,
      )

    return TraciveClient(
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
}
