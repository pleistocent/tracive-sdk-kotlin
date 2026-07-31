package id.tracive.sdk.cache

import id.tracive.sdk.model.CachePolicy
import id.tracive.sdk.model.Origin
import id.tracive.sdk.model.RiskTier
import id.tracive.sdk.query.TraciveQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant

/**
 * What a cached record contains, and — more importantly — what it must never contain.
 *
 * Two invariants are enforced here, and neither of them announces itself when broken:
 *
 * 1. **Full-detail content is never cached.** The cache is keyed on the query alone, so anything
 *    that varies with `detail` or `locale` would eventually be served under the wrong request: an
 *    Indonesian narrative shown to an English caller, or a `detail = FULL` payload returned to a
 *    `MINIMAL` one that never paid for it.
 * 2. **The raw identifier is never at rest.** The row key is a SHA-256 digest and the payload is
 *    ciphertext, so a database pulled off a device says how many identifiers were checked and
 *    nothing about which.
 */
class CacheContentTest {

  private val query = TraciveQuery.phone("+6281234567891")

  @Test
  fun fullDetailContentIsDroppedOnTheWayIn() = runTest {
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, TestClock.atStart())

    // A `detail = FULL` response, narrative text and billing included.
    cache.put(query, serverResult())

    val hit = assertNotNull(cache.get(query))
    assertNull(hit.topChronologies, "Narrative text is locale-dependent and must not be cached.")
    assertNull(hit.comments, "Comments are locale-dependent and must not be cached.")
    assertNull(
      hit.billing,
      "Billing describes one call, not the identifier — replaying it would misreport a charge.",
    )
    assertNull(hit.checkId, "A cache hit is not a check and has no check id to reconcile.")
  }

  @Test
  fun fullDetailContentNeverReachesTheStoredBytes() = runTest {
    // The stronger form of the test above: not "the getter hides it" but "it was never written".
    // A future field that quietly carried narrative text into the payload would pass the first
    // test and fail this one.
    val store = InMemoryCacheRecordStore()
    val cache = DefaultCheckCache(store, testKeyStore(), CachePolicy.Default, TestClock.atStart())

    cache.put(query, serverResult())

    val plaintext = decryptSoleRecord(store)
    assertTrue(!plaintext.contains(NARRATIVE_MARKER), "Chronology text was written to the cache.")
    assertTrue(!plaintext.contains(COMMENT_MARKER), "Comment text was written to the cache.")
    assertTrue(!plaintext.contains("chk_01J9ZQ"), "The check id was written to the cache.")
    assertTrue(!plaintext.contains("partnerBalanceRemaining"), "Billing was written to the cache.")
  }

  @Test
  fun everyFieldThatDoesNotVaryWithDetailOrLocaleSurvivesTheRoundTrip() = runTest {
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, TestClock.atStart())
    val server = serverResult(riskTier = RiskTier.ELEVATED, cacheTtlSeconds = 3_600)

    cache.put(query, server)
    val hit = assertNotNull(cache.get(query))

    assertEquals(server.found, hit.found)
    assertEquals(server.riskTier, hit.riskTier)
    assertEquals(server.reportTotal, hit.reportTotal)
    assertEquals(server.credibleReports, hit.credibleReports)
    assertEquals(server.ambiguousReports, hit.ambiguousReports)
    assertEquals(server.firstReportOn, hit.firstReportOn)
    assertEquals(server.lastReportOn, hit.lastReportOn)
    assertEquals(server.fraudAmount, hit.fraudAmount)
    assertEquals(server.tags, hit.tags)
    assertEquals(server.relatedScammerCount, hit.relatedScammerCount)
    assertEquals(server.cacheTtlSeconds, hit.cacheTtlSeconds)
    // The disclaimer travels with the verdict because a UI is required to show it, and a cache hit
    // is displayed exactly like a live answer.
    assertEquals(server.disclaimer, hit.disclaimer)
  }

  @Test
  fun aHitIsLabelledAsComingFromTheCache() = runTest {
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, TestClock.atStart())

    cache.put(query, serverResult())

    assertEquals(Origin.CACHE, assertNotNull(cache.get(query)).origin)
  }

  @Test
  fun aHitKeepsTheOriginalFetchTimeRatherThanTheTimeItWasRead() = runTest {
    // A UI says "last checked on …" from this field. Refreshing it on read would make every cached
    // answer look like it was fetched a moment ago, which is precisely the thing the caller is
    // trying to find out.
    val clock = TestClock.atStart()
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, clock)
    val fetchedAt = Instant.parse("2026-07-30T04:05:06Z")

    cache.put(query, serverResult(fetchedAt = fetchedAt))
    clock.advance(kotlin.time.Duration.parse("6h"))

    assertEquals(fetchedAt, assertNotNull(cache.get(query)).fetchedAt)
  }

  @Test
  fun theRowKeyIsTheSha256OfTheFoldedQuery() = runTest {
    val store = InMemoryCacheRecordStore()
    val cache = DefaultCheckCache(store, testKeyStore(), CachePolicy.Default, TestClock.atStart())

    cache.put(query, serverResult())

    val expected = Sha256.digest(query.searchQueryIndex.encodeToByteArray()).toHex()
    assertEquals(64, expected.length)
    assertNotNull(store.select(expected), "The row must be filed under SHA-256(searchQueryIndex).")
  }

  @Test
  fun theIdentifierItselfIsNowhereInTheStore() = runTest {
    val store = InMemoryCacheRecordStore()
    val cache = DefaultCheckCache(store, testKeyStore(), CachePolicy.Default, TestClock.atStart())

    cache.put(query, serverResult())

    val row = assertNotNull(store.select(cacheKeyFor(query)))
    assertTrue(!row.cacheKey.contains("6281234567891"), "The row key must not carry the number.")
    assertTrue(!row.cacheKey.contains("PHONE"), "Not even the identifier's type is stored.")
    assertTrue(
      !row.envelope.decodeToString().contains("6281234567891"),
      "The payload must be ciphertext, so the number cannot appear in it.",
    )
    // The folded query is not in the plaintext either: the cache never needs to read it back, and
    // storing it would undo the hashing.
    assertTrue(!decryptSoleRecord(store).contains("6281234567891"))
  }

  @Test
  fun twoSpellingsOfOneIdentifierShareARow() = runTest {
    // Folding happens in `TraciveQuery`, so this is really a test that the cache keys on the folded
    // string and not on what the caller typed. Getting it wrong would split one entity into several
    // entries, and each of those extra misses is billed.
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, TestClock.atStart())

    cache.put(TraciveQuery.phone("+62 812-3456-7891"), serverResult())

    assertNotNull(cache.get(TraciveQuery.phone("+6281234567891")))
    assertEquals(1, cache.size())
  }

  @Test
  fun differentIdentifiersDoNotShareARow() = runTest {
    val store = InMemoryCacheRecordStore()
    val cache = DefaultCheckCache(store, testKeyStore(), CachePolicy.Default, TestClock.atStart())
    val other = TraciveQuery.phone("+6289999999999")

    cache.put(query, serverResult(riskTier = RiskTier.HIGH))
    cache.put(other, serverResult(riskTier = RiskTier.NONE))

    assertNotEquals(cacheKeyFor(query), cacheKeyFor(other))
    assertEquals(RiskTier.HIGH, assertNotNull(cache.get(query)).riskTier)
    assertEquals(RiskTier.NONE, assertNotNull(cache.get(other)).riskTier)
  }

  @Test
  fun everyKindOfQueryGetsItsOwnRow() = runTest {
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, TestClock.atStart())
    val queries =
      listOf(
        TraciveQuery.phone("+6281234567891"),
        TraciveQuery.email("budi@gmail.com"),
        TraciveQuery.bankAccount("IDN", "014", "3242-4242-422"),
        TraciveQuery.iban("DE89 3704 0044 0532 0130 00"),
        TraciveQuery.crypto("ETH", "0xAbC0000000000000000000000000000000000001"),
        TraciveQuery.username("instagram", "@scammerx"),
      )

    queries.forEach { cache.put(it, serverResult()) }

    assertEquals(queries.size, cache.size())
    assertEquals(queries.size, queries.map { cacheKeyFor(it) }.toSet().size)
  }

  @Test
  fun aRecordThisBuildCannotDecodeIsAMissAndIsDropped() = runTest {
    // Two realistic causes: a payload written by a newer build in a shape this one cannot read, and
    // a `riskTier` value invented after this release shipped. Coercing the unknown tier to `NONE`
    // would report "no evidence found" for something the server considered severe enough to add a
    // tier for, so the decode is allowed to fail — and a failed decode is a miss, never an empty
    // result.
    val store = InMemoryCacheRecordStore()
    val keyStore = testKeyStore()
    val cache = DefaultCheckCache(store, keyStore, CachePolicy.Default, TestClock.atStart())

    cache.put(query, serverResult())

    val key = cacheKeyFor(query)
    val row = assertNotNull(store.select(key))
    val cipher = AesGcmCacheCipher(TestDataKey)
    val forged =
      cipher.seal(
        """{"riskTier":"CATASTROPHIC","found":true}""".encodeToByteArray(),
        cacheNonce(9_999),
        cacheAssociatedData(key, CachePolicy.SCHEMA_VERSION),
      )
    store.upsert(CacheRow(key, forged, row.expiresAtEpochMs))

    assertNull(cache.get(query))
    assertEquals(0, cache.size(), "An undecodable row must be dropped, not left to be re-read.")
  }

  @Test
  fun aRowMovedFromAnotherKeyIsRejectedRatherThanServed() = runTest {
    // The swap attack in its end-to-end form: someone with write access to the database copies the
    // sealed `NONE` record of a clean number over the row of a known fraudster. The associated data
    // binds each record to its own row key, so the moved record fails to open and the SDK misses —
    // costing a credit and returning the truth — instead of confidently reporting "no evidence".
    val store = InMemoryCacheRecordStore()
    val cache = DefaultCheckCache(store, testKeyStore(), CachePolicy.Default, TestClock.atStart())
    val clean = TraciveQuery.phone("+6287777777777")

    cache.put(query, serverResult(riskTier = RiskTier.HIGH))
    cache.put(clean, serverResult(riskTier = RiskTier.NONE))

    val cleanRow = assertNotNull(store.select(cacheKeyFor(clean)))
    val scammerRow = assertNotNull(store.select(cacheKeyFor(query)))
    store.upsert(CacheRow(scammerRow.cacheKey, cleanRow.envelope, scammerRow.expiresAtEpochMs))

    assertNull(cache.get(query), "A record from another row must not authenticate.")
  }

  /** Decrypts the single record in [store], for tests that assert on what was actually written. */
  private suspend fun decryptSoleRecord(store: InMemoryCacheRecordStore): String {
    val key = cacheKeyFor(query)
    val row = assertNotNull(store.select(key))
    val opened =
      AesGcmCacheCipher(TestDataKey)
        .open(row.envelope, cacheAssociatedData(key, CachePolicy.SCHEMA_VERSION))
    return assertNotNull(opened).decodeToString()
  }
}
