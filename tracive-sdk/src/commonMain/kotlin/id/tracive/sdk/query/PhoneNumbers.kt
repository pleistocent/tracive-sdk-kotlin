package id.tracive.sdk.query

import id.tracive.sdk.TraciveQueryException

/**
 * Turns a phone number written the way people in a country actually write it into E.164.
 *
 * `TraciveQuery.phone` deliberately refuses a local format such as `081234567891`, and says why:
 * the SDK cannot know the caller's dial code, and a wrong guess still costs a credit. This function
 * is where that missing dial code comes from, so the strictness downstream stays intact:
 * ```
 * TraciveQuery.phone(normalizePhoneNumber("IDN", "0857-1860-9079"))   // +6285718609079
 * TraciveQuery.phone(normalizePhoneNumber("+62", "0857 1860 9079"))   // the same key
 * ```
 *
 * ### Why this exists rather than being folded into `phone()`
 *
 * It is a plain function on purpose. A partner needs the E.164 form for their own records, their
 * own logging and their own outbound messaging, not only for a Tracive lookup — so it is public,
 * reusable, and does one thing.
 *
 * ### It is a copy of the app's rules, deliberately
 *
 * Every rule below mirrors the Tracive app's own normalizer, including its heuristics. That is the
 * point rather than a shortcut: if the app folds `0857…` one way and the SDK another, the same
 * person becomes two record keys, and a report filed through the app is invisible to a check made
 * through the SDK. `phone-normalization-vectors.json` pins the agreement, and it is vendored
 * byte-identically into all four SDK repositories for the same reason the grammar fixture is.
 */

/** The countries whose trunk prefix is not a leading zero, keyed by dial code. */
private const val DIAL_RUSSIA_KAZAKHSTAN = "7"
private const val DIAL_NANP = "1"
private const val DIAL_ITALY = "39"
private const val DIAL_SAN_MARINO = "378"
private const val DIAL_COTE_DIVOIRE = "225"
private const val DIAL_BENIN = "229"
private const val DIAL_CONGO = "242"
private const val DIAL_HUNGARY = "36"

/**
 * Countries whose trunk prefix is 8, with how many digits follow that 8. Belarus counts the 0 it
 * writes after the 8 (`8 029 123 45 67`); the leading-zero strip removes it afterwards. Source: ITU
 * "Dialling procedures" and each country's numbering plan filed with the ITU.
 */
/**
 * Every length a national number can have, for the dial codes whose national numbers can start with
 * the dial code's own digits (libphonenumber 9.0.42 metadata, all number types). The same table as
 * `nationalNumberLengths` in `phone-normalization-vectors.json`, which the tests sweep.
 */
private val NATIONAL_NUMBER_LENGTHS: Map<String, Set<Int>> =
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

/**
 * Digits that start with [dialCode] are a national number when their length is a national length
 * and their length without the dial code is not. When both are (Indonesia's `62812…`, Italy's
 * `393…`), the dial code is stripped as before, so a number typed with its country code never
 * changes key.
 */
private fun isNationalNumber(dialCode: String, digits: String): Boolean {
  val lengths = NATIONAL_NUMBER_LENGTHS[dialCode] ?: return false
  return digits.length in lengths && digits.length - dialCode.length !in lengths
}

private val DIGITS_AFTER_TRUNK_8 =
  mapOf(
    "375" to 10, // Belarus
    "370" to 8, // Lithuania
    "992" to 9, // Tajikistan
    "993" to 8, // Turkmenistan
    "998" to 9, // Uzbekistan
  )

/**
 * Normalizes [phoneNumber] to E.164 using [countryOrDialCode].
 *
 * @param countryOrDialCode either an ISO 3166-1 **alpha-3** code (`IDN`, `USA` — case-insensitive)
 *   or a dial code with or without `+` (`+62`, `62`). The two are told apart by shape: letters mean
 *   alpha-3, digits mean a dial code. Alpha-2 (`ID`) is rejected rather than guessed at, because
 *   this SDK uses alpha-3 everywhere and accepting both would let a caller's `US` mean something
 *   different here than it does in `TraciveQuery.bankAccount`.
 * @param phoneNumber the number as written locally. Spaces, dashes, brackets and a leading `+` are
 *   all fine.
 * @return the number in E.164, e.g. `+6285718609079`.
 * @throws TraciveQueryException [TraciveQueryException.EMPTY_COUNTRY] when the first argument has
 *   no letters and no digits, [TraciveQueryException.UNKNOWN_COUNTRY] when it looks like a country
 *   code but names no country this SDK knows, or [TraciveQueryException.EMPTY_PHONE_NUMBER] when
 *   the number contains no digits at all, or [TraciveQueryException.COUNTRY_CONFLICT] when the
 *   number carries a `+` whose dial code disagrees with the country named. It never returns an
 *   empty string: an empty key would reach `phone()` and be rejected there instead, one layer away
 *   from the mistake that caused it.
 */
fun normalizePhoneNumber(countryOrDialCode: String, phoneNumber: String): String {
  val dialCode = resolveDialCode(countryOrDialCode)
  var digits = phoneNumber.filter { it in '0'..'9' }
  if (digits.isEmpty()) throw TraciveQueryException(TraciveQueryException.EMPTY_PHONE_NUMBER)

  // A leading `+` is the caller stating "this is already international". If the code that follows
  // disagrees with the country they named, the two arguments contradict each other, and guessing
  // which one is right is exactly what costs a credit: `("IDN", "+64857…")` used to yield
  // `+626485718609079`, a New Zealand number wearing an Indonesian dial code, which can never match
  // a stored record and is billed as a miss anyway.
  //
  // Three deliberate departures from the Tracive app's normalizer live here, and they are not the
  // same kind of thing.
  //
  // A dial code that disagrees with the country named: safe to reject, because every input it
  // rejects produced an unusable key in the app too.
  //
  // `+00<dial>` — the app accepts this and folds it correctly, so here the SDK is genuinely
  // stricter than the app on an input the app handles. That is the intended trade: `+` and `00` are
  // alternative spellings of the same thing rather than stackable ones, what follows `+` must be a
  // country code, and `00` is none. The stored key is `PHONE_+<dial><number>`; admitting one
  // malformed concatenation has no principled stopping point (`+000062`, `++62`).
  //
  // An earlier version of this comment claimed the first reason covered both.
  //
  // A third, quieter one: emptiness is re-checked *after* the trunk prefix is stripped, so `000`,
  // `62` and `1` are refused. The app folds those to `+62` and `+1`, which cannot match any record,
  // so this belongs with the first kind — safe, not stricter. It did not.
  val international = phoneNumber.trimStart(*TRIMMED).startsWith('+')
  if (international && !digits.startsWith(dialCode)) {
    throw TraciveQueryException(TraciveQueryException.COUNTRY_CONFLICT)
  }

  // The caller may have typed the country code into the local-number field, or pasted a number that
  // was already international. Strip it rather than emitting +6262… — unless, without a `+`, the
  // digits are a national number that merely starts with the dial code's own digits (Kazakh 701…).
  if (digits.startsWith("00$dialCode")) {
    digits = digits.removePrefix("00$dialCode")
  } else if (
    digits.startsWith(dialCode) && (international || !isNationalNumber(dialCode, digits))
  ) {
    digits = digits.removePrefix(dialCode)
  }

  digits =
    when (dialCode) {
      // Russia and Kazakhstan: the trunk prefix is 8, and a local number is 10 digits. An 11-digit
      // number starting with 8 is therefore trunk-prefixed; a 10-digit one starting with 8 is not.
      DIAL_RUSSIA_KAZAKHSTAN ->
        if (digits.startsWith("8") && digits.length == 11) digits.substring(1) else digits
      // Italy and San Marino: the leading 0 IS part of the number for landlines. Stripping it would
      // produce a different subscriber, not a tidier one.
      DIAL_ITALY,
      DIAL_SAN_MARINO -> digits
      // Côte d'Ivoire, Benin and the Republic of the Congo have no trunk prefix, and their
      // regulators require the 0 after the country code (+225 07…, +229 01…, +242 06…).
      DIAL_COTE_DIVOIRE,
      DIAL_BENIN,
      DIAL_CONGO -> digits
      // Hungary: the trunk prefix is 06. A national number may start with 6 (Szeged is 62), so the
      // pair is dropped, never a 0 and then a 6 on its own.
      DIAL_HUNGARY -> digits.removePrefix("06").trimStart('0')
      // A trunk 8 (Lithuania's until March 2024, 0 since). Some national numbers start with 8
      // themselves (Belarusian 801 and Lithuanian 800 freephone, Uzbek 88 mobile), so the 8 goes
      // only when exactly a national number's length follows it.
      in DIGITS_AFTER_TRUNK_8 -> {
        val afterTrunk = DIGITS_AFTER_TRUNK_8.getValue(dialCode)
        val national =
          if (digits.startsWith("8") && digits.length == afterTrunk + 1) digits.substring(1)
          else digits
        national.trimStart('0')
      }
      // North America: the trunk prefix is 1, and a local number is 10 digits.
      DIAL_NANP ->
        if (digits.startsWith("1") && digits.length == 11) digits.substring(1) else digits
      // Everywhere else, including Indonesia, the UK and Germany: the trunk prefix is 0.
      else -> digits.trimStart('0')
    }

  // A number that is nothing but zeros is refused here, not left to `phone()`. For most countries
  // the trunk-prefix rule strips the zeros away and the emptiness check above catches it — but
  // Italy, San Marino, Côte d'Ivoire, Benin, the Congo, North America and Russia do not strip
  // leading zeros, so `+390000000` survived, matched E.164, reached the network and was billed for
  // a miss it could only ever be. Refusing it here also makes the reason the same in every country
  // instead of `empty_phone_number` in Indonesia and `not_e164` in Italy for the identical input.
  if (digits.isEmpty() || digits.all { it == '0' }) {
    throw TraciveQueryException(TraciveQueryException.EMPTY_PHONE_NUMBER)
  }
  return "+$dialCode$digits"
}

/**
 * Normalizes [phoneNumber] for a known [country].
 *
 * Prefer this over the string form when the country is known at compile time: a typo in `"IDN"` is
 * found at run time, a typo in `Country.INDONESIA` is not compiled.
 */
fun normalizePhoneNumber(country: Country, phoneNumber: String): String =
  normalizePhoneNumber(country.dialCode, phoneNumber)

/** Alpha-3 in, dial code out; or digits in, the same digits out. */
private fun resolveDialCode(countryOrDialCode: String): String {
  val trimmed = countryOrDialCode.trimCanonically()
  if (trimmed.isEmpty()) throw TraciveQueryException(TraciveQueryException.EMPTY_COUNTRY)

  val digits = trimmed.filter { it in '0'..'9' }
  if (digits.isNotEmpty()) {
    // A dial code. `+62`, `62` and `0062` all mean the same country.
    return digits.trimStart('0').ifEmpty {
      throw TraciveQueryException(TraciveQueryException.EMPTY_COUNTRY)
    }
  }

  return Country.fromAlpha3(trimmed)?.dialCode
    ?: throw TraciveQueryException(TraciveQueryException.UNKNOWN_COUNTRY)
}

/**
 * The characters trimmed from a country or dial code, defined here rather than delegated.
 *
 * Not `String.trim()`. This repository already knows the four platforms disagree about what
 * whitespace is — `js_string.dart` in the Flutter SDK spells it out: Kotlin's `trim()` follows the
 * Unicode White_Space property *plus* U+FEFF, JavaScript's strips a different set. Calling each
 * platform's own `trim()` therefore made `"\uFEFFIDN"` resolve in Flutter and React Native and
 * throw in Kotlin and Swift: one input, two behaviours.
 *
 * ASCII whitespace only, and deliberately **not** U+FEFF. A byte-order mark inside a data value is
 * an encoding fault upstream, and swallowing it here would tell a partner their pipeline is clean
 * while the same mark waits to land in a phone number or an account id, where nothing trims it and
 * the key comes out wrong. An `unknown_country` at the first field is the cheaper lesson.
 */
private val TRIMMED = charArrayOf(' ', '\t', '\n', '\u000B', '\u000C', '\r')

private fun String.trimCanonically(): String = trim(*TRIMMED)
