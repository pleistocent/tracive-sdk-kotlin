package id.tracive.sdk.sample

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import id.tracive.sdk.cache.SqlDelightCheckCache
import id.tracive.sdk.client.TraciveClient
import id.tracive.sdk.model.CachePolicy

/**
 * The iOS half of the wiring: a Keychain-backed key store and the Kotlin/Native SQLite driver.
 *
 * No double-checked lock, unlike the Android file. `by lazy` is enough here: Kotlin/Native's
 * default `lazy` is synchronised, and the only caller is `SampleController`, which is created on
 * the main thread.
 */
private val client: TraciveClient by lazy { create() }

/** The shared client, built on first use. */
internal fun traciveClient(): TraciveClient = client

private fun create(): TraciveClient {
  val driver = NativeSqliteDriver(SqlDelightCheckCache.Schema, "tracive-check-cache.db")

  val cache =
    SqlDelightCheckCache(
      driver = driver,
      // See KeychainCacheDataKeyStore for why the app supplies this, and why it is half the length
      // of the Android one.
      // TODO(partner): copy KeychainCacheDataKeyStore into your app as it stands. The SDK ships no
      // default because producing and protecting key material is the platform's job.
      dataKeyStore = KeychainCacheDataKeyStore(),
      policy = CachePolicy.Default,
      // No `ioContext` here, unlike the Android file. `Dispatchers.IO` is internal on
      // Kotlin/Native — there is no public IO dispatcher to pass — so the cache keeps its
      // `Dispatchers.Default`, which on native is a worker pool rather than a CPU-sized one.
    )

  return TraciveSample.client(cache)
}
