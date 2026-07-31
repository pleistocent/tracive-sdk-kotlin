package id.tracive.sdk.cache

import id.tracive.sdk.model.CachePolicy
import id.tracive.sdk.model.CheckResult
import id.tracive.sdk.model.RiskTier
import id.tracive.sdk.query.TraciveQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/**
 * How long a cached answer stays usable, per risk tier and under a server override.
 *
 * Both directions of getting this wrong cost real money or real safety, which is why the boundary
 * is asserted to the millisecond rather than approximately:
 * - **Too short** and every check goes to the server. Every one of them is billed, including the
 *   misses, and nothing in the app looks broken.
 * - **Too long** and the SDK keeps answering `NONE` for a number that was reported this morning.
 *   That is the failure mode this product exists to prevent.
 *
 * Every assertion here runs against an injected [TestClock]. Proving the `HIGH` default any other
 * way would take thirty days.
 */
class CacheTtlTest {

  private val query = TraciveQuery.phone("+6281234567891")

  // ------------------------------------------------------- tier defaults ----

  @Test fun aHighResultLivesForThirtyDays() = runTest { assertLifetime(30.days, RiskTier.HIGH) }

  @Test
  fun anElevatedResultLivesForSevenDays() = runTest { assertLifetime(7.days, RiskTier.ELEVATED) }

  @Test
  fun aCautionResultLivesForTwentyFourHours() = runTest {
    assertLifetime(24.hours, RiskTier.CAUTION)
  }

  @Test
  fun aNoneResultLivesForOneHour() = runTest {
    // The shortest default, and deliberately so: `NONE` means "no evidence found", and it is the
    // verdict most likely to be overtaken by the first report against an identifier.
    assertLifetime(1.hours, RiskTier.NONE)
  }

  @Test
  fun aHostCanShortenTheDefaultsWithoutTouchingTheOverride() = runTest {
    val policy = CachePolicy(highTtl = 15.minutes, noneTtl = 5.minutes)
    assertLifetime(15.minutes, RiskTier.HIGH, policy = policy)
    assertLifetime(5.minutes, RiskTier.NONE, policy = policy)
  }

  // ------------------------------------------------------ server override ----

  @Test
  fun theServersCacheTtlSecondsWinsOverTheTierDefault() = runTest {
    // The fleet-wide invalidation lever. A `HIGH` result would otherwise sit on the device for
    // thirty days; the backend shortening this field is the only way to change that without
    // shipping a release, so it has to win.
    assertLifetime(
      90.seconds,
      RiskTier.HIGH,
      result = serverResult(riskTier = RiskTier.HIGH, cacheTtlSeconds = 90),
    )
  }

  @Test
  fun theServerCanAlsoLengthenTheLifetime() = runTest {
    assertLifetime(
      2.days,
      RiskTier.NONE,
      result = serverResult(riskTier = RiskTier.NONE, cacheTtlSeconds = 2 * 24 * 60 * 60L),
    )
  }

  @Test
  fun aZeroTtlMeansDoNotKeepThisAnswer() = runTest {
    val clock = TestClock.atStart()
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, clock)

    cache.put(query, serverResult(riskTier = RiskTier.HIGH, cacheTtlSeconds = 0))

    assertNull(cache.get(query), "A zero TTL must not be stored, not even for an instant.")
    assertEquals(0, cache.size(), "A zero TTL must not leave a row behind either.")
  }

  @Test
  fun aZeroTtlAlsoRetractsAnEntryAlreadyOnTheDevice() = runTest {
    // This is the case that matters operationally. The user already holds a cached `HIGH` verdict;
    // the report behind it is withdrawn; the next response carries `cacheTtlSeconds: 0`. If `put`
    // only declined to store the new answer, the old one would keep being served for the rest of
    // its thirty days.
    val clock = TestClock.atStart()
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, clock)

    cache.put(query, serverResult(riskTier = RiskTier.HIGH))
    assertNotNull(cache.get(query))

    cache.put(query, serverResult(riskTier = RiskTier.NONE, cacheTtlSeconds = 0))

    assertNull(cache.get(query))
    assertEquals(0, cache.size())
  }

  @Test
  fun aNegativeTtlIsTreatedAsMalformedAndTheTierDefaultApplies() = runTest {
    // A negative value is a bug on the wire, not an instruction. Reading it as "expired already"
    // would silently disable caching for whatever the bug affects — and the only symptom would be
    // the bill.
    assertLifetime(
      30.days,
      RiskTier.HIGH,
      result = serverResult(riskTier = RiskTier.HIGH, cacheTtlSeconds = -1),
    )
  }

  @Test
  fun anAbsurdTtlSaturatesInsteadOfWrappingIntoThePast() = runTest {
    // `now + ttl` in milliseconds overflows `Long` well before `Long.MAX_VALUE` seconds. Wrapping
    // would produce a negative expiry, which reads as "expired decades ago": every entry would
    // miss, every check would be billed, and nothing would look wrong.
    val clock = TestClock.atStart()
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, clock)

    cache.put(query, serverResult(riskTier = RiskTier.HIGH, cacheTtlSeconds = Long.MAX_VALUE))

    assertNotNull(cache.get(query))
    clock.advance(365.days * 100)
    assertNotNull(cache.get(query), "A saturated expiry must still be in the future.")
  }

  @Test
  fun aHostCanOptOutOfTheServerOverride() = runTest {
    // Opting out is supported but is a real decision: it takes this installation out of reach of
    // the backend's invalidation lever, and `docs/caching.md` says so.
    val policy = CachePolicy(honorServerTtlOverride = false)
    assertLifetime(
      1.hours,
      RiskTier.NONE,
      result = serverResult(riskTier = RiskTier.NONE, cacheTtlSeconds = 10),
      policy = policy,
    )
  }

  @Test
  fun optingOutOfTheOverrideAlsoIgnoresAZeroTtl() = runTest {
    val clock = TestClock.atStart()
    val cache =
      InMemoryCheckCache(testKeyStore(), CachePolicy(honorServerTtlOverride = false), clock)

    cache.put(query, serverResult(riskTier = RiskTier.HIGH, cacheTtlSeconds = 0))

    assertNotNull(cache.get(query), "With the override off, a zero TTL is not an instruction.")
  }

  // --------------------------------------------------------------- expiry ----

  @Test
  fun anExpiredEntryIsDeletedByTheReadThatFindsIt() = runTest {
    val clock = TestClock.atStart()
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, clock)

    cache.put(query, serverResult(riskTier = RiskTier.NONE))
    assertEquals(1, cache.size())

    clock.advance(2.hours)
    assertNull(cache.get(query))
    assertEquals(
      0,
      cache.size(),
      "Lazy expiry has to actually delete, or the file grows forever without a sweeper.",
    )
  }

  @Test
  fun pruneRemovesOnlyTheEntriesThatAreAlreadyDead() = runTest {
    val clock = TestClock.atStart()
    val cache = InMemoryCheckCache(testKeyStore(), CachePolicy.Default, clock)
    val shortLived = TraciveQuery.phone("+6281111111111")
    val longLived = TraciveQuery.phone("+6282222222222")

    cache.put(shortLived, serverResult(riskTier = RiskTier.NONE))
    cache.put(longLived, serverResult(riskTier = RiskTier.HIGH))

    clock.advance(2.hours)

    assertEquals(1, cache.prune())
    assertEquals(1, cache.size())
    assertNotNull(cache.get(longLived))
    assertEquals(0, cache.prune(), "A second prune has nothing left to do.")
  }

  @Test
  fun aDisabledPolicyNeitherReadsNorWrites() = runTest {
    val cache =
      InMemoryCheckCache(testKeyStore(), CachePolicy(enabled = false), TestClock.atStart())

    cache.put(query, serverResult())

    assertNull(cache.get(query))
    assertEquals(0, cache.size())
  }

  /**
   * Asserts that an entry survives to one millisecond before [ttl] and is gone at [ttl] exactly.
   *
   * Both halves are load-bearing. Without the first, a cache that stores nothing at all passes;
   * without the second, one that never expires does.
   */
  private suspend fun assertLifetime(
    ttl: Duration,
    riskTier: RiskTier,
    result: CheckResult = serverResult(riskTier = riskTier),
    policy: CachePolicy = CachePolicy.Default,
  ) {
    val clock = TestClock.atStart()
    val cache = InMemoryCheckCache(testKeyStore(), policy, clock)

    cache.put(query, result)

    clock.advance(ttl - 1.milliseconds)
    assertNotNull(cache.get(query), "$riskTier must still be a hit 1 ms before $ttl has passed.")

    clock.advance(1.milliseconds)
    assertNull(cache.get(query), "$riskTier must be a miss once exactly $ttl has passed.")
  }
}
