package id.tracive.sdk.cache

/**
 * The rows a cache is made of, with no knowledge of encryption, TTLs or
 * [id.tracive.sdk.model.CheckResult].
 *
 * Everything interesting — key derivation, encryption, expiry, schema invalidation — lives in
 * [DefaultCheckCache] on top of this interface, so that logic is written once and tested once
 * regardless of whether the rows end up in SQLDelight or in a map. What is left here is storage:
 * the part that differs per backend and has nothing to reason about.
 *
 * Implementations may assume the caller serialises access; [DefaultCheckCache] holds a mutex.
 */
internal interface CacheRecordStore {

  /** The single metadata row, or null when the store has never been initialised. */
  suspend fun readMeta(): CacheMeta?

  /** Writes the metadata row, replacing any existing one. */
  suspend fun writeMeta(meta: CacheMeta)

  /**
   * Durably increments the nonce counter and returns the value reserved for this write.
   *
   * Increment-then-return, not return-then-increment: a crash between the two must lose a counter
   * value, never hand the same one out twice. See `cacheNonce` for why that matters so much.
   */
  suspend fun reserveNonceCounter(): Long

  /** The row under [cacheKey], or null. */
  suspend fun select(cacheKey: String): CacheRow?

  /** Inserts [row], replacing any row with the same key. */
  suspend fun upsert(row: CacheRow)

  /** Removes the row under [cacheKey] if present. */
  suspend fun delete(cacheKey: String)

  /** Removes every row. Must leave the metadata row — and therefore the nonce counter — intact. */
  suspend fun deleteAll()

  /**
   * Removes every row whose expiry is at or before [nowEpochMs], then evicts down to [maxEntries].
   * Returns how many rows went in total.
   *
   * [maxEntries] of zero or less means no cap. Eviction keeps the rows that live longest and drops
   * the ones closest to expiry, which have the least value left; ties break on [CacheRow.cacheKey]
   * descending, arbitrarily but identically in every implementation, so the two stores cannot
   * disagree about which row survived.
   */
  suspend fun deleteExpired(nowEpochMs: Long, maxEntries: Int): Int

  /** How many rows the store holds, expired ones included. */
  suspend fun count(): Int
}

/**
 * One stored record.
 *
 * Note what is *not* here. There is no query, no identifier, no risk tier and no report count in
 * cleartext: [cacheKey] is a SHA-256 digest and [envelope] is ciphertext. Someone who reads the
 * database file off a rooted device learns how many identifiers this user checked and roughly when
 * each answer stops being trusted, and nothing else. Adding a plaintext column — a tier, a "found"
 * flag, anything convenient to query on — would give that away, so do not.
 */
internal class CacheRow(
  /** `SHA-256(searchQueryIndex)`, lower-case hex. */
  val cacheKey: String,
  /** The sealed record; see [AesGcmCacheCipher] for the layout. */
  val envelope: ByteArray,
  /** Wall-clock expiry, in epoch milliseconds. Cleartext because expiry has to be queryable. */
  val expiresAtEpochMs: Long,
)

/** The store's single metadata row. */
internal class CacheMeta(
  /** The [id.tracive.sdk.model.CachePolicy.SCHEMA_VERSION] the rows were written under. */
  val schemaVersion: Int,
  /** Which [CacheDataKey] the rows are encrypted with. */
  val dataKeyId: String,
  /** The highest nonce counter value handed out so far. */
  val nonceCounter: Long,
)

/**
 * A [CacheRecordStore] backed by a map, for [InMemoryCheckCache] and for tests.
 *
 * Nothing here is written to disk, which is why it is also the honest choice for a host that wants
 * the credit savings of caching without an encrypted database file on the device.
 */
internal class InMemoryCacheRecordStore : CacheRecordStore {

  private val rows = LinkedHashMap<String, CacheRow>()
  private var meta: CacheMeta? = null

  override suspend fun readMeta(): CacheMeta? = meta

  override suspend fun writeMeta(meta: CacheMeta) {
    this.meta = meta
  }

  override suspend fun reserveNonceCounter(): Long {
    val current =
      meta ?: error("The cache metadata row must be written before a nonce is reserved.")
    val next = current.nonceCounter + 1
    meta = CacheMeta(current.schemaVersion, current.dataKeyId, next)
    return next
  }

  override suspend fun select(cacheKey: String): CacheRow? = rows[cacheKey]

  override suspend fun upsert(row: CacheRow) {
    rows[row.cacheKey] = row
  }

  override suspend fun delete(cacheKey: String) {
    rows.remove(cacheKey)
  }

  override suspend fun deleteAll() {
    rows.clear()
  }

  override suspend fun deleteExpired(nowEpochMs: Long, maxEntries: Int): Int {
    val doomed = rows.values.filter { it.expiresAtEpochMs <= nowEpochMs }.map { it.cacheKey }
    doomed.forEach { rows.remove(it) }
    if (maxEntries <= 0 || rows.size <= maxEntries) return doomed.size

    // Same order as the SQL store: keep the longest-lived rows, drop the ones closest to expiry.
    val overCap =
      rows.values
        .sortedWith(
          compareByDescending<CacheRow> { it.expiresAtEpochMs }.thenByDescending { it.cacheKey }
        )
        .drop(maxEntries)
        .map { it.cacheKey }
    overCap.forEach { rows.remove(it) }
    return doomed.size + overCap.size
  }

  override suspend fun count(): Int = rows.size
}
