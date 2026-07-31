package id.tracive.sdk

import id.tracive.sdk.query.Nfc
import id.tracive.sdk.query.UnicodeCanonicalData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unicode NFC, which the folding grammar leans on for every free-text identifier.
 *
 * The backend normalises with JavaScript's `String.prototype.normalize("NFC")`, so a divergence
 * here is a divergence in the document id: invisible in review, and billed one miss at a time.
 *
 * Every literal below is written as `\u` escapes on purpose. A test that spelled these as real
 * characters would be one editor-normalisation away from asserting `"x" == "x"`, passing forever
 * while proving nothing. Every expectation here was checked against Python's `unicodedata` first.
 *
 * The tables themselves were verified far more broadly when they were generated — every in-coverage
 * BMP code point, every starter/mark pair, 400,000 randomised multi-mark sequences and all 11,172
 * Hangul syllables, with zero mismatches (see the header of `UnicodeCanonicalData.kt`). What
 * follows pins the behaviours that matter for identifiers, plus the structural invariants the
 * algorithm relies on.
 */
class NfcTest {

  @Test
  fun asciiIsReturnedUntouched() {
    val input = "budi@gmail.com"
    assertSame(input, Nfc.normalize(input), "the fast path must not rebuild an ASCII string")
    assertEquals("", Nfc.normalize(""))
  }

  @Test
  fun aDecomposedSequenceComposes() {
    // "caf" + "e" + U+0301 COMBINING ACUTE ACCENT -> "caf" + U+00E9. The fixture's NFC vector.
    assertEquals("caf\u00e9", Nfc.normalize("cafe\u0301"))
  }

  @Test
  fun anAlreadyComposedSequenceIsUnchanged() {
    assertEquals("caf\u00e9", Nfc.normalize("caf\u00e9"))
  }

  @Test
  fun marksAreSortedIntoCanonicalOrder() {
    // U+0307 (class 230) before U+0323 (class 220) is not canonical order, so NFC swaps them.
    // Neither mark composes onto "q", which makes the reordering the entire observable effect.
    assertEquals("q\u0323\u0307", Nfc.normalize("q\u0307\u0323"))
    assertEquals("q\u0323\u0307", Nfc.normalize("q\u0323\u0307"))
  }

  @Test
  fun aSecondMarkComposesOntoTheResultOfTheFirst() {
    // U+00EA (e-circumflex) + U+0301 -> U+1EBF. Composition has to run against the character it has
    // just produced, not only against the character the run started with.
    assertEquals("\u1ebf", Nfc.normalize("\u00ea\u0301"))
    assertEquals("\u1ebf", Nfc.normalize("e\u0302\u0301"))
  }

  @Test
  fun hangulComposesAndDecomposesAlgorithmically() {
    // L + V -> LV, L + V + T -> LVT, LV + T -> LVT, and an existing syllable is left alone.
    assertEquals("\uac00", Nfc.normalize("\u1100\u1161"))
    assertEquals("\uac01", Nfc.normalize("\u1100\u1161\u11a8"))
    assertEquals("\uac01", Nfc.normalize("\uac00\u11a8"))
    assertEquals("\uac01", Nfc.normalize("\uac01"))
  }

  @Test
  fun singletonDecompositionsAreApplied() {
    // U+212B ANGSTROM SIGN and U+2126 OHM SIGN are canonically the ordinary letters.
    assertEquals("\u00c5", Nfc.normalize("\u212b"))
    assertEquals("\u03a9", Nfc.normalize("\u2126"))
  }

  @Test
  fun compositionExclusionsAreRespected() {
    // U+0958 decomposes to U+0915 U+093C and must NOT recompose: it is a composition exclusion.
    // Getting this wrong would be invisible in the common case and wrong only for Devanagari.
    assertEquals("\u0915\u093c", Nfc.normalize("\u0958"))
    // U+0344 decomposes to U+0308 U+0301, which then both compose onto the iota: -> U+0390.
    assertEquals("\u0390", Nfc.normalize("\u03b9\u0344"))
  }

  @Test
  fun compatibilityDecompositionsAreNotApplied() {
    // This is NFC, not NFKC. U+FF11 FULLWIDTH DIGIT ONE and U+FB01 LATIN SMALL LIGATURE FI carry
    // compatibility mappings only; folding them would merge identifiers the backend keeps apart.
    assertEquals("\uff11", Nfc.normalize("\uff11"))
    assertEquals("\ufb01", Nfc.normalize("\ufb01"))
  }

  @Test
  fun supplementaryPlaneCharactersSurviveIntact() {
    // U+1F600 as a surrogate pair. It has no canonical decomposition, so it must pass through
    // intact and must not disturb the composition that happens after it.
    assertEquals("a\ud83d\ude00b\u00e9", Nfc.normalize("a\ud83d\ude00be\u0301"))
  }

  // -------------------------------------------------------------- tables ----

  @Test
  fun theGeneratedTablesArePairedAndSorted() {
    val keys = UnicodeCanonicalData.DECOMPOSITION_KEYS
    assertEquals(keys.length, UnicodeCanonicalData.DECOMPOSITION_FIRST.length)
    assertEquals(keys.length, UnicodeCanonicalData.DECOMPOSITION_SECOND.length)
    assertStrictlyAscending(keys, "DECOMPOSITION_KEYS")

    val compositions = UnicodeCanonicalData.COMPOSITION_FIRST
    assertEquals(compositions.length, UnicodeCanonicalData.COMPOSITION_SECOND.length)
    assertEquals(compositions.length, UnicodeCanonicalData.COMPOSITION_RESULT.length)
    for (index in 1 until compositions.length) {
      val previous = compositions[index - 1]
      val current = compositions[index]
      val ordered =
        previous < current ||
          (previous == current &&
            UnicodeCanonicalData.COMPOSITION_SECOND[index - 1] <
              UnicodeCanonicalData.COMPOSITION_SECOND[index])
      assertTrue(ordered, "COMPOSITION_* must be sorted by (first, second); broken at $index")
    }

    val combining = UnicodeCanonicalData.COMBINING_CLASS_KEYS
    assertEquals(combining.length, UnicodeCanonicalData.COMBINING_CLASS_VALUES.length)
    assertStrictlyAscending(combining, "COMBINING_CLASS_KEYS")
    for (value in UnicodeCanonicalData.COMBINING_CLASS_VALUES) {
      assertTrue(value.code in 1..254, "combining class ${value.code} is out of range")
    }
  }

  /**
   * The fast path returns any string made only of code points below U+00C0 unchanged. That is sound
   * only because nothing below U+00C0 decomposes, is a combining mark, or can be the second half of
   * a composition pair. Assert it against the tables rather than trusting the comment.
   */
  @Test
  fun nothingBelowTheFastPathThresholdCanAffectNormalisation() {
    assertTrue(
      UnicodeCanonicalData.DECOMPOSITION_KEYS[0].code >= 0xC0,
      "a character below U+00C0 has a canonical decomposition; the fast path is unsound",
    )
    assertTrue(
      UnicodeCanonicalData.COMBINING_CLASS_KEYS[0].code >= 0xC0,
      "a character below U+00C0 is a combining mark; the fast path is unsound",
    )
    for (second in UnicodeCanonicalData.COMPOSITION_SECOND) {
      assertTrue(
        second.code >= 0xC0,
        "U+${second.code.toString(16)} can be composed onto; the fast path is unsound",
      )
    }
  }

  @Test
  fun hangulSyllablesAreLeftToTheAlgorithmicRule() {
    for (key in UnicodeCanonicalData.DECOMPOSITION_KEYS) {
      assertTrue(
        key.code !in 0xAC00..0xD7A3,
        "Hangul syllable U+${key.code.toString(16)} must not be in the table",
      )
    }
  }

  private fun assertStrictlyAscending(table: String, name: String) {
    for (index in 1 until table.length) {
      assertTrue(table[index - 1] < table[index], "$name must ascend strictly; broken at $index")
    }
  }
}
