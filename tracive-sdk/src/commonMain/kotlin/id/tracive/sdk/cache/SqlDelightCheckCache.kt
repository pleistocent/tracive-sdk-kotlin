package id.tracive.sdk.cache

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import id.tracive.sdk.cache.db.TraciveCacheDatabase
import id.tracive.sdk.model.CachePolicy
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * The persistent [CheckCache]: SQLDelight rows, AES-256-GCM payloads, keyed by the SHA-256 of the
 * folded query.
 *
 * ### Constructing one
 *
 * The [SqlDriver] is a constructor parameter rather than something this class builds, because the
 * driver is the one genuinely platform-specific piece and the host app already has the `Context`
 * (or the file path) it needs. Create it with [Schema] so the tables exist before the first read:
 * ```kotlin
 * // Android
 * val driver = AndroidSqliteDriver(SqlDelightCheckCache.Schema, context, "tracive-check-cache.db")
 *
 * // iOS
 * val driver = NativeSqliteDriver(SqlDelightCheckCache.Schema, "tracive-check-cache.db")
 * ```
 *
 * [dataKeyStore] must be backed by the Android Keystore or the iOS Keychain. Handing this class an
 * [InMemoryCacheDataKeyStore] would be worse than not encrypting at all: the same key would be
 * paired with a nonce counter that restarts at zero every time the process does, and a repeated
 * (key, nonce) pair in AES-GCM leaks the authentication subkey for every record ever written under
 * it. See [CacheDataKeyStore] for the full contract.
 *
 * ### What it stores
 *
 * Two columns of substance per row: the SHA-256 cache key and the sealed envelope. No identifier,
 * no risk tier, no report count in cleartext. `TraciveCache.sq` spells out why nothing may be added
 * to that list.
 *
 * ### Threading
 *
 * SQLDelight's SQLite drivers are blocking, so every call hops to [ioContext] first. The default is
 * [Dispatchers.Default], which is the strongest guarantee common Kotlin can make on its own —
 * `Dispatchers.IO` is not declared in the common source set, only on JVM and Native separately, so
 * the SDK cannot name it here. Default is a CPU-sized pool, and blocking one of its threads on a
 * disk read is not free; an Android host should pass `Dispatchers.IO` instead. What matters is that
 * neither default nor override is the main thread, so the cache cannot cause a jank or an ANR by
 * omission.
 *
 * The cache serialises its own operations internally, so concurrent callers are safe regardless of
 * which context is used.
 *
 * The driver's lifetime belongs to whoever created it — this class never closes it, since a host
 * that shares one driver across several components would be badly surprised if the cache closed it
 * on their behalf.
 */
class SqlDelightCheckCache(
  driver: SqlDriver,
  dataKeyStore: CacheDataKeyStore,
  policy: CachePolicy = CachePolicy.Default,
  clock: Clock = Clock.System,
  ioContext: CoroutineContext = Dispatchers.Default,
) :
  CheckCache by DefaultCheckCache(
    store = SqlDelightCacheRecordStore(TraciveCacheDatabase(driver), ioContext),
    dataKeyStore = dataKeyStore,
    policy = policy,
    clock = clock,
  ) {

  override fun toString(): String = "SqlDelightCheckCache"

  companion object {
    /**
     * The cache's SQLite schema, for constructing the driver.
     *
     * Re-exported here so a host never has to import the generated `id.tracive.sdk.cache.db`
     * package, which exists to serve this file and is not part of the SDK's supported surface.
     */
    val Schema: SqlSchema<QueryResult.Value<Unit>>
      get() = TraciveCacheDatabase.Schema
  }
}

/**
 * [CacheRecordStore] over SQLDelight. Rows in, rows out — no encryption, no TTL policy, no schema
 * reasoning; all of that is [DefaultCheckCache]'s, and keeping it out of here is what lets the same
 * logic be tested against a map.
 */
internal class SqlDelightCacheRecordStore(
  database: TraciveCacheDatabase,
  private val ioContext: CoroutineContext,
) : CacheRecordStore {

  private val queries = database.traciveCacheQueries

  override suspend fun readMeta(): CacheMeta? =
    withContext(ioContext) {
      queries.selectMeta().executeAsOneOrNull()?.let {
        CacheMeta(
          schemaVersion = it.schemaVersion.toInt(),
          dataKeyId = it.dataKeyId,
          nonceCounter = it.nonceCounter,
        )
      }
    }

  override suspend fun writeMeta(meta: CacheMeta) {
    withContext(ioContext) {
      queries.upsertMeta(
        schemaVersion = meta.schemaVersion.toLong(),
        dataKeyId = meta.dataKeyId,
        nonceCounter = meta.nonceCounter,
      )
    }
  }

  override suspend fun reserveNonceCounter(): Long =
    withContext(ioContext) {
      // Increment and read in one transaction. Splitting them would let a crash — or, without the
      // cache's own mutex, a second writer — hand the same counter to two records, and two records
      // sealed under one (key, nonce) pair is the failure AES-GCM does not degrade gracefully from.
      queries.transactionWithResult {
        queries.bumpNonceCounter()
        queries.selectNonceCounter().executeAsOne()
      }
    }

  override suspend fun select(cacheKey: String): CacheRow? =
    withContext(ioContext) {
      queries.selectEntry(cacheKey).executeAsOneOrNull()?.let {
        CacheRow(
          cacheKey = it.cacheKey,
          envelope = it.envelope,
          expiresAtEpochMs = it.expiresAtEpochMs,
        )
      }
    }

  override suspend fun upsert(row: CacheRow) {
    withContext(ioContext) {
      queries.upsertEntry(
        cacheKey = row.cacheKey,
        envelope = row.envelope,
        expiresAtEpochMs = row.expiresAtEpochMs,
      )
    }
  }

  override suspend fun delete(cacheKey: String) {
    withContext(ioContext) { queries.deleteEntry(cacheKey) }
  }

  override suspend fun deleteAll() {
    // Only the entry table. The meta row carries the nonce counter, and dropping it would restart
    // the counter under an unchanged data key.
    withContext(ioContext) { queries.deleteAllEntries() }
  }

  override suspend fun deleteExpired(nowEpochMs: Long): Int =
    withContext(ioContext) {
      queries
        .transactionWithResult {
          queries.deleteExpiredEntries(nowEpochMs)
          // `changes()` reports the rows touched by the statement before it, so it has to be read
          // inside the same transaction.
          queries.selectChanges().executeAsOne()
        }
        .toInt()
    }

  override suspend fun count(): Int =
    withContext(ioContext) { queries.countEntries().executeAsOne().toInt() }
}
