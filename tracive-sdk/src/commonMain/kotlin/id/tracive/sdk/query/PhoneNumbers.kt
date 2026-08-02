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
 *   the number contains no digits at all. It never returns an empty string: an empty key would
 *   reach `phone()` and be rejected there instead, one layer away from the mistake that caused it.
 */
fun normalizePhoneNumber(countryOrDialCode: String, phoneNumber: String): String {
  val dialCode = resolveDialCode(countryOrDialCode)
  var digits = phoneNumber.filter { it in '0'..'9' }
  if (digits.isEmpty()) throw TraciveQueryException(TraciveQueryException.EMPTY_PHONE_NUMBER)

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

  if (digits.isEmpty()) throw TraciveQueryException(TraciveQueryException.EMPTY_PHONE_NUMBER)
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
  val trimmed = countryOrDialCode.trim()
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
