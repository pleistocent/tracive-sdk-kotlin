package id.tracive.sdk.cache

import id.tracive.sdk.model.Billing
import id.tracive.sdk.model.CheckResult
import id.tracive.sdk.model.Chronology
import id.tracive.sdk.model.Comment
import id.tracive.sdk.model.Origin
import id.tracive.sdk.model.RiskTier
import kotlin.time.Duration
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * A clock the test moves by hand.
 *
 * Every TTL assertion in this package depends on it. Sleeping for real would make the suite take
 * thirty days to prove the `HIGH` default, so the alternative to an injected clock is not a slower
 * test — it is no test.
 */
internal class TestClock(private var current: Instant) : Clock {

  override fun now(): Instant = current

  fun advance(by: Duration) {
    current += by
  }

  companion object {
    /** An arbitrary fixed "now". Nothing depends on the value, only on it not moving on its own. */
    val START: Instant = Instant.parse("2026-07-31T09:00:00Z")

    fun atStart(): TestClock = TestClock(START)
  }
}

/** A 32-byte data key. Fixed so a failure is reproducible; obviously never a real key. */
internal val TestDataKey: ByteArray = ByteArray(32) { (it * 7 + 3).toByte() }

internal fun testKeyStore(): InMemoryCacheDataKeyStore = InMemoryCacheDataKeyStore(TestDataKey)

/** Parses lower- or upper-case hex. Test-only; the production path only ever emits hex. */
internal fun hexToBytes(hex: String): ByteArray {
  require(hex.length % 2 == 0) { "A hex string needs an even number of digits." }
  return ByteArray(hex.length / 2) { i ->
    val high = hexDigit(hex[i * 2])
    val low = hexDigit(hex[i * 2 + 1])
    ((high shl 4) or low).toByte()
  }
}

private fun hexDigit(c: Char): Int =
  when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> throw IllegalArgumentException("Not a hex digit: $c")
  }

/**
 * A fully populated server result, full-detail fields included.
 *
 * Populated on purpose: a fixture with null `topChronologies` and null `comments` would let the
 * "full detail is never cached" test pass against a cache that stores everything it is given.
 */
internal fun serverResult(
  riskTier: RiskTier = RiskTier.HIGH,
  fetchedAt: Instant = TestClock.START,
): CheckResult =
  CheckResult(
    checkId = "chk_01J9ZQ",
    found = true,
    riskTier = riskTier,
    reportTotal = 12,
    credibleReports = 9,
    ambiguousReports = 3,
    firstReportOn = "2025-01",
    lastReportOn = "2026-06",
    fraudAmount = mapOf("IDR" to 25_000_000.0, "USD" to 1_500.0),
    tags = listOf("investment", "impersonation"),
    relatedScammerCount = 4,
    topChronologies =
      listOf(Chronology(chronology = NARRATIVE_MARKER, riskLevel = 4, incidentDate = "2026-05")),
    comments = listOf(Comment(date = "2026-05", text = COMMENT_MARKER, fromCredibleReport = true)),
    billing = Billing(creditSource = "USER", unitsCharged = 1, partnerBalanceRemaining = 998),
    disclaimer = "User-submitted reports. Not an adjudicated finding.",
    origin = Origin.SERVER,
    fetchedAt = fetchedAt,
  )

/**
 * Distinctive strings that must never reach the database file.
 *
 * They stand in for what real full-detail content is: locale-specific narrative prose about a named
 * person, fetched under one `detail`/`locale` pair and meaningless under another.
 */
internal const val NARRATIVE_MARKER: String =
  "Korban ditipu lewat investasi bodong yang mengatasnamakan koperasi."

internal const val COMMENT_MARKER: String = "Saya juga kena, nomor yang sama."
