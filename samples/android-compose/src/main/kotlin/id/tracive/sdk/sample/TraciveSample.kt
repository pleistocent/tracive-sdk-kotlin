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
  private const val CLIENT_ID = "REPLACE_WITH_YOUR_CLIENT_ID"

  /** Must match the `<data>` element in `AndroidManifest.xml` exactly, scheme and host included. */
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
          scopes = listOf("scammer.check", "profile.read"),
          // The partner's own id for this user, so the same person keeps one grant across
          // reinstalls, and so `grant.revoked` can be mapped back to the partner's own customer.
          // Required: the Tracive app refuses a consent without one, client-side and with no
          // redirect, so leaving it out looks like nothing happening at all. Opaque — never a
          // phone number or an email.
          partnerUserId = "sample-user-1",
          // PUBLIC: this app performs the PKCE token exchange itself. A partner whose backend holds
          // a client secret uses CONFIDENTIAL instead, and the SDK stops at the authorization code.
          clientType = ClientType.PUBLIC,
        ),
      // Sandbox while the API is being built. Production is the default and is what a shipped app
      // would use.
      environment = TraciveEnvironment.Sandbox,
      cachePolicy = CachePolicy.Default,
      // Cache first: a hit costs nothing and works offline, and every server check is billed —
      // a miss included.
      defaultSource = Source.CACHE_FIRST,
      cache = cache,
    )
  }
}
