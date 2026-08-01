package id.tracive.sdk.cache

import id.tracive.sdk.model.CheckResult
import id.tracive.sdk.query.TraciveQuery

/**
 * The local store of previous answers.
 *
 * Reachable as `client.cache` so a host app can clear it on sign-out and prune it on a schedule.
 * The client reads and writes it on its own during `checkScammer`; calling these methods directly
 * is for lifecycle work, not for the check path.
 *
 * What the cache is for, in order of importance:
 * 1. **Money.** Every server check is billed, a miss included. A hit costs nothing.
 * 2. **Offline.** A hit is an answer with no network at all.
 * 3. **Latency.** No round trip.
 *
 * What it stores, and what it deliberately does not:
 * - The key is `SHA-256(query.searchQueryIndex)`. The identifier itself — the phone number, the
 *   account number, the wallet address — is never written to disk, in any form.
 * - The value is encrypted with AES-256-GCM under a key held in the platform key store.
 * - `topChronologies`, `comments`, `billing` and `checkId` are dropped on the way in — see
 *   [CheckResult.toCacheable]. What survives is invariant across `detail`, but not across `locale`:
 *   `tags` and `disclaimer` are localized and are kept. Only a minimal check, which sends no
 *   `locale`, is ever written here, so there is one form of the answer to store.
 *
 * Everything here suspends because the backing store does real I/O. Implementations must tolerate
 * concurrent callers.
 */
interface CheckCache {

  /**
   * The live entry for [query], or null when there is none.
   *
   * Null covers a missing entry, an expired one, and an entry that failed to decrypt or decode —
   * all three mean the same thing to a caller, and all three leave the cache clean afterwards. An
   * expired or unreadable row is deleted as it is found, so the cache does not need a background
   * sweeper to stay correct.
   *
   * A returned result always has `origin = Origin.CACHE` and keeps its original `fetchedAt`, so a
   * UI can say how old the answer is.
   *
   * A caller serving `Source.CACHE` must turn null into [id.tracive.sdk.TraciveException.CacheMiss]
   * rather than an empty [CheckResult]: an empty result reads as "this identifier is clean", which
   * is the most dangerous wrong answer this SDK can give.
   */
  suspend fun get(query: TraciveQuery): CheckResult?

  /**
   * Stores [result] for [query], replacing any existing entry.
   *
   * The lifetime is `CachePolicy.effectiveTtl(result.riskTier, result.cacheTtlSeconds)`: the tier
   * default, unless the server sent an override. A TTL of zero stores nothing and removes whatever
   * was there — that is how the backend says "do not keep this answer".
   *
   * Only the cacheable subset is written, whatever is passed in.
   */
  suspend fun put(query: TraciveQuery, result: CheckResult)

  /** Removes the entry for [query] if there is one. */
  suspend fun remove(query: TraciveQuery)

  /**
   * Removes every entry.
   *
   * Call this on sign-out. The entries belong to the user who was authorised when they were
   * fetched, and the next user of the device must not inherit them.
   */
  suspend fun clear()

  /**
   * Deletes expired entries, evicts anything over [id.tracive.sdk.model.CachePolicy.maxEntries],
   * and returns how many rows went in total.
   *
   * Expiry is enforced lazily on read as well, so this is housekeeping for disk space rather than
   * for correctness. Implementations also prune once when the cache is first opened, which is where
   * the entry cap is normally applied; a host that writes thousands of entries in a single session
   * should call this on a schedule rather than wait for the next launch.
   */
  suspend fun prune(): Int

  /** How many entries are stored, expired ones included. Useful in a debug screen; cheap. */
  suspend fun size(): Int
}

/**
 * A cache that stores nothing and always misses.
 *
 * For `CachePolicy(enabled = false)`, for tests that need every check to hit the transport, and for
 * a host that would rather pay per check than keep any check history on the device.
 */
object DisabledCheckCache : CheckCache {

  override suspend fun get(query: TraciveQuery): CheckResult? = null

  override suspend fun put(query: TraciveQuery, result: CheckResult) = Unit

  override suspend fun remove(query: TraciveQuery) = Unit

  override suspend fun clear() = Unit

  override suspend fun prune(): Int = 0

  override suspend fun size(): Int = 0

  override fun toString(): String = "DisabledCheckCache"
}
