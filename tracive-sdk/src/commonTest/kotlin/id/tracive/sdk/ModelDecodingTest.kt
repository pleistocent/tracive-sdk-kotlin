package id.tracive.sdk

import id.tracive.sdk.model.Billing
import id.tracive.sdk.model.CheckResult
import id.tracive.sdk.model.Chronology
import id.tracive.sdk.model.Comment
import id.tracive.sdk.model.CreditsInfo
import id.tracive.sdk.model.Detail
import id.tracive.sdk.model.Origin
import id.tracive.sdk.model.ProfileInfo
import id.tracive.sdk.model.RiskTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

/**
 * Wire decoding for every model the Open API returns.
 *
 * These payloads are written from the spec rather than captured from a server; replies captured
 * from the live API are in [MissResponseDecodingTest]. That makes the forward-compatibility cases
 * the important ones here: whatever phase 2 adds, an app already in users' hands must keep decoding
 * what it knows and skip the rest.
 *
 * No network, no fixtures on disk, no clock.
 */
class ModelDecodingTest {

  /** The configuration the SDK's transport must use. `ignoreUnknownKeys` is not optional. */
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun aMinimalResultDecodes() {
    // The payload still carries `cacheTtlSeconds`, which this SDK no longer models. A server or
    // proxy that keeps sending it must not break the decode, and this is where that is proved:
    // without `ignoreUnknownKeys` the call below throws.
    val payload =
      """
      {
        "checkId": "chk_01HZY",
        "found": true,
        "riskTier": "HIGH",
        "reportTotal": 12,
        "credibleReports": 9,
        "ambiguousReports": 3,
        "firstReportOn": "2025-01-15T12:00:00Z",
        "lastReportOn": "2026-07-15T12:00:00.000Z",
        "fraudAmount": { "IDR": 15000000.5, "USD": 900.0 },
        "tags": ["investment", "impersonation"],
        "relatedScammerCount": 4,
        "cacheTtlSeconds": 3600,
        "disclaimer": "Community reports, not adjudicated findings."
      }
      """
        .trimIndent()

    val result = json.decodeFromString(CheckResult.serializer(), payload)

    assertEquals("chk_01HZY", result.checkId)
    assertTrue(result.found)
    assertEquals(RiskTier.HIGH, result.riskTier)
    assertEquals(12, result.reportTotal)
    assertEquals(9, result.credibleReports)
    assertEquals(3, result.ambiguousReports)
    assertEquals(Instant.parse("2025-01-15T12:00:00Z"), result.firstReportOn)
    assertEquals(Instant.parse("2026-07-15T12:00:00Z"), result.lastReportOn)
    assertEquals(mapOf("IDR" to 15000000.5, "USD" to 900.0), result.fraudAmount)
    assertEquals(listOf("investment", "impersonation"), result.tags)
    assertEquals(4, result.relatedScammerCount)
    assertEquals("Community reports, not adjudicated findings.", result.disclaimer)

    // detail = MINIMAL, so the narrative fields are absent rather than empty.
    assertNull(result.topChronologies)
    assertNull(result.comments)
    // Client-assigned fields the server never sends.
    assertEquals(Origin.SERVER, result.origin)
    assertEquals(Instant.DISTANT_PAST, result.fetchedAt)
  }

  @Test
  fun aFullDetailResultDecodesItsNarrativeFields() {
    val payload =
      """
      {
        "checkId": "chk_02",
        "found": true,
        "riskTier": "ELEVATED",
        "topChronologies": [
          { "chronology": "Paid for goods never shipped.", "riskLevel": 4, "incidentDate": "2026-03" },
          { "chronology": "Fake investment scheme." }
        ],
        "comments": [
          { "date": "2026-06", "text": "Same thing happened to me.", "fromCredibleReport": true },
          { "date": "2026-05", "text": "Be careful." }
        ],
        "billing": { "creditSource": "USER", "unitsCharged": 1, "partnerBalanceRemaining": 4211 }
      }
      """
        .trimIndent()

    val result = json.decodeFromString(CheckResult.serializer(), payload)

    assertEquals(
      listOf(
        Chronology("Paid for goods never shipped.", 4, "2026-03"),
        Chronology("Fake investment scheme.", 0, null),
      ),
      result.topChronologies,
    )
    assertEquals(
      listOf(
        Comment("2026-06", "Same thing happened to me.", true),
        Comment("2026-05", "Be careful.", false),
      ),
      result.comments,
    )
    assertEquals(Billing("USER", 1, 4211L), result.billing)
  }

  /**
   * phase 2 will add fields. An app shipped today has to survive that, so unknown keys are ignored
   * at every level: top level, nested object, and inside an array element.
   */
  @Test
  fun unknownFieldsAreIgnoredAtEveryLevel() {
    val payload =
      """
      {
        "found": true,
        "riskTier": "CAUTION",
        "networkGraphVersion": 7,
        "billing": { "creditSource": "PARTNER", "unitsCharged": 2, "settlementRef": "s_1" },
        "comments": [
          { "date": "2026-07", "text": "hi", "reactions": { "up": 3 } }
        ],
        "experiment": { "arm": "b", "weights": [1, 2, 3] }
      }
      """
        .trimIndent()

    val result = json.decodeFromString(CheckResult.serializer(), payload)

    assertEquals(RiskTier.CAUTION, result.riskTier)
    assertEquals("PARTNER", result.billing?.creditSource)
    assertEquals(2, result.billing?.unitsCharged)
    assertNull(result.billing?.partnerBalanceRemaining)
    assertEquals(listOf(Comment("2026-07", "hi", false)), result.comments)
  }

  /**
   * A response that omits everything must decode to the safe reading, not fail.
   *
   * Note what "safe" means here: [RiskTier.NONE] is "no evidence found", never "verified safe". The
   * SDK cannot invent a verdict, and a caller that treats `NONE` as an all-clear has misread it.
   */
  @Test
  fun anEmptyObjectDecodesToTheSafeDefaults() {
    val result = json.decodeFromString(CheckResult.serializer(), "{}")

    assertNull(result.checkId)
    assertEquals(false, result.found)
    assertEquals(RiskTier.NONE, result.riskTier)
    assertEquals(0, result.reportTotal)
    assertEquals(0, result.credibleReports)
    assertEquals(0, result.ambiguousReports)
    assertNull(result.firstReportOn)
    assertNull(result.lastReportOn)
    assertEquals(emptyMap<String, Double>(), result.fraudAmount)
    assertEquals(emptyList<String>(), result.tags)
    assertEquals(0, result.relatedScammerCount)
    assertNull(result.disclaimer)
  }

  @Test
  fun riskTierUsesTheServersUpperCaseNames() {
    for (tier in RiskTier.values()) {
      val decoded =
        json.decodeFromString(CheckResult.serializer(), """{"riskTier":"${tier.name}"}""")
      assertEquals(tier, decoded.riskTier)
    }
  }

  @Test
  fun detailIsSentInLowerCase() {
    assertEquals("\"minimal\"", json.encodeToString(Detail.serializer(), Detail.MINIMAL))
    assertEquals("\"full\"", json.encodeToString(Detail.serializer(), Detail.FULL))
  }

  /** The cache stores a `CheckResult`, so it has to survive an encode/decode round trip intact. */
  @Test
  fun aResultRoundTripsThroughJson() {
    val original =
      CheckResult(
        found = true,
        riskTier = RiskTier.ELEVATED,
        reportTotal = 5,
        credibleReports = 4,
        ambiguousReports = 1,
        firstReportOn = Instant.parse("2024-05-15T12:00:00Z"),
        lastReportOn = Instant.parse("2026-07-15T12:00:00Z"),
        fraudAmount = mapOf("IDR" to 250000.0),
        tags = listOf("marketplace"),
        relatedScammerCount = 2,
        disclaimer = "Community reports.",
        origin = Origin.CACHE,
        fetchedAt = Instant.parse("2026-07-31T09:10:11Z"),
      )

    val restored =
      json.decodeFromString(
        CheckResult.serializer(),
        json.encodeToString(CheckResult.serializer(), original),
      )

    assertEquals(original, restored)
  }

  /**
   * The cache is keyed on the query alone, so it may only hold fields that do not vary with the
   * request's `detail` or `locale`. Anything else would let a `MINIMAL` request serve narrative
   * text in the wrong language, or replay another call's charge.
   */
  @Test
  fun toCacheableDropsExactlyTheFieldsThatVaryPerCall() {
    val fetched = Instant.parse("2026-07-31T09:10:11Z")
    val full =
      CheckResult(
        checkId = "chk_03",
        found = true,
        riskTier = RiskTier.HIGH,
        reportTotal = 7,
        tags = listOf("phishing"),
        topChronologies = listOf(Chronology("narrative", 3, null)),
        comments = listOf(Comment("2026-07", "text", true)),
        billing = Billing("USER", 1, null),
        disclaimer = "Community reports.",
        fetchedAt = fetched,
      )

    val cacheable = full.toCacheable()

    assertNull(cacheable.checkId, "checkId identifies one call, not the identifier")
    assertNull(cacheable.topChronologies, "narrative text varies with detail and locale")
    assertNull(cacheable.comments, "narrative text varies with detail and locale")
    assertNull(cacheable.billing, "a cache hit costs nothing, so it cannot carry a charge")

    // Everything invariant survives untouched.
    assertEquals(full.found, cacheable.found)
    assertEquals(full.riskTier, cacheable.riskTier)
    assertEquals(full.reportTotal, cacheable.reportTotal)
    assertEquals(full.tags, cacheable.tags)
    assertEquals(full.disclaimer, cacheable.disclaimer)
    assertEquals(fetched, cacheable.fetchedAt)
  }

  @Test
  fun creditsDecode() {
    val payload =
      """
      { "subscriptionCredits": 42, "tier": "PRO", "nextResetAt": "2026-08-01T00:00:00Z",
        "legacyBalance": 0 }
      """
        .trimIndent()

    val credits = json.decodeFromString(CreditsInfo.serializer(), payload)

    assertEquals(42, credits.subscriptionCredits)
    assertEquals("PRO", credits.tier)
    assertEquals(Instant.parse("2026-08-01T00:00:00Z"), credits.nextResetAt)
  }

  @Test
  fun aProfileDecodesWithOnlyTheScopesTheUserGranted() {
    val full =
      json.decodeFromString(
        ProfileInfo.serializer(),
        """
        { "sub": "usr_9", "name": "Budi", "country": "IDN", "tier": "PRO",
          "memberSince": "2023-04-05T06:07:08Z" }
        """
          .trimIndent(),
      )
    assertEquals("usr_9", full.sub)
    assertEquals("Budi", full.name)
    assertEquals("IDN", full.country)
    assertEquals("PRO", full.tier)
    assertEquals(Instant.parse("2023-04-05T06:07:08Z"), full.memberSince)

    // A narrower grant returns only the subject. Null means "not shared", not "not set".
    val narrow = json.decodeFromString(ProfileInfo.serializer(), """{"sub":"usr_9"}""")
    assertEquals("usr_9", narrow.sub)
    assertNull(narrow.name)
    assertNull(narrow.country)
    assertNull(narrow.tier)
    assertNull(narrow.memberSince)
  }
}
