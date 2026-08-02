package id.tracive.sdk.query

import id.tracive.sdk.TraciveQueryException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `normalizePhoneNumber` against the shared contract.
 *
 * The vectors are compiled in here **and** kept as
 * `commonTest/resources/phone-normalization-vectors.json`, vendored byte-identically into all four
 * SDK repositories. Expected values came from executing the Tracive app's own normalizer: if this
 * SDK disagrees with a vector, this SDK is wrong, because the app and the SDK must fold the same
 * person to the same record key.
 */
class PhoneNormalizationTest {

  private data class PhoneVector(val country: String, val input: String, val expected: String)

  private data class PhoneRejection(val country: String, val input: String, val reason: String)

  private val vectors =
    listOf(
      PhoneVector("IDN", "0857-1860-9079", "+6285718609079"),
      PhoneVector("+62", "0857 1860 9079", "+6285718609079"),
      PhoneVector("62", "085718609079", "+6285718609079"),
      PhoneVector("idn", "0857 1860 9079", "+6285718609079"),
      PhoneVector("IDN", "+62 857 1860 9079", "+6285718609079"),
      PhoneVector("IDN", "0062857 1860 9079", "+6285718609079"),
      PhoneVector("IDN", "8571860907", "+628571860907"),
      PhoneVector("USA", "(555) 123-4567", "+15551234567"),
      PhoneVector("USA", "1 555 123 4567", "+15551234567"),
      PhoneVector("CAN", "1 604 555 0199", "+16045550199"),
      PhoneVector("ITA", "06 1234 5678", "+390612345678"),
      PhoneVector("SMR", "0549 123456", "+3780549123456"),
      PhoneVector("RUS", "8 912 345 67 89", "+79123456789"),
      PhoneVector("KAZ", "8 701 234 5678", "+77012345678"),
      PhoneVector("GBR", "07911 123456", "+447911123456"),
      PhoneVector("DEU", "0151 23456789", "+4915123456789"),
      PhoneVector("+39", "06 1234 5678", "+390612345678"),
    )

  private val rejections =
    listOf(
      PhoneRejection("IDN", "   ", "empty_phone_number"),
      PhoneRejection("IDN", "abc-def", "empty_phone_number"),
      PhoneRejection("", "0857123456", "empty_country"),
      PhoneRejection("XXX", "0857123456", "unknown_country"),
      PhoneRejection("ID", "0857123456", "unknown_country"),
    )

  @Test
  fun everyVectorFoldsByteForByte() {
    assertEquals(17, vectors.size, "vector count — did the embedded copy get truncated?")
    for (vector in vectors) {
      assertEquals(
        vector.expected,
        normalizePhoneNumber(vector.country, vector.input),
        "vector did not fold as the app folds it",
      )
    }
  }

  @Test
  fun everyRejectionIsRefusedWithItsExactReason() {
    assertEquals(5, rejections.size, "rejection count")
    for (rejection in rejections) {
      val failure =
        assertFailsWith<TraciveQueryException>("accepted an unusable input") {
          normalizePhoneNumber(rejection.country, rejection.input)
        }
      assertEquals(rejection.reason, failure.reason)
    }
  }

  @Test
  fun theCountryTableMatchesTheContract() {
    assertEquals(245, Country.entries.size, "country count drifted from the fixture")
    assertEquals(
      Country.entries.size,
      Country.entries.map { it.alpha3 }.toSet().size,
      "two countries share an alpha-3 code",
    )
    val spotChecks =
      listOf(
        Triple("GBR", "44", "GB"),
        Triple("IDN", "62", "ID"),
        Triple("ITA", "39", "IT"),
        Triple("RUS", "7", "RU"),
        Triple("SGS", "500", "GS"),
        Triple("USA", "1", "US"),
      )
    for ((alpha3, dialCode, alpha2) in spotChecks) {
      val country = assertNotNull(Country.fromAlpha3(alpha3), "missing from the table")
      assertEquals(dialCode, country.dialCode)
      assertEquals(alpha2, country.alpha2)
    }
  }

  @Test
  fun alpha3LookupIgnoresCaseAndSurroundingSpace() {
    assertEquals(Country.INDONESIA, Country.fromAlpha3("idn"))
    assertEquals(Country.INDONESIA, Country.fromAlpha3("  IDN  "))
    assertNull(Country.fromAlpha3("ID"), "alpha-2 must not resolve; this SDK speaks alpha-3")
  }

  /** The whole point of the utility: what it returns is what `phone()` accepts. */
  @Test
  fun theResultIsAcceptedByTheQueryBuilder() {
    val query = TraciveQuery.phone(normalizePhoneNumber("IDN", "0857-1860-9079"))
    assertEquals("PHONE_+6285718609079", query.searchQueryIndex)
    assertEquals(
      query.searchQueryIndex,
      TraciveQuery.phone(normalizePhoneNumber("+62", "0857 1860 9079")).searchQueryIndex,
      "alpha-3 and dial code must produce one key, not two",
    )
  }
}
