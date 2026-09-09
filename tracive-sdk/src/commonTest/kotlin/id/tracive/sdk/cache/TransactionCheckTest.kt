package id.tracive.sdk.cache

import id.tracive.sdk.crypto.Sha256
import id.tracive.sdk.model.CachePolicy
import id.tracive.sdk.model.Origin
import id.tracive.sdk.model.RiskTier
import id.tracive.sdk.query.TraciveQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * The record of what a user was shown before they sent money, filed under the host's transaction
 * id.
 *
 * The whole point of these rows is that they answer a question the cache cannot. The cache holds
 * the *latest* answer for an identifier and expires it as soon as it stops being trustworthy; a
 * transaction-detail screen has to show the answer that was on screen **when the user decided**,
 * months later, and it must not change when that account is checked again. Everything below is one
 * of those two properties.
 */
class TransactionCheckTest {

  private val scammer = TraciveQuery.bankAccount("IDN", "BCA", "7710442951")
  private val firstTransfer = "TRF26090814320771"
  private val secondTransfer = "TRF26090910160442"

  // ------------------------------------------------ outliving the cache entry ----

  @Test
  fun theRecordOutlivesTheCacheEntryItWasTakenFrom() = runTest {
    // The failure this exists to prevent: a `NONE` answer is cached for an hour, so by the next
    // morning the transaction detail would have no card at all — for a transfer that genuinely was
    // checked. The record has to survive on its own retention.
    val clock = TestClock.atStart()
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, clock)
    val shown = serverResult(riskTier = RiskTier.NONE)

    cache.put(scammer, shown)
    cache.putTransactionCheck(firstTransfer, shown)

    clock.advance(2.hours)

    assertNull(cache.get(scammer), "A NONE entry lives one hour; this asserts the premise.")
    val record = assertNotNull(cache.getTransactionCheck(firstTransfer))
    assertEquals(RiskTier.NONE, record.riskTier)
    assertEquals(TestClock.START, record.fetchedAt, "The card says when the check happened.")
  }

  @Test
  fun retentionEndsTheRecordAndDropsTheRow() = runTest {
    val store = InMemoryCacheRecordStore()
    val clock = TestClock.atStart()
    val cache = DefaultCheckCache(store, testKeyStore(), CachePolicy.Default, clock)

    cache.putTransactionCheck(firstTransfer, serverResult())
    clock.advance(90.days - 1.minutes)
    assertNotNull(cache.getTransactionCheck(firstTransfer))

    clock.advance(2.minutes)
    assertNull(cache.getTransactionCheck(firstTransfer))
    assertNull(
      store.select(transactionCacheKeyFor(firstTransfer)),
      "An expired record must be deleted as it is found, not left to be re-read.",
    )
  }

  @Test
  fun theServerTtlOverrideDoesNotRetractARecord() = runTest {
    // `cacheTtlSeconds` is the backend's lever for un-trusting a verdict it no longer stands
    // behind.
    // Applied to a record it would erase the evidence that a warning was shown — which is precisely
    // what a partner needs when the transfer is disputed.
    val clock = TestClock.atStart()
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, clock)
    val retracted = serverResult(riskTier = RiskTier.HIGH, cacheTtlSeconds = 0L)

    cache.put(scammer, retracted)
    cache.putTransactionCheck(firstTransfer, retracted)

    assertNull(cache.get(scammer), "A zero server TTL retracts the cached answer.")
    assertEquals(RiskTier.HIGH, assertNotNull(cache.getTransactionCheck(firstTransfer)).riskTier)
  }

  @Test
  fun zeroRetentionStoresNothingAndDropsWhatIsAlreadyThere() = runTest {
    val clock = TestClock.atStart()
    val keeping = CachePolicy.Default
    val store = InMemoryCacheRecordStore()

    DefaultCheckCache(store, testKeyStore(), keeping, clock)
      .putTransactionCheck(firstTransfer, serverResult())
    assertNotNull(store.select(transactionCacheKeyFor(firstTransfer)))

    val optedOut =
      DefaultCheckCache(
        store,
        testKeyStore(),
        keeping.copy(transactionCheckRetention = kotlin.time.Duration.ZERO),
        clock,
      )
    optedOut.putTransactionCheck(firstTransfer, serverResult())

    assertNull(
      store.select(transactionCacheKeyFor(firstTransfer)),
      "Turning retention off must also take the records already written with it.",
    )
  }

  // --------------------------------------------- one check, several transactions ----

  @Test
  fun oneCheckServesSeveralTransactions() = runTest {
    // The everyday case: a user transfers to the same account twice. The second transfer is a cache
    // hit, so it is not billed again — and both transaction details still get their own card.
    val clock = TestClock.atStart()
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, clock)

    cache.put(scammer, serverResult(riskTier = RiskTier.HIGH))
    val shownFirst = assertNotNull(cache.get(scammer))
    cache.putTransactionCheck(firstTransfer, shownFirst)

    clock.advance(7.days)

    val shownAgain = assertNotNull(cache.get(scammer), "A HIGH answer is still live after a week.")
    cache.putTransactionCheck(secondTransfer, shownAgain)

    val first = assertNotNull(cache.getTransactionCheck(firstTransfer))
    val second = assertNotNull(cache.getTransactionCheck(secondTransfer))
    assertEquals(RiskTier.HIGH, first.riskTier)
    assertEquals(RiskTier.HIGH, second.riskTier)
    assertEquals(first.fetchedAt, second.fetchedAt, "Both cards report the one check they shared.")
    assertEquals(
      Origin.CACHE,
      second.origin,
      "The second transfer spent no credit, and its record has to say so.",
    )
  }

  @Test
  fun aLaterCheckOfTheSameAccountLeavesAnEarlierTransactionAlone() = runTest {
    // The reason a record is a snapshot rather than a pointer into the cache. The account is clean
    // in September and reported by October; a pointer would rewrite September's card to say the
    // user
    // was warned — an audit trail that blames them for a warning they were never shown.
    val clock = TestClock.atStart()
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, clock)

    val clean = serverResult(riskTier = RiskTier.NONE, fetchedAt = clock.now())
    cache.put(scammer, clean)
    cache.putTransactionCheck(firstTransfer, clean)

    clock.advance(30.days)

    val reported = serverResult(riskTier = RiskTier.HIGH, fetchedAt = clock.now())
    cache.put(scammer, reported)
    cache.putTransactionCheck(secondTransfer, reported)

    val first = assertNotNull(cache.getTransactionCheck(firstTransfer))
    assertEquals(RiskTier.NONE, first.riskTier, "September's card must still say what it said.")
    assertEquals(TestClock.START, first.fetchedAt)
    assertEquals(RiskTier.HIGH, assertNotNull(cache.getTransactionCheck(secondTransfer)).riskTier)
  }

  // ------------------------------------------------------ what is on the disk ----

  @Test
  fun theTransactionIdIsNeverStoredInCleartext() = runTest {
    val store = InMemoryCacheRecordStore()
    val cache = DefaultCheckCache(store, testKeyStore(), CachePolicy.Default, TestClock.atStart())

    cache.putTransactionCheck(firstTransfer, serverResult())

    val expected = TRANSACTION_KEY_PREFIX + Sha256.digest(firstTransfer.encodeToByteArray()).toHex()
    val row = assertNotNull(store.select(expected), "The row must be filed under the hashed id.")
    assertTrue(!row.cacheKey.contains(firstTransfer), "The row key must not carry the reference.")
    assertTrue(
      !row.envelope.decodeToString().contains(firstTransfer),
      "The payload is ciphertext, so the reference cannot appear in it either.",
    )
  }

  @Test
  fun narrativeContentIsDroppedButTheCallsOwnFieldsSurvive() = runTest {
    // The projection differs from the cache's on purpose. `checkId` and `billing` describe the one
    // call this record *is*, so they are what a support agent reconciles a dispute against; the
    // report narratives are other people's prose and have no place in a record that lives 90 days.
    val store = InMemoryCacheRecordStore()
    val keyStore = testKeyStore()
    val cache = DefaultCheckCache(store, keyStore, CachePolicy.Default, TestClock.atStart())

    cache.putTransactionCheck(firstTransfer, serverResult())

    val record = assertNotNull(cache.getTransactionCheck(firstTransfer))
    assertNull(record.topChronologies, "Report narratives must not be archived on the device.")
    assertNull(record.comments, "Nor the comments.")
    assertEquals("chk_01J9ZQ", record.checkId, "The check id is what a dispute is reconciled by.")
    assertEquals(1, assertNotNull(record.billing).unitsCharged)
    // Not forced to CACHE the way `get` forces it. This is the only assertion standing between the
    // documented behaviour and a one-line "fix" that mirrors `get` — without it, a billed check
    // could be read back claiming the transfer spent nothing, and nothing would fail.
    assertEquals(
      Origin.SERVER,
      record.origin,
      "A live check stays a live check on the record; only `get` restamps the origin.",
    )

    val plaintext = decryptRecord(store, keyStore, transactionCacheKeyFor(firstTransfer))
    assertTrue(!plaintext.contains(NARRATIVE_MARKER), "Chronology text was written to the store.")
    assertTrue(!plaintext.contains(COMMENT_MARKER), "Comment text was written to the store.")
  }

  @Test
  fun aRecordCannotBeMovedOntoACacheEntrysRow() = runTest {
    // The key namespace is bound into each record's associated data, so the two kinds of row cannot
    // be swapped for one another — a 90-day-old record must not be servable as a live answer to a
    // check, whatever someone with write access to the database file does.
    val store = InMemoryCacheRecordStore()
    val cache = DefaultCheckCache(store, testKeyStore(), CachePolicy.Default, TestClock.atStart())

    cache.put(scammer, serverResult(riskTier = RiskTier.HIGH))
    cache.putTransactionCheck(firstTransfer, serverResult(riskTier = RiskTier.NONE))

    val record = assertNotNull(store.select(transactionCacheKeyFor(firstTransfer)))
    val entry = assertNotNull(store.select(cacheKeyFor(scammer)))
    store.upsert(CacheRow(entry.cacheKey, record.envelope, entry.expiresAtEpochMs))

    assertNull(cache.get(scammer), "A transaction record must not authenticate as a cache entry.")
  }

  // ------------------------------------------------------------- lifecycle ----

  @Test
  fun removingOneSideLeavesTheOther() = runTest {
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, TestClock.atStart())
    val shown = serverResult(riskTier = RiskTier.HIGH)

    cache.put(scammer, shown)
    cache.putTransactionCheck(firstTransfer, shown)
    cache.putTransactionCheck(secondTransfer, shown)

    // Forcing a fresh check must not wipe the history that references the same account.
    cache.remove(scammer)
    assertNull(cache.get(scammer))
    assertNotNull(cache.getTransactionCheck(firstTransfer))

    cache.removeTransactionCheck(firstTransfer)
    assertNull(cache.getTransactionCheck(firstTransfer))
    assertNotNull(
      cache.getTransactionCheck(secondTransfer),
      "Records are independent; dropping one must not touch another.",
    )
  }

  @Test
  fun signOutTakesTheRecordsWithIt() = runTest {
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, TestClock.atStart())

    cache.put(scammer, serverResult())
    cache.putTransactionCheck(firstTransfer, serverResult())
    cache.clear()

    assertNull(cache.getTransactionCheck(firstTransfer), "clear() is what sign-out calls.")
    assertEquals(0, cache.size())
  }

  @Test
  fun recordsAreEvictedLastWhenTheEntryCapIsReached() = runTest {
    // Eviction drops whatever is closest to expiry, and a record outlives every tier by default.
    // The
    // ordering is the right way round: an evicted answer costs one credit to fetch again, an
    // evicted
    // record cannot be recovered at all.
    val clock = TestClock.atStart()
    val store = InMemoryCacheRecordStore()
    val cache =
      DefaultCheckCache(store, testKeyStore(), CachePolicy.Default.copy(maxEntries = 2), clock)

    cache.put(scammer, serverResult(riskTier = RiskTier.NONE))
    cache.put(TraciveQuery.phone("+6287777777777"), serverResult(riskTier = RiskTier.NONE))
    cache.putTransactionCheck(firstTransfer, serverResult(riskTier = RiskTier.NONE))

    assertEquals(1, cache.prune(), "One row is over the cap of two.")
    assertNotNull(cache.getTransactionCheck(firstTransfer), "The record must be the survivor.")
  }

  @Test
  fun theCountedSizeIncludesRecords() = runTest {
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, TestClock.atStart())

    cache.put(scammer, serverResult())
    cache.putTransactionCheck(firstTransfer, serverResult())

    assertEquals(2, cache.size(), "size() counts rows, and a record is a row.")
  }

  // ------------------------------------------------------- the id as a key ----

  @Test
  fun surroundingWhitespaceIsFoldedAwayButNothingElseIs() = runTest {
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, TestClock.atStart())

    cache.putTransactionCheck(" $firstTransfer ", serverResult())

    assertNotNull(cache.getTransactionCheck(firstTransfer), "A trimmed id finds its record.")
    assertNull(
      cache.getTransactionCheck(firstTransfer.lowercase()),
      "Case is not folded: a host's reference format is not one this SDK can guess at.",
    )
  }

  @Test
  fun aBlankTransactionIdIsRejectedRatherThanSharedByEveryTransaction() = runTest {
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, TestClock.atStart())

    assertFailsWith<IllegalArgumentException> { cache.putTransactionCheck("  ", serverResult()) }
    assertFailsWith<IllegalArgumentException> { cache.getTransactionCheck("") }
    assertFailsWith<IllegalArgumentException> { cache.removeTransactionCheck("\t") }
  }

  @Test
  fun aDisabledCacheKeepsNoRecordsEither() = runTest {
    // Worth pinning because it is the trap: a host that never wires a cache gets a working check
    // path and a transaction-detail card that is always empty, with nothing to say why.
    DisabledCheckCache.putTransactionCheck(firstTransfer, serverResult())
    assertNull(DisabledCheckCache.getTransactionCheck(firstTransfer))
  }

  /** The record under [key], decrypted, for the tests that assert on what was actually written. */
  private suspend fun decryptRecord(
    store: InMemoryCacheRecordStore,
    keyStore: InMemoryCacheDataKeyStore,
    key: String,
  ): String {
    val row = assertNotNull(store.select(key))
    val dataKey = assertNotNull(keyStore.loadKey(assertNotNull(store.readMeta()).dataKeyId))
    val opened =
      AesGcmCacheCipher(dataKey)
        .open(row.envelope, cacheAssociatedData(key, CachePolicy.SCHEMA_VERSION))
    return assertNotNull(opened).decodeToString()
  }
}
