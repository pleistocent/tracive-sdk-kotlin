package id.tracive.sdk.cache

import id.tracive.sdk.crypto.Sha256
import id.tracive.sdk.model.CachePolicy
import id.tracive.sdk.model.CheckResult
import id.tracive.sdk.model.Origin
import id.tracive.sdk.query.TraciveQuery
import kotlin.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

/**
 * The cache key for [query]: `SHA-256(searchQueryIndex)`, lower-case hex.
 *
 * The digest is the whole privacy story of this layer. `searchQueryIndex` is a phone number, an
 * email address, a bank account or a wallet address in cleartext; hashing it means the database
 * file holds no identifier at all, so a device backup, a rooted phone or a forensic image yields
 * nothing about *who* was checked.
 *
 * It is not a defence against someone who already suspects a specific number — SHA-256 of a phone
 * number is trivially brute-forced from a candidate list, and no salt would change that, because a
 * salt the device stores is a salt the attacker also has. It is a defence against bulk extraction,
 * which is the realistic threat: the identifiers cannot be enumerated out of the file, only
 * confirmed one guess at a time.
 */
internal fun cacheKeyFor(query: TraciveQuery): String =
  Sha256.digest(query.searchQueryIndex.encodeToByteArray()).toHex()

/**
 * The prefix that separates a transaction record's key from an entry's.
 *
 * An entry key is 64 lower-case hex digits, and `:` is not a hex digit, so the two key spaces
 * cannot collide. That is what lets both kinds of row live in one table without a discriminator
 * column — and a discriminator column is exactly what `TraciveCache.sq` refuses to grow. The prefix
 * is part of the key, so it is bound into the record's associated data too: a transaction record
 * copied onto an entry's row fails to open rather than answering a check.
 */
internal const val TRANSACTION_KEY_PREFIX: String = "txn:"

/**
 * The store key for a transaction record: `"txn:" + SHA-256(transactionId)`, lower-case hex.
 *
 * The id is hashed for the same reason the identifier is. A transaction reference is the host's own
 * string rather than a person's phone number, but written in cleartext beside an encrypted verdict
 * it would say *which* transfers a device holds a fraud answer for — and that is enough to pick the
 * interesting rows out of a stolen database file. Hashing costs nothing here: the only lookup this
 * store ever does is an exact one.
 *
 * The id is trimmed and otherwise used verbatim, so `"TRF-1"` and `"trf-1"` are two records. A host
 * whose own reference varies in case has to normalise it before it gets here; the SDK cannot, since
 * a reference format it has never seen is not one it can fold safely.
 */
internal fun transactionCacheKeyFor(transactionId: String): String {
  require(transactionId.isNotBlank()) {
    "A transaction record needs a non-blank transaction id: the record is looked up by it, and a " +
      "blank one would file every unlabelled transaction under a single shared row."
  }
  return TRANSACTION_KEY_PREFIX + Sha256.digest(transactionId.trim().encodeToByteArray()).toHex()
}

/**
 * How a cached record is serialised before it is sealed.
 *
 * Separate from the SDK's wire `Json` on purpose: this is a storage format the SDK controls on both
 * ends, and it must not drift because someone changed a decision about parsing server responses.
 *
 * `ignoreUnknownKeys` is here for the case where a newer build of the app wrote records and the
 * user then downgraded — the old build should treat a record with an extra field as usable, not as
 * corrupt. `encodeDefaults = false` keeps records small, which matters when a heavy user
 * accumulates thousands of them. `coerceInputValues` is left off deliberately: a `riskTier` this
 * build does not recognise must fail the decode, which the cache turns into a miss and a fresh
 * server call, rather than silently becoming `NONE`.
 */
private val CachePayloadJson: Json = Json {
  ignoreUnknownKeys = true
  encodeDefaults = false
  explicitNulls = false
}

/**
 * The whole cache, minus where the bytes physically live.
 *
 * Everything this cache is required to do is implemented here — SHA-256 key derivation, the AES-GCM
 * envelope, TTL per risk tier with the server override applied, schema-version invalidation, lazy
 * expiry on read and a prune on open — over the small [CacheRecordStore] interface. Swapping
 * SQLDelight for a map changes the store and nothing else, which is why the TTL and encryption
 * tests can run with no database at all.
 *
 * A [Mutex] serialises every operation. That is not just for the record store, which the contract
 * says may assume serialised access: opening the cache mutates shared state (the cipher, the data
 * key, the meta row) and reserving a nonce counter must not interleave, since two writers that
 * obtained the same counter value would seal two different records under one nonce — the single
 * failure AES-GCM does not survive.
 */
internal class DefaultCheckCache(
  private val store: CacheRecordStore,
  private val dataKeyStore: CacheDataKeyStore,
  private val policy: CachePolicy = CachePolicy.Default,
  private val clock: Clock = Clock.System,
  /**
   * Exposed as a parameter only so tests can simulate an upgrade. Production code always gets
   * [CachePolicy.SCHEMA_VERSION].
   */
  private val schemaVersion: Int = CachePolicy.SCHEMA_VERSION,
) : CheckCache {

  private val mutex = Mutex()
  private var cipher: CacheCipher? = null

  override suspend fun get(query: TraciveQuery): CheckResult? = mutex.withLock {
    // Forced rather than trusted: a record whose stored `origin` said SERVER would make a cache
    // hit indistinguishable from a live answer, and the caller uses this to decide whether to
    // show "checked just now" or "last checked <date>".
    readRecord(cacheKeyFor(query))?.copy(origin = Origin.CACHE)
  }

  override suspend fun put(query: TraciveQuery, result: CheckResult) {
    mutex.withLock {
      // `toCacheable()` is what enforces "never cache full detail" — it drops `topChronologies`,
      // `comments`, `billing` and `checkId`. The cache is keyed on the query alone, so anything
      // that varies with `detail` or `locale` would be served back under the wrong request. If a
      // later version ever needs to cache full-detail content, the key must become the triple of
      // (query, detail, locale); changing only this line would ship an Indonesian narrative to an
      // English request.
      //
      // A TTL of zero stores nothing and drops what is there: a zero `cacheTtlSeconds` is how the
      // backend retracts an answer that is already on the device.
      writeRecord(
        key = cacheKeyFor(query),
        record = result.toCacheable().copy(origin = Origin.CACHE),
        lifetime = policy.effectiveTtl(result.riskTier, result.cacheTtlSeconds),
      )
    }
  }

  override suspend fun putTransactionCheck(transactionId: String, result: CheckResult) {
    // Outside the lock: an argument that cannot produce a key is the caller's mistake, and raising
    // it should not depend on opening a cipher or waiting behind another writer.
    val key = transactionCacheKeyFor(transactionId)
    mutex.withLock {
      // `policy.transactionCheckRetention`, never `effectiveTtl`. The tier TTL asks how long a
      // verdict stays trustworthy; this row answers "what was this user shown before they sent the
      // money", and that does not decay. Feeding the server's `cacheTtlSeconds` in here would let
      // the backend retract a record of the past, which is the one thing it must not be able to do.
      writeRecord(
        key = key,
        record = result.toTransactionRecord(),
        lifetime = policy.transactionCheckRetention,
      )
    }
  }

  override suspend fun getTransactionCheck(transactionId: String): CheckResult? {
    val key = transactionCacheKeyFor(transactionId)
    // No `origin` rewrite, deliberately. `get` forces CACHE because it is offering a stored answer
    // as the answer to a question asked now; this is offering a record of an answer given then, and
    // the origin it was given under is part of what happened — a second transfer to the same
    // account, served from the cache, spent no credit and the record should still say so.
    return mutex.withLock { readRecord(key) }
  }

  override suspend fun removeTransactionCheck(transactionId: String) {
    val key = transactionCacheKeyFor(transactionId)
    mutex.withLock { store.delete(key) }
  }

  // The four maintenance methods below deliberately do not open the cache. Opening generates a
  // data key in the platform key store if there is not one yet, and a host that clears or prunes a
  // cache it has never read should not end up with a key — or, on SQLDelight, a database file — as
  // a side effect. Skipping the open also skips schema invalidation, which is harmless here: the
  // next `get` or `put` performs it, and until then these operate on rows that are about to be
  // dropped anyway.

  override suspend fun remove(query: TraciveQuery) {
    mutex.withLock { store.delete(cacheKeyFor(query)) }
  }

  override suspend fun clear() {
    mutex.withLock {
      // Leaves the meta row, and therefore the nonce counter, in place. Resetting the counter while
      // keeping the same data key is the one way to reissue a nonce, so `clear()` must not do it.
      store.deleteAll()
    }
  }

  override suspend fun prune(): Int = mutex.withLock {
    store.deleteExpired(nowEpochMs(), policy.maxEntries)
  }

  override suspend fun size(): Int = mutex.withLock { store.count() }

  /**
   * Opens the record under [key], or null when there is nothing usable there.
   *
   * Shared by the cache read and the transaction-record read, because the failure handling is the
   * same for both and writing it twice is how the two would drift. Expiry is lazy: deleting the row
   * on the way past keeps the store honest without a background sweeper, so a row the caller can no
   * longer use is gone before the caller is told it is a miss.
   *
   * Caller holds [mutex].
   */
  private suspend fun readRecord(key: String): CheckResult? {
    if (!policy.enabled) return null
    val cipher = openedCipher()
    val row = store.select(key) ?: return null

    if (row.expiresAtEpochMs <= nowEpochMs()) {
      store.delete(key)
      return null
    }

    val plaintext = cipher.open(row.envelope, cacheAssociatedData(key, schemaVersion))
    if (plaintext == null) {
      // Wrong key, tampered row, a record moved from another key, or an envelope version this
      // build does not know. All four are unreadable and all four are treated the same way: drop
      // the row and miss. The caller pays for one server check and gets a correct answer, which
      // is strictly better than any attempt to salvage bytes that failed authentication.
      store.delete(key)
      return null
    }

    val decoded = decode(plaintext)
    if (decoded == null) {
      store.delete(key)
      return null
    }
    return decoded
  }

  /**
   * Seals [record] under [key] for [lifetime], or removes whatever is there when the lifetime is
   * not positive.
   *
   * The removal on a non-positive lifetime is the point rather than a guard: for a cache entry it
   * is how the backend retracts an answer it has already put on a device, and for a transaction
   * record it is how a host that sets `transactionCheckRetention` to zero opts out and takes the
   * records it already wrote with it.
   *
   * Caller holds [mutex], which is what makes the nonce reservation below safe: two writers that
   * obtained the same counter value would seal two records under one nonce.
   */
  private suspend fun writeRecord(key: String, record: CheckResult, lifetime: Duration) {
    if (!policy.enabled) return
    val cipher = openedCipher()
    if (lifetime <= Duration.ZERO) {
      store.delete(key)
      return
    }

    val payload = CachePayloadJson.encodeToString(CheckResult.serializer(), record)
    val nonce = cacheNonce(store.reserveNonceCounter())
    val envelope =
      cipher.seal(payload.encodeToByteArray(), nonce, cacheAssociatedData(key, schemaVersion))

    store.upsert(CacheRow(key, envelope, expiryEpochMs(clock.now(), lifetime)))
  }

  /**
   * Resolves the cipher, invalidating the stored generation when it cannot be reused.
   *
   * Two conditions force a fresh start, and both mean the same thing — the rows on disk cannot be
   * read:
   * - the meta row names a different [schemaVersion], so the payload shape or the folding grammar
   *   changed under them; or
   * - the data key is gone, which happens after an OS keystore reset, a device restore, or a
   *   biometric enrolment change that invalidated it.
   *
   * Starting fresh means dropping every row, discarding the old key, and writing new meta with the
   * nonce counter back at zero. The reset is safe *because* the key is new: a counter is only ever
   * paired with key material it has not been used with.
   */
  private suspend fun openedCipher(): CacheCipher {
    cipher?.let {
      return it
    }

    val meta = store.readMeta()
    val reusableKey =
      meta?.takeIf { it.schemaVersion == schemaVersion }?.let { dataKeyStore.loadKey(it.dataKeyId) }

    val key =
      if (reusableKey != null) {
        reusableKey
      } else {
        store.deleteAll()
        meta?.let { dataKeyStore.deleteKey(it.dataKeyId) }
        dataKeyStore.createKey().also { store.writeMeta(CacheMeta(schemaVersion, it.id, 0L)) }
      }

    // The prune pass on open. Lazy expiry already keeps reads correct; this is about the rows
    // nobody will ever read again, which would otherwise sit in the file until the app is
    // uninstalled. It is also where `CachePolicy.maxEntries` is enforced, so a cache that grew past
    // its bound in one session comes back within it on the next launch.
    store.deleteExpired(nowEpochMs(), policy.maxEntries)

    // `CacheDataKey.bytes` hands out a fresh copy on every read and the cipher copies it again, so
    // this array is a second live copy of the data key that nothing else will ever touch. Wiping it
    // is best-effort on a managed runtime — the collector may already have moved it — but leaving a
    // key on the heap for the life of the process when zeroing it costs one loop is the wrong
    // default. The copy the cipher keeps is unavoidable: a software cipher has to see its key.
    val keyBytes = key.bytes
    return try {
      AesGcmCacheCipher(keyBytes).also { cipher = it }
    } finally {
      keyBytes.wipe()
    }
  }

  private fun decode(plaintext: ByteArray): CheckResult? =
    try {
      CachePayloadJson.decodeFromString(CheckResult.serializer(), plaintext.decodeToString())
    } catch (_: IllegalArgumentException) {
      // Covers both `SerializationException` (a malformed record, or a `riskTier` this build does
      // not know) and the `IllegalArgumentException` a bad ISO-8601 instant raises. Neither is
      // recoverable and neither is worth distinguishing: the record is dropped and the check is
      // re-run.
      null
    }

  private fun nowEpochMs(): Long = clock.now().toEpochMilliseconds()

  /**
   * [now] plus [ttl], in epoch milliseconds, saturating instead of wrapping.
   *
   * A server that sends an absurd `cacheTtlSeconds` — a bug, a signed/unsigned mix-up, or a test
   * fixture — would otherwise overflow `Long` into a negative expiry, which reads as "expired
   * decades ago". Every entry would then miss, every check would go to the server, and every one of
   * them would be billed, with nothing in the logs to say why. Saturating turns that into "expires
   * at the end of time", which is wrong in the harmless direction and visible in a debug screen.
   */
  private fun expiryEpochMs(now: Instant, ttl: Duration): Long {
    val nowMs = now.toEpochMilliseconds()
    val ttlMs = ttl.inWholeMilliseconds
    // `nowMs > 0` guards the guard: with a device clock set before 1970 the subtraction would
    // itself overflow, and the naive check would report an overflow that cannot happen.
    return if (nowMs > 0 && ttlMs > Long.MAX_VALUE - nowMs) Long.MAX_VALUE else nowMs + ttlMs
  }
}
