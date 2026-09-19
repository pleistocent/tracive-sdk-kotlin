package id.tracive.sdk.sample

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import id.tracive.sdk.cache.SqlDelightCheckCache
import id.tracive.sdk.client.TraciveClient
import id.tracive.sdk.model.CachePolicy
import kotlinx.coroutines.Dispatchers

/**
 * The Android half of the wiring: a Keystore-backed key store and the Android SQLite driver.
 *
 * `@Volatile` is load-bearing, not decoration. [traciveClient] reads this field once outside the
 * lock, which is the whole point of double-checked locking — but without `@Volatile` there is no
 * happens-before edge between the write inside the lock and that unsynchronised read. A second
 * thread can then see a non-null reference to a `TraciveClient` whose fields it does not yet see
 * initialised. This is the textbook broken double-checked lock, and it was in this file, which
 * partners are told to copy.
 */
@Volatile private var instance: TraciveClient? = null

/**
 * The shared client, built on first use.
 *
 * [flavor] and [redirectUri] are `BuildConfig.FLAVOR` and `BuildConfig.TRACIVE_REDIRECT_URI` of the
 * app module, which this library module cannot see for itself.
 */
fun traciveClient(context: Context, flavor: String, redirectUri: String): TraciveClient =
  instance
    ?: synchronized(TraciveSample) {
      instance
        ?: create(context.applicationContext, SampleFlavor.named(flavor), redirectUri).also {
          instance = it
        }
    }

private fun create(context: Context, flavor: SampleFlavor, redirectUri: String): TraciveClient {
  val driver =
    AndroidSqliteDriver(
      schema = SqlDelightCheckCache.Schema,
      context = context,
      name = "tracive-check-cache.db",
    )

  val cache =
    SqlDelightCheckCache(
      driver = driver,
      // See KeystoreCacheDataKeyStore for why the app supplies this and what the wrapping pattern
      // buys on a platform whose key store will not hand back raw bytes.
      // TODO(partner): copy KeystoreCacheDataKeyStore into your app as it stands. The SDK ships no
      // default because producing and protecting key material is the platform's job.
      dataKeyStore = KeystoreCacheDataKeyStore(context),
      policy = CachePolicy.Default,
      // The SDK cannot name `Dispatchers.IO` in common code, so its default is
      // `Dispatchers.Default`. On Android, pass IO: a SQLite read blocking a CPU-pool thread is
      // avoidable, and this is where it is avoided. There is no equivalent on iOS — see the
      // comment in the iOS file.
      ioContext = Dispatchers.IO,
    )

  return TraciveSample.client(cache, flavor, redirectUri)
}
