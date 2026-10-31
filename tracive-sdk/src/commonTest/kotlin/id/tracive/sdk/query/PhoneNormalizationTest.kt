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
      PhoneVector("IDN", "08571860907\uFE0F9", "+6285718609079"),
      PhoneVector("IDN", "0857\u200B1860\u200B9079", "+6285718609079"),
      PhoneVector("  IDN  ", "0857 1860 9079", "+6285718609079"),
      PhoneVector("HUN", "06 30 123 4567", "+36301234567"),
      PhoneVector("HUN", "+36 30 123 4567", "+36301234567"),
      PhoneVector("HUN", "06 62 123 456", "+3662123456"),
      PhoneVector("BLR", "8 029 123 45 67", "+375291234567"),
      PhoneVector("BLR", "+375 29 123 45 67", "+375291234567"),
      PhoneVector("BLR", "8 801 123 4567", "+3758011234567"),
      PhoneVector("LTU", "8 612 34567", "+37061234567"),
      PhoneVector("LTU", "0 612 34567", "+37061234567"),
      PhoneVector("LTU", "800 12345", "+37080012345"),
      PhoneVector("TKM", "8 65 123456", "+99365123456"),
      PhoneVector("TJK", "8 93 123 4567", "+992931234567"),
      PhoneVector("UZB", "8 90 123 45 67", "+998901234567"),
      PhoneVector("UZB", "88 123 45 67", "+998881234567"),
      PhoneVector("CIV", "07 12 34 56 78", "+2250712345678"),
      PhoneVector("CIV", "+225 07 12 34 56 78", "+2250712345678"),
      PhoneVector("BEN", "01 97 12 34 56", "+2290197123456"),
      PhoneVector("COG", "06 123 4567", "+242061234567"),
      PhoneVector("GAB", "077 12 34 56", "+24177123456"),
      PhoneVector("KAZ", "701 234 5678", "+77012345678"),
      PhoneVector("KAZ", "7 701 234 5678", "+77012345678"),
      PhoneVector("NOR", "476 12 345", "+4747612345"),
      PhoneVector("NOR", "47 476 12 345", "+4747612345"),
      PhoneVector("CRI", "5062 4585", "+50650624585"),
      PhoneVector("IDN", "62812 3456 7890", "+6281234567890"),
    )

  private val rejections =
    listOf(
      PhoneRejection("IDN", "   ", "empty_phone_number"),
      PhoneRejection("IDN", "abc-def", "empty_phone_number"),
      PhoneRejection("", "0857123456", "empty_country"),
      PhoneRejection("XXX", "0857123456", "unknown_country"),
      PhoneRejection("ID", "0857123456", "unknown_country"),
      PhoneRejection("IDN", "+64857-1860-9079", "country_conflict"),
      PhoneRejection("USA", "+44 7911 123456", "country_conflict"),
      PhoneRejection("+62", "+64 857 1860 9079", "country_conflict"),
      PhoneRejection("IDN", "+0062 857 1860 9079", "country_conflict"),
      PhoneRejection("+62", "+0062857 1860 9079", "country_conflict"),
      PhoneRejection("IDN", "000", "empty_phone_number"),
      PhoneRejection("IDN", "62", "empty_phone_number"),
      PhoneRejection("USA", "1", "empty_phone_number"),
      PhoneRejection("\uFEFFIDN", "0857 1860 9079", "unknown_country"),
      PhoneRejection("ITA", "0000000", "empty_phone_number"),
      PhoneRejection("ITA", "+39 0000000", "empty_phone_number"),
      PhoneRejection("USA", "0000000000", "empty_phone_number"),
      PhoneRejection("RUS", "00000000000", "empty_phone_number"),
      PhoneRejection("IDN", "0000", "empty_phone_number"),
      PhoneRejection("CIV", "0000000000", "empty_phone_number"),
    )

  private val nationalNumberLengths =
    mapOf(
      "7" to setOf(10, 14),
      "20" to setOf(8, 9, 10),
      "27" to setOf(5, 6, 7, 8, 9, 10),
      "31" to setOf(5, 6, 7, 8, 9, 10, 11),
      "32" to setOf(8, 9),
      "33" to setOf(9),
      "36" to setOf(8, 9),
      "39" to setOf(6, 7, 8, 9, 10, 11, 12),
      "41" to setOf(9, 12),
      "43" to setOf(4, 5, 6, 7, 8, 9, 10, 11, 12, 13),
      "45" to setOf(8),
      "46" to setOf(6, 7, 8, 9, 10, 12),
      "47" to setOf(5, 8),
      "48" to setOf(6, 7, 8, 9, 10),
      "49" to setOf(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
      "51" to setOf(8, 9),
      "53" to setOf(6, 7, 8, 10),
      "55" to setOf(8, 9, 10, 11),
      "62" to setOf(7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17),
      "63" to setOf(6, 8, 9, 10, 11, 12, 13),
      "65" to setOf(8, 10, 11),
      "66" to setOf(8, 9, 10, 13),
      "84" to setOf(7, 8, 9, 10),
      "90" to setOf(7, 10, 12, 13),
      "91" to setOf(8, 9, 10, 11, 12, 13),
      "92" to setOf(8, 9, 10, 11, 12),
      "95" to setOf(6, 7, 8, 9, 10),
      "213" to setOf(8, 9),
      "216" to setOf(8),
      "218" to setOf(9),
      "220" to setOf(7, 9),
      "222" to setOf(8),
      "230" to setOf(7, 8, 10),
      "231" to setOf(7, 8, 9),
      "233" to setOf(8, 9),
      "238" to setOf(7),
      "241" to setOf(7, 8),
      "243" to setOf(7, 8, 9, 10),
      "244" to setOf(9),
      "247" to setOf(5, 6),
      "250" to setOf(8, 9),
      "251" to setOf(9),
      "252" to setOf(6, 7, 8, 9),
      "255" to setOf(9),
      "262" to setOf(9),
      "266" to setOf(8),
      "267" to setOf(7, 8, 10),
      "290" to setOf(4, 5),
      "298" to setOf(6),
      "299" to setOf(6),
      "355" to setOf(6, 7, 8, 9),
      "358" to setOf(5, 6, 7, 8, 9, 10, 11, 12),
      "359" to setOf(6, 7, 8, 9, 12),
      "370" to setOf(8),
      "373" to setOf(8),
      "376" to setOf(6, 8, 9),
      "377" to setOf(8, 9),
      "379" to setOf(6, 7, 8, 9, 10, 11, 12),
      "380" to setOf(9, 10),
      "383" to setOf(8, 9, 10, 11, 12),
      "386" to setOf(5, 6, 7, 8),
      "387" to setOf(8, 9),
      "421" to setOf(6, 7, 9),
      "500" to setOf(5),
      "501" to setOf(7, 11),
      "502" to setOf(8, 11),
      "503" to setOf(7, 8, 11),
      "506" to setOf(8, 10),
      "507" to setOf(7, 8, 10, 11),
      "508" to setOf(6, 9),
      "590" to setOf(9),
      "594" to setOf(9),
      "596" to setOf(9),
      "677" to setOf(5, 7),
      "679" to setOf(7, 11),
      "680" to setOf(7),
      "683" to setOf(4, 7),
      "685" to setOf(5, 6, 7, 10),
      "850" to setOf(8, 10),
      "853" to setOf(7, 8),
      "855" to setOf(8, 9, 10),
      "886" to setOf(7, 8, 9, 10, 11),
      "960" to setOf(7, 10),
      "961" to setOf(7, 8),
      "962" to setOf(8, 9),
      "963" to setOf(8, 9),
      "965" to setOf(7, 8),
      "968" to setOf(7, 8, 9),
      "971" to setOf(5, 6, 7, 8, 9, 10, 11, 12),
      "972" to setOf(7, 8, 9, 10, 11, 12),
      "973" to setOf(8),
      "976" to setOf(8, 9, 10),
      "992" to setOf(9),
      "994" to setOf(9),
      "996" to setOf(9, 10),
      "998" to setOf(9),
    )

  @Test
  fun everyVectorFoldsByteForByte() {
    assertEquals(44, vectors.size, "vector count — did the embedded copy get truncated?")
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
    assertEquals(20, rejections.size, "rejection count")
    for (rejection in rejections) {
      val failure =
        assertFailsWith<TraciveQueryException>("accepted an unusable input") {
          normalizePhoneNumber(rejection.country, rejection.input)
        }
      assertEquals(rejection.reason, failure.reason)
    }
  }

  /**
   * Every dial code and every length up to 17: a number typed without `+` that starts with its own
   * dial code keeps those digits only as the fixture's `nationalNumberLengths` says. The 5s keep
   * trunk rules out of it.
   */
  @Test
  fun aNumberStartingWithItsDialCodeIsReadByTheLengthsTable() {
    assertEquals(96, nationalNumberLengths.size, "lengths table count")
    for (dialCode in Country.entries.map { it.dialCode }.toSet() - "1") {
      val national = nationalNumberLengths[dialCode].orEmpty()
      for (length in dialCode.length + 1..17) {
        val rest = "5".repeat(length - dialCode.length)
        val typed = dialCode + rest
        val keep = length in national && length - dialCode.length !in national
        assertEquals(
          "+$dialCode${if (keep) typed else rest}",
          normalizePhoneNumber(dialCode, typed),
        )
      }
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
