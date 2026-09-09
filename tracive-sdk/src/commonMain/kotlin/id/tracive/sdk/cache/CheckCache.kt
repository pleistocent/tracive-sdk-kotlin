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
 * Alongside those answers it keeps **transaction records**: the answer that was actually shown for
 * one of the host's transactions, filed under the host's own transaction id. Those are a different
 * kind of row with a different lifetime — see [putTransactionCheck] — and they share this interface
 * because they share everything underneath it: the same database, the same data key, the same
 * envelope, and the same `clear()` on sign-out.
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

  /**
   * Removes the entry for [query] if there is one. Leaves any transaction record referring to it.
   */
  suspend fun remove(query: TraciveQuery)

  /**
   * Files [result] as the answer that was shown for [transactionId].
   *
   * This is what a transaction-detail screen reads back weeks later — "checked before you sent it,
   * no reports found" — and it exists because [get] cannot answer that question. [get] is keyed on
   * the identifier and holds whatever the *latest* check said; a transaction detail has to show
   * what the user was shown **at the time they decided**, and those two diverge the moment the same
   * account is checked again.
   *
   * Three differences from [put], all of them following from that:
   * - **It is a snapshot, not a pointer.** Each call stores its own copy, so a later check of the
   *   same account cannot rewrite what an earlier transaction says. Recording one answer against
   *   two transactions — a user who transfers to the same account twice — is two calls with two ids
   *   and one billed check, and each record is then independent of the other.
   * - **It outlives the cache entry.** The lifetime is
   *   [id.tracive.sdk.model.CachePolicy.transactionCheckRetention] rather than a tier TTL, because
   *   a `NONE` answer stops being *trustworthy* after an hour but never stops being *what was
   *   shown*. The server's `cacheTtlSeconds` is not applied here for the same reason: it retracts a
   *   verdict, and a record of the past cannot be retracted.
   * - **`origin` and `fetchedAt` are stored as given.** A record whose origin is
   *   [id.tracive.sdk.model.Origin.CACHE] is the honest statement that this transaction spent no
   *   credit, which is exactly what a second transfer to the same account looks like.
   *
   * Narrative content is dropped on the way in; [CheckResult.toTransactionRecord] says what
   * survives and why it differs from [CheckResult.toCacheable].
   *
   * Call it **after** the transaction exists. In a transfer flow the check happens on the
   * confirmation screen and the reference number is minted after the PIN, so the id the record is
   * filed under is not knowable when `checkScammer` runs — which is why this is a separate call
   * rather than a parameter on it.
   *
   * @param transactionId the host's own reference for the transaction. Trimmed, then hashed: the id
   *   itself is never written to the database, and a lookup has to supply the same string back.
   * @throws IllegalArgumentException if [transactionId] is blank.
   */
  suspend fun putTransactionCheck(transactionId: String, result: CheckResult)

  /**
   * The answer recorded for [transactionId], or null when there is none.
   *
   * Null covers a transaction that was never recorded, one whose retention has run out, and a
   * record that failed to decrypt or decode. A UI must read null as **"no check is on file"** and
   * show nothing, never as "no reports were found": the second reads as a clean bill of health for
   * a transfer nobody ever checked.
   *
   * Unlike [get] this does not force [id.tracive.sdk.model.Origin.CACHE] onto the result, and it
   * ignores the tier TTL entirely. Both are deliberate: the record is a statement about a past
   * moment, not a cached answer being offered as current.
   *
   * @throws IllegalArgumentException if [transactionId] is blank.
   */
  suspend fun getTransactionCheck(transactionId: String): CheckResult?

  /**
   * Removes the record for [transactionId] if there is one. Leaves the cache entry it was copied
   * from, and every other transaction's record, alone.
   *
   * @throws IllegalArgumentException if [transactionId] is blank.
   */
  suspend fun removeTransactionCheck(transactionId: String)

  /**
   * Removes every entry, transaction records included.
   *
   * Call this on sign-out. The entries belong to the user who was authorised when they were
   * fetched, and the next user of the device must not inherit them. That covers the transaction
   * records too, so a host whose transaction history outlives a Tracive sign-out should expect the
   * check card on those older transactions to go quiet — the verdict was read under a grant that no
   * longer exists.
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
   *
   * Transaction records are pruned by the same pass and counted by the same cap. They are evicted
   * last, because eviction drops whatever is closest to expiry and their retention outlives every
   * tier TTL — which is the right way round: a cache entry that goes costs one credit to get back,
   * and a transaction record that goes cannot be got back at all. A host that records more
   * transactions than [id.tracive.sdk.model.CachePolicy.maxEntries] should raise that bound.
   */
  suspend fun prune(): Int

  /**
   * How many rows are stored — entries and transaction records together, expired ones included.
   * Useful in a debug screen; cheap.
   */
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

  // Transaction records go with the cache, because they are rows in it. A host that wants the check
  // card on its transaction-detail screen has to wire a real cache — there is nowhere else to put
  // them, and quietly storing them somewhere this object controls would be a second, undocumented
  // store on a device whose owner was told there is none.

  override suspend fun putTransactionCheck(transactionId: String, result: CheckResult) = Unit

  override suspend fun getTransactionCheck(transactionId: String): CheckResult? = null

  override suspend fun removeTransactionCheck(transactionId: String) = Unit

  override suspend fun clear() = Unit

  override suspend fun prune(): Int = 0

  override suspend fun size(): Int = 0

  override fun toString(): String = "DisabledCheckCache"
}
