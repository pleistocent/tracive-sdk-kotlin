package id.tracive.sdk

import id.tracive.sdk.client.SdkJson
import id.tracive.sdk.model.CheckResult
import id.tracive.sdk.model.RiskTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two response shapes the backend really sends, decoded with the SDK's own `Json`.
 *
 * `ModelDecodingTest` writes its payloads from the spec and builds its own `Json`; both of those
 * choices hid this. The server answers a miss — the majority of checks, since most accounts are
 * clean — with `fraudAmount` and `tags` set to `null`, and it does the same for a hit that carries
 * no tags. The spec types neither as nullable, so nothing in a spec-derived fixture would ever
 * present one.
 */
class MissResponseDecodingTest {

  /** A miss, field for field as `POST /v1/scammer-checks` returns it. */
  private val missPayload =
    """
    {
      "checkId": "chk_0123456789abcdef01234567",
      "found": false,
      "riskTier": "NONE",
      "reportTotal": 0,
      "credibleReports": 0,
      "ambiguousReports": 0,
      "firstReportOn": null,
      "lastReportOn": null,
      "fraudAmount": null,
      "fraudAmountUsd": null,
      "tags": null,
      "relatedScammerCount": 0,
      "cacheTtlSeconds": 3600,
      "disclaimer": "This information is indicative, based on community reports, and is not a legal verdict.",
      "billing": { "creditSource": "USER", "unitsCharged": 1 }
    }
    """
      .trimIndent()

  @Test
  fun aMissDecodesInsteadOfFailingAfterTheCreditIsSpent() {
    val result = SdkJson.decodeFromString(CheckResult.serializer(), missPayload)

    assertFalse(result.found)
    assertEquals(RiskTier.NONE, result.riskTier)
    assertEquals(emptyMap(), result.fraudAmount)
    assertEquals(emptyList(), result.tags)
    assertEquals(3600L, result.cacheTtlSeconds)
    assertEquals(1, result.billing?.unitsCharged)
  }

  @Test
  fun aHitWithoutTagsDecodes() {
    val payload =
      """
      {
        "checkId": "chk_0123456789abcdef01234568",
        "found": true,
        "riskTier": "HIGH",
        "reportTotal": 12,
        "credibleReports": 9,
        "ambiguousReports": 3,
        "firstReportOn": "2025-11-02T09:14:00.000Z",
        "lastReportOn": "2026-08-14T02:11:00.000Z",
        "fraudAmount": { "IDR": 87400000.0 },
        "fraudAmountUsd": { "approx": 4928.0, "ratesAsOf": null, "source": "static" },
        "tags": null,
        "relatedScammerCount": 4,
        "cacheTtlSeconds": 2592000,
        "disclaimer": "This information is indicative, based on community reports, and is not a legal verdict.",
        "billing": { "creditSource": "USER", "unitsCharged": 1 }
      }
      """
        .trimIndent()

    val result = SdkJson.decodeFromString(CheckResult.serializer(), payload)

    assertTrue(result.found)
    assertEquals(RiskTier.HIGH, result.riskTier)
    assertEquals(mapOf("IDR" to 87400000.0), result.fraudAmount)
    assertEquals(emptyList(), result.tags)
  }

  /** A present collection still decodes, and still round-trips into the cache. */
  @Test
  fun aPresentCollectionIsUnaffected() {
    val payload =
      """
      {"found":true,"riskTier":"CAUTION","fraudAmount":{"IDR":250000.0},"tags":["marketplace"]}
      """
        .trimIndent()

    val result = SdkJson.decodeFromString(CheckResult.serializer(), payload)
    assertEquals(mapOf("IDR" to 250000.0), result.fraudAmount)
    assertEquals(listOf("marketplace"), result.tags)

    val restored =
      SdkJson.decodeFromString(
        CheckResult.serializer(),
        SdkJson.encodeToString(CheckResult.serializer(), result),
      )
    assertEquals(result, restored)
  }
}
