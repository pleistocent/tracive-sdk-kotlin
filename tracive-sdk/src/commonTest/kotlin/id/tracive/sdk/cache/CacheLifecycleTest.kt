package id.tracive.sdk.cache

import id.tracive.sdk.model.CachePolicy
import id.tracive.sdk.model.RiskTier
import id.tracive.sdk.query.TraciveQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

/**
 * Opening, invalidating, clearing and pruning a cache — the operations that decide whether stale or
 * unreadable records linger, and whether a nonce can ever be reused.
 */
class CacheLifecycleTest {

  private val query = TraciveQuery.phone("+6281234567891")

  // ------------------------------------------- schema-version invalidation ----

  @Test
  fun bumpingTheSchemaVersionDiscardsEverythingWrittenUnderTheOldOne() = runTest {
    // The upgrade path. When the payload shape or the folding grammar changes, entries written
    // under the old rules are keyed on strings the new rules would never produce — without the
    // bump they would sit there unreachable until their TTL ran out, which for a `HIGH` verdict is
    // thirty days of dead weight.
    val store = InMemoryCacheRecordStore()
    val keyStore = testKeyStore()
    val clock = TestClock.atStart()

    val before = DefaultCheckCache(store, keyStore, CachePolicy.Default, clock, schemaVersion = 1)
    before.put(query, serverResult(riskTier = RiskTier.HIGH))
    assertNotNull(before.get(query))
    val oldMeta = assertNotNull(store.readMeta())

    val after = DefaultCheckCache(store, keyStore, CachePolicy.Default, clock, schemaVersion = 2)
    assertNull(after.get(query), "An entry from an older schema version must not be served.")
    assertEquals(0, after.size())

    val newMeta = assertNotNull(store.readMeta())
    assertEquals(2, newMeta.schemaVersion)
    assertNotEquals(
      oldMeta.dataKeyId,
      newMeta.dataKeyId,
      "A new generation of records must get a new data key — that is what makes restarting the " +
        "nonce counter at zero safe.",
    )
    assertEquals(0L, newMeta.nonceCounter)
  }

  @Test
  fun losingTheDataKeyIsHandledAsAnEmptyCacheRatherThanAnError() = runTest {
    // Happens for real: an OS keystore reset, a device restore onto new hardware, or a biometric
    // enrolment change that invalidates the key. The rows are unreadable, so the only correct
    // behaviour is to drop them and start again — quietly, because the user did nothing wrong and
    // the cost is one re-fetch.
    val store = InMemoryCacheRecordStore()
    val keyStore = testKeyStore()
    val clock = TestClock.atStart()

    DefaultCheckCache(store, keyStore, CachePolicy.Default, clock).put(query, serverResult())
    val meta = assertNotNull(store.readMeta())
    keyStore.deleteKey(meta.dataKeyId)

    val reopened = DefaultCheckCache(store, keyStore, CachePolicy.Default, clock)
    assertNull(reopened.get(query))
    assertEquals(0, reopened.size())
    assertNotEquals(meta.dataKeyId, assertNotNull(store.readMeta()).dataKeyId)
  }

  @Test
  fun reopeningWithTheSameSchemaAndKeyKeepsTheRecords() = runTest {
    // The control for the two tests above: invalidation must be triggered by something, not be the
    // default. A cache that threw its records away on every launch would be indistinguishable from
    // no cache at all, and the bill would say so.
    val store = InMemoryCacheRecordStore()
    val keyStore = testKeyStore()
    val clock = TestClock.atStart()

    DefaultCheckCache(store, keyStore, CachePolicy.Default, clock)
      .put(query, serverResult(riskTier = RiskTier.HIGH))

    val reopened = DefaultCheckCache(store, keyStore, CachePolicy.Default, clock)
    assertEquals(RiskTier.HIGH, assertNotNull(reopened.get(query)).riskTier)
  }

  @Test
  fun openingPrunesWhatHasAlreadyExpired() = runTest {
    val store = InMemoryCacheRecordStore()
    val keyStore = testKeyStore()
    val clock = TestClock.atStart()

    val first = DefaultCheckCache(store, keyStore, CachePolicy.Default, clock)
    first.put(TraciveQuery.phone("+6281111111111"), serverResult(riskTier = RiskTier.NONE))
    first.put(TraciveQuery.phone("+6282222222222"), serverResult(riskTier = RiskTier.NONE))
    assertEquals(2, first.size())

    clock.advance(2.hours)

    // A brand-new instance over the same rows, as happens on the next app launch.
    val next = DefaultCheckCache(store, keyStore, CachePolicy.Default, clock)
    next.get(TraciveQuery.phone("+6283333333333"))

    assertEquals(0, next.size(), "The prune on open must clear rows nobody will read again.")
  }

  // ------------------------------------------------------------ nonce safety ----

  @Test
  fun everyRecordGetsItsOwnNonce() = runTest {
    val store = InMemoryCacheRecordStore()
    val cache = DefaultCheckCache(store, testKeyStore(), CachePolicy.Default, TestClock.atStart())
    val queries =
      (0 until 25).map { TraciveQuery.phone("+62812345678${it.toString().padStart(2, '0')}") }

    queries.forEach { cache.put(it, serverResult()) }

    val nonces = queries.mapNotNull { store.select(cacheKeyFor(it))?.envelope?.nonce() }
    assertEquals(queries.size, nonces.size)
    assertEquals(queries.size, nonces.toSet().size, "A nonce may never repeat under one key.")
  }

  /**
   * A new generation of records must get new key *material*, not merely a new id.
   *
   * The whole safety argument for a counter-based nonce is that a cache which restarts its counter
   * at zero is also handed a key that counter has never been used with. A key store that reissues
   * the same bytes under a new id satisfies the `dataKeyId` assertions elsewhere in this file while
   * breaking the assumption they exist to protect.
   */
  @Test
  fun eachGeneratedKeyIsDifferentMaterialAndTheEarlierOneStaysReadable() = runTest {
    val keyStore = testKeyStore()

    val first = keyStore.createKey()
    val second = keyStore.createKey()

    assertNotEquals(first.id, second.id)
    assertFalse(
      first.bytes.contentEquals(second.bytes),
      "createKey() reissued the same key bytes; the nonce counter restarts at zero for every new " +
        "key, so reissuing one is a repeated (key, nonce) pair",
    )
    // Both generations stay loadable: a cache still holding the first key has rows it can read.
    assertTrue(first.bytes.contentEquals(assertNotNull(keyStore.loadKey(first.id)).bytes))
    assertTrue(second.bytes.contentEquals(assertNotNull(keyStore.loadKey(second.id)).bytes))
  }

  /**
   * Two caches over one key store must not seal their records under the same (key, nonce) pair.
   *
   * Nothing stops a host from doing this, and it is the route the documentation's "never pair this
   * with a persistent record store" warning does not cover: each cache gets its own record store,
   * so each starts its counter at zero. If the key store hands both the same bytes, both first
   * records are sealed under key K and nonce 0 — and for identical plaintext the two envelopes come
   * out byte for byte identical, which is what this asserts against.
   */
  @Test
  fun twoCachesSharingOneKeyStoreDoNotSealUnderOneNonceAndKey() = runTest {
    val keyStore = testKeyStore()
    val clock = TestClock.atStart()
    val storeA = InMemoryCacheRecordStore()
    val storeB = InMemoryCacheRecordStore()

    DefaultCheckCache(storeA, keyStore, CachePolicy.Default, clock).put(query, serverResult())
    DefaultCheckCache(storeB, keyStore, CachePolicy.Default, clock).put(query, serverResult())

    val key = cacheKeyFor(query)
    val a = assertNotNull(storeA.select(key)).envelope
    val b = assertNotNull(storeB.select(key)).envelope
    assertEquals(a.nonce(), b.nonce(), "both counters legitimately start at zero")
    assertFalse(
      a.contentEquals(b),
      "identical envelopes for identical plaintext under identical nonces means the two caches " +
        "share one key, which is exactly the (key, nonce) reuse AES-GCM does not survive",
    )
  }

  @Test
  fun rewritingOneEntryStillAdvancesTheNonce() = runTest {
    // The easiest way to reuse a nonce would be to derive it from the cache key. Re-checking the
    // same number is the single most common thing a user does, so that mistake would produce two
    // records under one (key, nonce) pair almost immediately — and a repeated pair in AES-GCM
    // leaks the authentication subkey for every record written under that key.
    val store = InMemoryCacheRecordStore()
    val cache = DefaultCheckCache(store, testKeyStore(), CachePolicy.Default, TestClock.atStart())

    cache.put(query, serverResult(riskTier = RiskTier.NONE))
    val first = assertNotNull(store.select(cacheKeyFor(query))).envelope.nonce()

    cache.put(query, serverResult(riskTier = RiskTier.HIGH))
    val second = assertNotNull(store.select(cacheKeyFor(query))).envelope.nonce()

    assertNotEquals(first, second)
  }

  @Test
  fun theNonceCounterSurvivesReopeningTheCache() = runTest {
    val store = InMemoryCacheRecordStore()
    val keyStore = testKeyStore()
    val clock = TestClock.atStart()

    DefaultCheckCache(store, keyStore, CachePolicy.Default, clock).put(query, serverResult())
    val first = assertNotNull(store.select(cacheKeyFor(query))).envelope.nonce()

    val second = TraciveQuery.phone("+6285555555555")
    DefaultCheckCache(store, keyStore, CachePolicy.Default, clock).put(second, serverResult())

    assertNotEquals(
      first,
      assertNotNull(store.select(cacheKeyFor(second))).envelope.nonce(),
      "The counter lives in the store, not in the instance — an app restart must not rewind it.",
    )
  }

  @Test
  fun clearingDoesNotRewindTheNonceCounter() = runTest {
    // `clear()` empties the entries and deliberately leaves the metadata row. Dropping the metadata
    // too would restart the counter while the data key stayed the same, which is the one
    // combination AES-GCM does not survive.
    val store = InMemoryCacheRecordStore()
    val cache = DefaultCheckCache(store, testKeyStore(), CachePolicy.Default, TestClock.atStart())

    cache.put(query, serverResult())
    val before = assertNotNull(store.select(cacheKeyFor(query))).envelope.nonce()

    cache.clear()
    cache.put(query, serverResult())

    assertNotEquals(before, assertNotNull(store.select(cacheKeyFor(query))).envelope.nonce())
  }

  @Test
  fun concurrentWritersNeverShareANonce() = runTest {
    val store = InMemoryCacheRecordStore()
    val cache = DefaultCheckCache(store, testKeyStore(), CachePolicy.Default, TestClock.atStart())
    val queries =
      (0 until 20).map { TraciveQuery.phone("+62899000000${it.toString().padStart(2, '0')}") }

    queries.map { async { cache.put(it, serverResult()) } }.awaitAll()

    val nonces = queries.map { assertNotNull(store.select(cacheKeyFor(it))).envelope.nonce() }
    assertEquals(nonces.size, nonces.toSet().size)
  }

  // ---------------------------------------------------------- housekeeping ----

  @Test
  fun clearRemovesEveryEntry() = runTest {
    // Called on sign-out. The entries belong to whoever was authorised when they were fetched, and
    // the next person to use the device must not inherit that person's check history.
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, TestClock.atStart())
    cache.put(query, serverResult())
    cache.put(TraciveQuery.email("budi@gmail.com"), serverResult())

    cache.clear()

    assertEquals(0, cache.size())
    assertNull(cache.get(query))
  }

  @Test
  fun removeTakesOutOneEntryAndLeavesTheRest() = runTest {
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, TestClock.atStart())
    val other = TraciveQuery.email("budi@gmail.com")
    cache.put(query, serverResult())
    cache.put(other, serverResult())

    cache.remove(query)

    assertNull(cache.get(query))
    assertNotNull(cache.get(other))
  }

  @Test
  fun clearingAndPruningAnUntouchedCacheDoNotCreateAKey() = runTest {
    // A host that clears a cache it never read should not end up with a data key in the platform
    // key store — or, on the SQLDelight cache, a database file — as a side effect of tidying up.
    val store = InMemoryCacheRecordStore()
    val cache = DefaultCheckCache(store, testKeyStore(), CachePolicy.Default, TestClock.atStart())

    cache.clear()
    assertEquals(0, cache.prune())
    assertEquals(0, cache.size())

    assertNull(store.readMeta())
  }

  // ------------------------------------------------------------- entry cap ----

  /**
   * The cap is the only thing that bounds the file. Nothing but a TTL ever removes a row otherwise,
   * and a `HIGH` verdict lives thirty days, so an app that checks every number it sees would grow
   * an encrypted database on the user's device that nobody is watching.
   */
  @Test
  fun pruningEvictsDownToTheEntryCap() = runTest {
    val clock = TestClock.atStart()
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy(maxEntries = 2), clock)
    val shortest = TraciveQuery.phone("+6281111111111")
    val middle = TraciveQuery.phone("+6282222222222")
    val longest = TraciveQuery.phone("+6283333333333")

    cache.put(shortest, serverResult(riskTier = RiskTier.NONE)) // 1 hour
    cache.put(middle, serverResult(riskTier = RiskTier.CAUTION)) // 24 hours
    cache.put(longest, serverResult(riskTier = RiskTier.HIGH)) // 30 days
    assertEquals(3, cache.size())

    assertEquals(1, cache.prune(), "prune reports the rows it removed, eviction included")

    assertEquals(2, cache.size())
    // Closest to expiry goes first: it is the row with the least value left, and the cheapest to
    // re-fetch if the user does ask again.
    assertNull(cache.get(shortest), "the entry nearest expiry survived the cap")
    assertNotNull(cache.get(middle))
    assertNotNull(cache.get(longest))
  }

  @Test
  fun aCacheThatIsWithinItsCapLosesNothing() = runTest {
    // The control. A cap that evicted whether or not the cache was over it would look identical in
    // the test above and would throw away answers the user has already paid for.
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy(maxEntries = 5), TestClock.atStart())
    cache.put(query, serverResult(riskTier = RiskTier.HIGH))
    cache.put(TraciveQuery.email("budi@gmail.com"), serverResult(riskTier = RiskTier.NONE))

    assertEquals(0, cache.prune())
    assertEquals(2, cache.size())
  }

  @Test
  fun anEntryCapOfZeroMeansNoCapAtAll() = runTest {
    // The opt-out for a host that would rather manage the file itself. It has to mean "unbounded"
    // rather than "keep nothing", or turning the cap off would empty the cache on every open.
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy(maxEntries = 0), TestClock.atStart())
    (0 until 12).forEach {
      cache.put(TraciveQuery.phone("+62812345670${it.toString().padStart(2, '0')}"), serverResult())
    }

    assertEquals(0, cache.prune())
    assertEquals(12, cache.size())
  }

  /** The default is a bound, not the absence of one, and it is the same number in all four SDKs. */
  @Test
  fun theDefaultPolicyIsBounded() {
    assertEquals(5000, CachePolicy.Default.maxEntries)
  }

  @Test
  fun theDisabledCacheAlwaysMisses() = runTest {
    // The honest opt-out for a host that would rather pay per check than keep any check history.
    DisabledCheckCache.put(query, serverResult())

    assertNull(DisabledCheckCache.get(query))
    assertEquals(0, DisabledCheckCache.size())
    assertEquals(0, DisabledCheckCache.prune())
  }

  @Test
  fun theCachesDoNotPrintAnythingAboutTheirContents() = runTest {
    assertTrue(InMemoryCheckCache(testKeyStore()).toString() == "InMemoryCheckCache")
    assertTrue(DisabledCheckCache.toString() == "DisabledCheckCache")
  }

  /**
   * The 12 nonce bytes an envelope carries, as hex — the envelope layout is version, nonce, body.
   */
  private fun ByteArray.nonce(): String = copyOfRange(1, 13).toHex()
}
