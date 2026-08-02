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
  if (phoneNumber.trimStart(*TRIMMED).startsWith('+') && !digits.startsWith(dialCode)) {
    throw TraciveQueryException(TraciveQueryException.COUNTRY_CONFLICT)
  }

  // The caller may have typed the country code into the local-number field, or pasted a number that
  // was already international. Strip it rather than emitting +6262…
  if (digits.startsWith("00$dialCode")) {
    digits = digits.removePrefix("00$dialCode")
  } else if (digits.startsWith(dialCode)) {
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
      // North America: the trunk prefix is 1, and a local number is 10 digits.
      DIAL_NANP ->
        if (digits.startsWith("1") && digits.length == 11) digits.substring(1) else digits
      // Everywhere else, including Indonesia, the UK and Germany: the trunk prefix is 0.
      else -> digits.trimStart('0')
    }

  // A number that is nothing but zeros is refused here, not left to `phone()`. For most countries
  // the
  // trunk-prefix rule strips the zeros away and the emptiness check above catches it — but Italy,
  // San
  // Marino, North America and Russia do not strip leading zeros, so `+390000000` survived, matched
  // E.164, reached the network and was billed for a miss it could only ever be. Refusing it here
  // also
  // makes the reason the same in every country instead of `empty_phone_number` in Indonesia and
  // `not_e164` in Italy for the identical input.
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
