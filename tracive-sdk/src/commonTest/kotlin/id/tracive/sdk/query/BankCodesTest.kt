package id.tracive.sdk.query

import id.tracive.sdk.TraciveQueryException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `bankAccountByShortCode` and `bankAccountByBic` beyond the shared vectors.
 *
 * The inputs below were run through the Tracive API's own translation and every outcome matches it.
 * A key built from a partner's code has to be the key the server builds from it, or the local cache
 * and the server disagree about which record was asked for.
 */
class BankCodesTest {

  /** A cross-SDK wire contract, so spelled as a literal: a rename must fail here. */
  @Test
  fun theReasonTokenIsUnknownBankCode() {
    assertEquals("unknown_bank_code", TraciveQueryException.UNKNOWN_BANK_CODE)
  }

  @Test
  fun aTranslatedCodeBuildsExactlyTheKeyItsBankCodeBuilds() {
    val byCode = TraciveQuery.bankAccount("IDN", "BCA", "1234567890")
    assertEquals(byCode, TraciveQuery.bankAccountByShortCode("IDN", "014", "1234567890"))
    assertEquals(byCode, TraciveQuery.bankAccountByBic("IDN", "CENAIDJA", "1234567890"))
    assertEquals("BANK_IDN_BCA_1234567890", byCode.searchQueryIndex)

    // The table stores Taiwan's SinoPac in mixed case. It still goes through the bank-code fold, so
    // it upper-cases exactly as the server's key does.
    assertEquals(
      "BANK_TWN_SINOPAC_1",
      TraciveQuery.bankAccountByShortCode("TWN", "807", "1").searchQueryIndex,
    )
    assertEquals(
      "BANK_TWN_SINOPAC_1",
      TraciveQuery.bankAccountByBic("TWN", "SINOTWTP", "1").searchQueryIndex,
    )
  }

  /** Every entry of the bundled table, so a map built from only part of it cannot pass. */
  @Test
  fun everyTableEntryResolvesToItsBankCode() {
    var checked = 0
    for ((country, codes) in bundledTable()) {
      for ((code, bankCode) in codes.section("shortCode")) {
        assertEquals(
          TraciveQuery.bankAccount(country, bankCode, "1"),
          TraciveQuery.bankAccountByShortCode(country, code, "1"),
          "$country short code $code",
        )
        checked += 1
      }
      for ((bic, bankCode) in codes.section("bic")) {
        val expected = TraciveQuery.bankAccount(country, bankCode, "1")
        assertEquals(expected, TraciveQuery.bankAccountByBic(country, bic, "1"), "$country $bic")
        assertEquals(expected, TraciveQuery.bankAccountByBic(country, "${bic}XXX", "1"), bic)
        checked += 1
      }
    }
    assertTrue(checked > 0, "no table entry was checked, so nothing was proved")
  }

  @Test
  fun leadingZerosAreOptionalOnAnAllDigitCode() {
    for (code in listOf("14", "0014", "00000014")) {
      assertEquals(bank("IDN", "BCA"), TraciveQuery.bankAccountByShortCode("IDN", code, "1"), code)
    }
    assertEquals(bank("TUR", "ZIRAAT"), TraciveQuery.bankAccountByShortCode("TUR", "10", "1"))
    assertEquals(bank("EGY", "MISR"), TraciveQuery.bankAccountByShortCode("EGY", "2", "1"))
    // No bank is listed under a code of only zeros, however many there are.
    assertUnknown { TraciveQuery.bankAccountByShortCode("IDN", "0", "1") }
    assertUnknown { TraciveQuery.bankAccountByShortCode("IDN", "000", "1") }
    // Only an all-digit code loses its zeros: "0HBL" is not "HBL".
    assertEquals(bank("NPL", "HIMALAYAN"), TraciveQuery.bankAccountByShortCode("NPL", "hbl", "1"))
    assertUnknown { TraciveQuery.bankAccountByShortCode("NPL", "0hbl", "1") }
  }

  @Test
  fun theShortCodeFoldIsTheServers() {
    for (code in listOf("0-1.4", "﻿014　", " 0 1 4 ")) {
      assertEquals(bank("IDN", "BCA"), TraciveQuery.bankAccountByShortCode("IDN", code, "1"), code)
    }
    assertEquals(bank("NPL", "HIMALAYAN"), TraciveQuery.bankAccountByShortCode("NPL", "h.b-l", "1"))
    // U+200B is not JavaScript whitespace, so it stays and the code is unknown.
    assertUnknown { TraciveQuery.bankAccountByShortCode("IDN", "014​", "1") }
    // Arabic-Indic and fullwidth digits are not ASCII digits, and NFC does not fold them to one.
    assertUnknown { TraciveQuery.bankAccountByShortCode("IDN", "١٤", "1") }
    assertUnknown { TraciveQuery.bankAccountByShortCode("IDN", "０１４", "1") }
  }

  @Test
  fun theBicFoldIsTheServers() {
    for (bic in listOf("CENAIDJA", "cenaidjaxxx", "CENA IDJA XXX", "cenaıdja")) {
      assertEquals(bank("IDN", "BCA"), TraciveQuery.bankAccountByBic("IDN", bic, "1"), bic)
    }
    assertEquals(bank("SGP", "DBS"), TraciveQuery.bankAccountByBic("SGP", "DBSSSGSG", "1"))
    // Malformed: a dash is not stripped from a BIC, 9 and 10 characters are not a BIC, and the
    // first six characters are letters. A BIC is looked up in the given country only.
    for (bic in listOf("CENA-IDJA", "CENAIDJAX", "CENAIDJAXX", "1ENAIDJA", "", "DBSSSGSG")) {
      assertUnknown(bic) { TraciveQuery.bankAccountByBic("IDN", bic, "1") }
    }
  }

  /**
   * The server keeps its table in maps because a plain JavaScript object answers these names with
   * an inherited member. A Kotlin map has none, so this pins that no lookup path reintroduces one.
   */
  @Test
  fun prototypeMemberNamesAreMisses() {
    val names = listOf("constructor", "__proto__", "toString", "hasOwnProperty", "valueOf")
    for (name in names) {
      assertUnknown(name) { TraciveQuery.bankAccountByShortCode("IDN", name, "1") }
      assertUnknown(name) { TraciveQuery.bankAccountByBic("IDN", name, "1") }
      assertNull(BankCodes.byShortCode("IDN", name), name)
      assertNull(BankCodes.byBic("IDN", name), name)
      assertNull(BankCodes.byShortCode(name, "014"), "country $name")
      assertNull(BankCodes.byBic(name, "CENAIDJA"), "country $name")
    }
  }

  @Test
  fun theCountryIsCheckedBeforeTheCode() {
    val notAlpha3 = TraciveQueryException.COUNTRY_NOT_ALPHA3
    assertEquals(notAlpha3, reasonFor { TraciveQuery.bankAccountByShortCode("ID", "999", "1") })
    assertEquals(notAlpha3, reasonFor { TraciveQuery.bankAccountByBic("ID", "CENA", "1") })
    assertEquals(bank("IDN", "BCA"), TraciveQuery.bankAccountByShortCode(" idn ", "014", "1"))
    // A well-shaped country with no list is an unknown code, not a bad country.
    assertUnknown { TraciveQuery.bankAccountByShortCode("XXX", "014", "1") }
  }

  /** The code is resolved before the account is folded, in the server's order. */
  @Test
  fun anUnknownCodeWinsOverAnEmptyAccount() {
    val emptyAccount = TraciveQueryException.EMPTY_ACCOUNT_ID
    assertUnknown { TraciveQuery.bankAccountByShortCode("IDN", "999", "  ") }
    assertUnknown { TraciveQuery.bankAccountByBic("IDN", "CENA", "  ") }
    assertEquals(emptyAccount, reasonFor { TraciveQuery.bankAccountByShortCode("IDN", "014", " ") })
    assertEquals(emptyAccount, reasonFor { TraciveQuery.bankAccountByBic("IDN", "CENAIDJA", " ") })
  }

  /**
   * Properties of the bundled table the lookup relies on, so a regenerated table that broke one
   * fails here rather than resolving a partner's code to the wrong bank.
   *
   * Leading-zero stripping must not make two banks' codes collide: the server builds that map from
   * JavaScript object entries, whose order differs from the file's, so a collision would resolve
   * differently there and here.
   */
  @Test
  fun theBundledTableHasNoUnreachableOrAmbiguousEntries() {
    for ((country, codes) in bundledTable()) {
      val bare = mutableMapOf<String, String>()
      for ((code, bankCode) in codes.section("shortCode")) {
        assertTrue(bankCode.isNotEmpty(), "$country short code $code maps to an empty bank code")
        assertEquals(code, foldBankShortCode(code), "$country short code $code is not folded")
        if (code.all { it in '0'..'9' }) {
          val stripped = code.trimStart('0').ifEmpty { "0" }
          val other = bare.put(stripped, bankCode)
          assertTrue(other == null || other == bankCode, "$country $code collides with $other")
        }
      }
      for ((bic, bankCode) in codes.section("bic")) {
        assertTrue(bankCode.isNotEmpty(), "$country $bic maps to an empty bank code")
        assertEquals(bic, foldBic(bic), "$country $bic is not an 8-character folded BIC")
      }
    }
  }

  private fun bank(country: String, bankCode: String) =
    TraciveQuery.bankAccount(country, bankCode, "1")

  private fun reasonFor(block: () -> Unit): String =
    assertFailsWith<TraciveQueryException> { block() }.reason

  private fun assertUnknown(input: String = "", block: () -> Unit) =
    assertEquals(TraciveQueryException.UNKNOWN_BANK_CODE, reasonFor(block), input)

  private fun bundledTable(): Map<String, JsonObject> =
    Json.parseToJsonElement(BANK_CODES_JSON).jsonObject.getValue("countries").jsonObject.mapValues {
      it.value.jsonObject
    }

  private fun JsonObject.section(name: String): Map<String, String> =
    getValue(name).jsonObject.mapValues { it.value.jsonPrimitive.content }
}
