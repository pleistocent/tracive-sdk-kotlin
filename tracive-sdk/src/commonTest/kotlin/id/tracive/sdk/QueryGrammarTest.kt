package id.tracive.sdk

import id.tracive.sdk.query.MAX_DOCUMENT_ID_BYTES
import id.tracive.sdk.query.TraciveQuery
import id.tracive.sdk.query.requireValidDocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The anti-drift contract.
 *
 * `query-grammar-vectors.json` is vendored verbatim into this repo, into the Flutter SDK, into the
 * Swift SDK and into the backend. All four must fold these inputs to byte-identical keys; if they
 * drift, a search silently stops finding a scammer the write path stored, and the resulting miss is
 * still billed. This class drives every entry of that file rather than restating any of it, so a
 * rule can only change by changing the shared fixture.
 *
 * Fixture version 2 added a `crossLanguageSemantics` section and a `documentIdGuard` section. Both
 * exist because the backend folds in JavaScript and no platform's built-ins match JavaScript's
 * semantics: `trim`, `\s`, `\d` and the case mappings all differ somewhere. Those two sections are
 * driven here as well, so "the SDK uses its platform default" cannot pass silently.
 *
 * Nothing here touches the network. Folding happens entirely on the client, by design.
 */
class QueryGrammarTest {

  private val fixture: JsonObject = Json.parseToJsonElement(QUERY_GRAMMAR_VECTORS_JSON).jsonObject

  private val vectors: JsonArray = fixture.getValue("vectors").jsonArray

  private val rejected: JsonArray = fixture.getValue("rejected").jsonArray

  private val documentIdGuard: JsonObject = fixture.getValue("documentIdGuard").jsonObject

  private val crossLanguageSemantics: JsonObject =
    fixture.getValue("crossLanguageSemantics").jsonObject

  @Test
  fun theEmbeddedFixtureIsTheWholeVendoredFile() {
    assertEquals(2, fixture.getValue("version").jsonPrimitive.int, "fixture schema version")
    assertEquals(33, vectors.size, "vector count — did the embedded copy get truncated?")
    assertEquals(11, rejected.size, "rejected count — did the embedded copy get truncated?")
    assertTrue(
      fixture.getValue("derivedFrom").jsonObject.string("authority").isNotBlank(),
      "the fixture must still record where its expected values came from",
    )
    // Version 2 is exactly these two sections plus eight vectors. A copy that still parses but has
    // lost a section would leave the platform-default traps untested, which is the one failure this
    // file exists to prevent.
    assertTrue(fixture.containsKey("crossLanguageSemantics"), "crossLanguageSemantics section")
    assertTrue(fixture.containsKey("documentIdGuard"), "documentIdGuard section")
  }

  /**
   * The NFC vector only means anything if the embedded copy kept its exact code points: the input
   * carries a COMBINING ACUTE ACCENT and the expectation carries a precomposed U+00E9. An editor or
   * a build step that "helpfully" normalises the file would leave this test passing vacuously, so
   * assert the code points directly.
   */
  @Test
  fun theNfcVectorSurvivedEmbedding() {
    val vector = vectorWithId("email-nfc-decomposed")
    val input = vector.getValue("input").jsonObject.string("value")
    val expected = vector.string("expected")
    assertTrue(input.contains(COMBINING_ACUTE), "input must still hold the combining acute accent")
    assertFalse(input.contains(E_ACUTE), "input must not have been pre-composed")
    assertTrue(expected.contains(E_ACUTE), "expectation must still hold the precomposed e-acute")
    assertFalse(expected.contains(COMBINING_ACUTE), "expectation must not hold a combining mark")
  }

  /**
   * The same argument as [theNfcVectorSurvivedEmbedding], for the version-2 vectors whose whole
   * point is one invisible character. If an editor replaced the NBSP with a plain space or dropped
   * the ZWSP, all three would still pass while testing nothing.
   */
  @Test
  fun theInvisibleCharacterVectorsSurvivedEmbedding() {
    val nbspEmail =
      vectorWithId("email-nbsp-is-trimmed").getValue("input").jsonObject.string("value")
    assertTrue(nbspEmail.startsWith(NBSP) && nbspEmail.endsWith(NBSP), "NBSP must wrap the address")

    val zwspEmail = vectorWithId("email-zwsp-is-not-trimmed")
    assertTrue(
      zwspEmail.getValue("input").jsonObject.string("value").startsWith(ZWSP),
      "the ZWSP input must still lead with U+200B",
    )
    assertTrue(
      zwspEmail.string("expected").startsWith("EMAIL_$ZWSP"),
      "the ZWSP expectation must still keep U+200B — the server keeps it, so the SDK must too",
    )

    val account =
      vectorWithId("bank-account-nbsp-stripped").getValue("input").jsonObject.string("accountId")
    assertTrue(account.contains(NBSP), "the account number must still hold an NBSP, not a space")

    val dottedI =
      vectorWithId("email-dotted-capital-I-lowercases-to-two-codepoints")
        .getValue("input")
        .jsonObject
        .string("value")
    assertTrue(dottedI.startsWith(DOTTED_CAPITAL_I), "the input must still lead with U+0130")
  }

  @Test
  fun everyVectorFoldsToItsExpectedKey() {
    for (element in vectors) {
      val vector = element.jsonObject
      val id = vector.string("id")
      val query = buildQuery(vector.string("type"), vector.getValue("input").jsonObject, id)
      assertEquals(vector.string("expected"), query.searchQueryIndex, "vector $id")
    }
  }

  @Test
  fun everyRejectedInputIsRefusedWithTheStatedReason() {
    for (element in rejected) {
      val entry = element.jsonObject
      val id = entry.string("id")
      val type = entry.string("type")
      val input = entry.getValue("input").jsonObject
      val failure =
        assertFailsWith<TraciveQueryException>("rejected vector $id was accepted") {
          // RAW entries are already-folded keys: they exercise the document-id guard on its own,
          // because no public factory can produce a key such as "__proto__".
          if (type == "RAW") requireValidDocumentId(input.string("value"))
          else buildQuery(type, input, id)
        }
      assertEquals(entry.string("reason"), failure.reason, "rejected vector $id")
    }
  }

  /**
   * The plan lists a DOMAIN input type; the backend never shipped it, so no document with a
   * `DOMAIN_` id exists. A `domain()` factory would build keys that can never hit, and a miss is
   * billed — hence no such factory, and no vector that would justify one.
   */
  @Test
  fun theGrammarEmitsOnlyThePrefixesTheBackendWrites() {
    val supported = setOf("PHONE", "EMAIL", "BANK", "CRYPTO", "USERNAME")
    assertTrue(
      fixture.getValue("notImplemented").jsonObject.containsKey("DOMAIN"),
      "the fixture must keep documenting why DOMAIN is absent",
    )
    for (element in vectors) {
      val vector = element.jsonObject
      val expected = vector.string("expected")
      val prefix = expected.substringBefore('_')
      assertTrue(prefix in supported, "vector ${vector.string("id")} emits prefix $prefix")
    }
  }

  @Test
  fun everyFoldedKeyIsALegalDocumentId() {
    for (element in vectors) {
      val vector = element.jsonObject
      val id = vector.string("id")
      val key = buildQuery(vector.string("type"), vector.getValue("input").jsonObject, id)
      assertEquals(
        key.searchQueryIndex,
        requireValidDocumentId(key.searchQueryIndex),
        "vector $id built a key the guard rejects",
      )
    }
  }

  // ---- documentIdGuard -----------------------------------------------------

  /**
   * Every `documentIdGuard.cases` entry, driven verbatim.
   *
   * The `safe: true` half is the half that catches over-eager guards: `__x`, `x__` and `_` are all
   * legal record keys, and a guard that rejected them would refuse queries the backend answers.
   */
  @Test
  fun theDocumentIdGuardAgreesWithEveryFixtureCase() {
    val cases = documentIdGuard.getValue("cases").jsonArray
    assertEquals(8, cases.size, "documentIdGuard case count — did the embedded copy get truncated?")
    for (element in cases) {
      val case = element.jsonObject
      val value = case.string("value")
      if (case.getValue("safe").jsonPrimitive.boolean) {
        assertEquals(value, requireValidDocumentId(value), "guard rejected the safe id '$value'")
      } else {
        val failure =
          assertFailsWith<TraciveQueryException>("guard accepted the unsafe id '$value'") {
            requireValidDocumentId(value)
          }
        assertEquals(TraciveQueryException.INVALID_DOCUMENT_ID, failure.reason, "id '$value'")
      }
    }
  }

  /**
   * The characters the fixture lists as explicitly allowed must survive inside a key.
   *
   * They are listed because they look dangerous and are not: a guard that blanket-rejected `$`, `[`
   * or `~` would refuse a legal id, and the caller would be told their identifier is invalid when
   * the backend would have answered it.
   */
  @Test
  fun theDocumentIdGuardAllowsEverythingTheFixtureCallsAllowed() {
    for (element in documentIdGuard.getValue("explicitlyAllowed").jsonArray) {
      val entry = element.jsonPrimitive.content
      // Three entries name a shape rather than spelling a literal; the rest are literals.
      val sample =
        when (entry) {
          "spaces" -> "BANK_IDN_BCA_12 34"
          "'.' inside the string" -> "EMAIL_budi@gmail.com"
          "emoji" -> "USERNAME_TIKTOK_scammer\uD83D\uDE00"
          else -> "KEY${entry}KEY"
        }
      assertEquals(sample, requireValidDocumentId(sample), "guard rejected allowed shape: $entry")
    }
  }

  @Test
  fun theDocumentIdGuardRejectsEveryReservedShape() {
    for (key in listOf("BANK_IDN_BCA_12/34", "__proto__", ".", "..", "", "__a__")) {
      val failure =
        assertFailsWith<TraciveQueryException>("guard accepted: $key") {
          requireValidDocumentId(key)
        }
      assertEquals(TraciveQueryException.INVALID_DOCUMENT_ID, failure.reason)
    }
  }

  /**
   * A lone surrogate is listed in `documentIdGuard.rejected`. It has no UTF-8 encoding, so every
   * layer between here and the server substitutes U+FFFD for it: the key that arrives is not the
   * key that was built, and the request is a guaranteed — billed — miss.
   */
  @Test
  fun theDocumentIdGuardRejectsAnUnpairedSurrogate() {
    for (key in listOf("EMAIL_\uD83D", "EMAIL_\uDE00", "EMAIL_a\uD83Db")) {
      val failure =
        assertFailsWith<TraciveQueryException>("guard accepted a lone surrogate in $key") {
          requireValidDocumentId(key)
        }
      assertEquals(TraciveQueryException.INVALID_DOCUMENT_ID, failure.reason)
    }
    // A well-formed pair is fine, and counts as the four UTF-8 bytes it really is.
    val paired = "USERNAME_TIKTOK_\uD83D\uDE00"
    assertEquals(paired, requireValidDocumentId(paired))
  }

  @Test
  fun theDocumentIdGuardMeasuresUtf8BytesNotCharacters() {
    assertEquals(
      MAX_DOCUMENT_ID_BYTES,
      documentIdGuard.getValue("maxUtf8Bytes").jsonPrimitive.int,
      "the SDK's limit must be the fixture's limit",
    )
    // Three-byte characters: 500 of them are exactly at the limit, 501 are over it, and both are
    // far below the limit if you count Kotlin Chars instead.
    val atLimit = THREE_BYTE_CHARACTER.repeat(MAX_DOCUMENT_ID_BYTES / 3)
    assertEquals(atLimit, requireValidDocumentId(atLimit))
    val overLimit = atLimit + THREE_BYTE_CHARACTER
    assertTrue(overLimit.length < MAX_DOCUMENT_ID_BYTES, "the over-limit key is short in Chars")
    val failure = assertFailsWith<TraciveQueryException> { requireValidDocumentId(overLimit) }
    assertEquals(TraciveQueryException.INVALID_DOCUMENT_ID, failure.reason)
  }

  /**
   * The guard runs on the FOLDED key, not on the caller's input.
   *
   * Case mapping is length-changing, so a byte count taken before folding measures a different
   * string: 750 `ß` characters are 1500 UTF-8 bytes on the way in and 1500 ASCII bytes plus the
   * prefix on the way out. Guarding the input would let that key through to a request that the API
   * cannot serve.
   */
  @Test
  fun theDocumentIdGuardRunsAfterFoldingNotBefore() {
    val account = "\u00df".repeat(750)
    assertEquals(1500, account.encodeToByteArray().size, "the input is exactly at the limit")
    assertFailsWith<TraciveQueryException>("an over-long folded key was accepted") {
      TraciveQuery.bankAccount("IDN", "BCA", account)
    }
  }

  // ---- crossLanguageSemantics ---------------------------------------------

  /**
   * Every code point JavaScript's `trim()` strips must be stripped here, and U+200B must not be.
   *
   * Driven from the fixture rather than restated: Kotlin's `trim()` disagrees with JavaScript's at
   * U+FEFF (JavaScript strips it, Kotlin does not) and at U+001C..U+001F (the reverse), so a fold
   * that reaches for the built-in passes the five original whitespace vectors and fails here.
   */
  @Test
  fun theTrimSetIsJavaScriptsAndNotKotlins() {
    val trimSet = crossLanguageSemantics.getValue("trimSet").jsonObject
    val stripped =
      trimSet.getValue("codepoints").jsonArray.map { codePoint(it.jsonPrimitive.content) }
    assertEquals(25, stripped.size, "trimSet size — did the embedded copy get truncated?")
    for (character in stripped) {
      assertEquals(
        "EMAIL_budi@gmail.com",
        TraciveQuery.email("$character  budi@gmail.com$character").searchQueryIndex,
        "U+${character.code.toString(16).uppercase()} must be trimmed, as JS trim() trims it",
      )
    }
    for (element in trimSet.getValue("notStripped").jsonArray) {
      val character = codePoint(element.jsonPrimitive.content)
      assertEquals(
        "EMAIL_${character}budi@gmail.com",
        TraciveQuery.email("${character}budi@gmail.com").searchQueryIndex,
        "U+${character.code.toString(16).uppercase()} must survive — JS trim() leaves it",
      )
    }
    // U+001C..U+001F are the mirror image: Kotlin's trim() strips them and JavaScript's does not.
    // No fixture entry names them, because the fixture states the JS set positively — but a fold
    // that used Kotlin's trim() would silently disagree with the server here.
    for (code in 0x1C..0x1F) {
      val character = code.toChar()
      assertEquals(
        "EMAIL_${character}budi@gmail.com",
        TraciveQuery.email("${character}budi@gmail.com").searchQueryIndex,
        "U+00${code.toString(16).uppercase()} must survive — it is not in JS's trim set",
      )
    }
  }

  /**
   * The whitespace class the bank-account fold strips is the same JavaScript `\s`, not Kotlin's
   * `Char.isWhitespace()` and certainly not `Regex("\\s")`, which is ASCII-only.
   */
  @Test
  fun theBankAccountStripUsesTheJavaScriptWhitespaceClass() {
    val stripped =
      crossLanguageSemantics.getValue("trimSet").jsonObject.getValue("codepoints").jsonArray.map {
        codePoint(it.jsonPrimitive.content)
      }
    for (character in stripped) {
      assertEquals(
        "BANK_IDN_BCA_12345",
        TraciveQuery.bankAccount("IDN", "BCA", "12${character}345").searchQueryIndex,
        "U+${character.code.toString(16).uppercase()} must be stripped from an account number",
      )
    }
  }

  /**
   * Case mapping is locale-invariant and length-changing, exactly as JavaScript's is.
   *
   * `uppercase(Locale.getDefault())` would fold `i` to `İ` under the Turkish locale and build a key
   * the backend never wrote. The two examples the fixture names are the ones that catch a
   * per-character mapping, which is what a hand-rolled fold usually reaches for.
   */
  @Test
  fun caseMappingIsLocaleInvariantAndMayChangeLength() {
    val examples = crossLanguageSemantics.getValue("caseMapping").jsonObject
    assertTrue(examples.containsKey("examples"), "the fixture must keep the case-mapping examples")
    assertEquals(
      "BANK_DEU_DB_SS123",
      TraciveQuery.bankAccount("DEU", "DB", "\u00df123").searchQueryIndex,
      "ß must uppercase to SS — a per-Char mapping leaves it alone",
    )
    assertEquals(
      "EMAIL_i\u0307stanbul@x.com",
      TraciveQuery.email("\u0130stanbul@x.com").searchQueryIndex,
      "İ must lowercase to i + U+0307 — the Turkish locale would give a bare i",
    )
    // The same input under the mapping a Turkish locale would apply, spelled out so the difference
    // is visible rather than implied.
    assertNotEquals(
      "EMAIL_istanbul@x.com",
      TraciveQuery.email("\u0130stanbul@x.com").searchQueryIndex,
    )
  }

  /** JavaScript's `\D` is `[^0-9]`, so a non-ASCII digit is removed and never converted. */
  @Test
  fun phoneFoldingStripsNonAsciiDigitsRatherThanConvertingThem() {
    assertEquals(
      "PHONE_+62812345678",
      TraciveQuery.phone("+62812345678\u0669").searchQueryIndex,
      "U+0669 ARABIC-INDIC NINE must be stripped; an ICU \\d port would keep it as a 9",
    )
    // Devanagari, for a second script — the rule is "ASCII 0-9", not "the digits I thought of".
    assertEquals("PHONE_+62812345678", TraciveQuery.phone("+62812345678\u0969").searchQueryIndex)
  }

  /** NFC is applied to email, username, platform and chain codes only — never to the rest. */
  @Test
  fun nfcAppliesOnlyWhereTheFixtureSaysItDoes() {
    assertTrue(
      crossLanguageSemantics.string("nfcScope").contains("NOT applied"),
      "the fixture must keep stating where NFC does not apply",
    )
    // A decomposed 'é' in an email composes.
    assertEquals(
      "EMAIL_caf\u00e9@example.com",
      TraciveQuery.email("cafe\u0301@example.com").searchQueryIndex,
    )
    // The same two code points in a bank account do NOT compose: the backend does not normalise an
    // account id, so composing here would build a key it never wrote.
    assertEquals(
      "BANK_IDN_BCA_CAFE\u0301123",
      TraciveQuery.bankAccount("IDN", "BCA", "cafe\u0301123").searchQueryIndex,
    )
    // Nor in a crypto address.
    assertEquals("CRYPTO_ETH_cafe\u0301", TraciveQuery.crypto("ETH", "cafe\u0301").searchQueryIndex)
  }

  /**
   * The EVM fold requires a lower-case `0x`. `0X…` is deliberately left unfolded, because the
   * backend's regex requires the lower-case prefix on both its write and its read path.
   */
  @Test
  fun theEvmFoldRequiresALowercase0xPrefix() {
    val checksummed = "0x52908400098527886E0F7030069857D2E4169EE7"
    assertEquals(
      "CRYPTO_ETH_${checksummed.lowercase()}",
      TraciveQuery.crypto("ETH", checksummed).searchQueryIndex,
    )
    val upperPrefix = "0X52908400098527886E0F7030069857D2E4169EE7"
    assertEquals(
      "CRYPTO_ETH_$upperPrefix",
      TraciveQuery.crypto("ETH", upperPrefix).searchQueryIndex,
    )
  }

  /**
   * The bank code is stripped of underscores client-side because only the backend's WRITE path
   * strips them. An unstripped code would build a key no document was written under — and the miss
   * is still billed.
   */
  @Test
  fun theBankCodeIsStrippedOfUnderscoresClientSide() {
    assertEquals(
      TraciveQuery.bankAccount("IDN", "BCA", "1234567890"),
      TraciveQuery.bankAccount("IDN", "B_C_A", "1234567890"),
    )
    // The account number keeps its underscores: the id is split on '_', but everything past the
    // bank code is rejoined.
    assertEquals(
      "BANK_IDN_BCA_123_456",
      TraciveQuery.bankAccount("IDN", "BCA", "123_456").searchQueryIndex,
    )
  }

  /** The fixture must keep telling implementers to fold phone keys client-side. */
  @Test
  fun thePhoneFoldIsTheOnlyOneThereIs() {
    assertTrue(
      crossLanguageSemantics.string("phoneFolding").contains("client-side"),
      "the fixture must keep documenting that phone keys are folded client-side",
    )
  }

  // ---- redaction and identity ---------------------------------------------

  @Test
  fun aQueryNeverPrintsTheIdentifierItHolds() {
    val query = TraciveQuery.phone("+6281234567891")
    assertEquals("TraciveQuery(PHONE, redacted)", query.toString())
    assertFalse(
      query.toString().contains("6281234567891"),
      "toString reaches logs and crash reports; it must not carry the identifier",
    )
    assertEquals("TraciveQuery(EMAIL, redacted)", TraciveQuery.email("Budi@Gmail.com").toString())
  }

  @Test
  fun queriesCompareOnTheirFoldedKey() {
    assertEquals(TraciveQuery.email("Budi@Gmail.com"), TraciveQuery.email("  budi@gmail.com "))
    assertEquals(
      TraciveQuery.email("Budi@Gmail.com").hashCode(),
      TraciveQuery.email("budi@gmail.com").hashCode(),
    )
    assertNotEquals(TraciveQuery.email("a@b.com"), TraciveQuery.email("c@d.com"))
  }

  // ------------------------------------------------------------------------

  private fun vectorWithId(id: String): JsonObject =
    vectors.map { it.jsonObject }.firstOrNull { it.string("id") == id }
      ?: fail("the fixture no longer contains the vector $id")

  private fun buildQuery(type: String, input: JsonObject, id: String): TraciveQuery =
    when (type) {
      "PHONE" -> TraciveQuery.phone(input.string("value"))
      "EMAIL" -> TraciveQuery.email(input.string("value"))
      "BANK" ->
        if (input.getValue("isIban").jsonPrimitive.boolean) {
          TraciveQuery.iban(input.string("iban"))
        } else {
          TraciveQuery.bankAccount(
            input.string("country"),
            input.string("bankCode"),
            input.string("accountId"),
          )
        }
      "CRYPTO" -> TraciveQuery.crypto(input.string("chain"), input.string("address"))
      "USERNAME" -> TraciveQuery.username(input.string("platform"), input.string("handle"))
      else -> fail("vector $id has an unknown type $type — teach this test about it")
    }

  private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

  /** "U+00A0" -> the character. The fixture spells code points, so no editor can fold them. */
  private fun codePoint(spelling: String): Char {
    require(spelling.startsWith("U+")) { "not a code point spelling: $spelling" }
    return spelling.substring(2).toInt(16).toChar()
  }

  private companion object {
    /** U+0301 COMBINING ACUTE ACCENT, spelled as an escape so no editor can silently fold it. */
    const val COMBINING_ACUTE: Char = '\u0301'

    /** U+00E9 LATIN SMALL LETTER E WITH ACUTE, the precomposed form. */
    const val E_ACUTE: Char = '\u00e9'

    /** U+00A0 NO-BREAK SPACE: in JavaScript's trim set, and in Kotlin's. */
    const val NBSP: Char = '\u00a0'

    /** U+200B ZERO WIDTH SPACE: in neither trim set, despite looking like a space. */
    const val ZWSP: Char = '\u200b'

    /** U+0130 LATIN CAPITAL LETTER I WITH DOT ABOVE: lowercases to two code points. */
    const val DOTTED_CAPITAL_I: Char = '\u0130'

    /** U+4E2D: three bytes in UTF-8, one Char in Kotlin -- the point of the length test. */
    const val THREE_BYTE_CHARACTER: String = "\u4e2d"
  }
}
