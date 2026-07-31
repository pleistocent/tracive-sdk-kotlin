package id.tracive.sdk.query

import id.tracive.sdk.TraciveQueryException

/*
 * The `searchQueryIndex` grammar: the exact string folding that turns a caller's identifier into
 * the record key the server writes.
 *
 * Every rule here mirrors the server's own canonical key normalizer, which it applies on both its
 * write and its read path. A fold applied on one side but not the other is invisible in tests of
 * either side alone and shows up in production as "search never finds this scammer" — and because
 * a miss is billed, it also shows up on the bill.
 * `query-grammar-vectors.json`, vendored into this repo and into the Flutter and Swift SDKs, is the
 * contract that keeps all four implementations byte-identical; `QueryGrammarTest` drives it.
 *
 * Folding also happens locally rather than being left to the server because the folded key is the
 * local cache key. An unfolded key would split one entity across several cache entries, so the same
 * account would be paid for once per spelling.
 *
 * Validation runs before any network call: a key that cannot match a stored document still costs a
 * credit when the server answers "not found", so the SDK refuses to send one.
 *
 * THE BACKEND FOLDS IN JAVASCRIPT, AND THIS FILE REIMPLEMENTS JAVASCRIPT SEMANTICS RATHER THAN
 * KOTLIN'S. The two disagree in three places, all of them silent:
 *
 *  1. Whitespace. `String.prototype.trim()` and `/\s/` in JavaScript strip exactly the set in
 *     [JS_WHITESPACE] — which includes U+FEFF and excludes U+200B and U+001C..U+001F. Kotlin's
 *     `trim()` and `Char.isWhitespace()` strip U+001C..U+001F and leave U+FEFF, and `Regex("\\s")`
 *     is ASCII-only. Every trim and every whitespace strip below goes through the hand-written set.
 *  2. Digits. JavaScript's `\D` is exactly `[^0-9]`, so an Arabic-Indic digit is REMOVED from a
 *     phone number, never converted to its ASCII equivalent. `Char.isDigit()` is Unicode Nd and
 *     would keep it, building a key the backend cannot have written. Phone folding tests ASCII
 *     `'0'..'9'` explicitly.
 *  3. Case. JavaScript's `toLowerCase()`/`toUpperCase()` are locale-INVARIANT full case mappings
 *     that can change a string's length (`ß` -> `SS`, `İ` -> `i` + U+0307). Kotlin's no-argument
 *     `uppercase()`/`lowercase()` are the same thing; the locale-taking overloads are not, and
 *     `uppercase(Locale.getDefault())` would fold `i` to `İ` on a Turkish device and build a key
 *     that misses. No call below passes a locale, and none may be added.
 *
 * Because case mapping is length-changing, the document-id guard runs on the FOLDED key, after
 * every fold — never on the caller's input.
 */

/** The API's key length limit. */
internal const val MAX_DOCUMENT_ID_BYTES: Int = 1500

/**
 * The exact set of code points JavaScript's `String.prototype.trim()` strips, and the exact set its
 * `\s` character class matches. Hand-written because no Kotlin built-in matches it.
 *
 * It is `WhiteSpace` plus `LineTerminator` plus U+FEFF. Two members are the whole reason this
 * constant exists rather than a call to `trim()`.
 *
 * U+FEFF is in the set, and Kotlin does not strip it. U+200B (ZERO WIDTH SPACE) is deliberately NOT
 * in the set even though it looks like a space: the backend keeps it, so the SDK keeps it, and the
 * resulting key legitimately misses.
 */
private val JS_WHITESPACE: Set<Char> =
  setOf(
    '\u0009', // CHARACTER TABULATION
    '\u000a', // LINE FEED
    '\u000b', // LINE TABULATION -- ICU's \s omits this one, which is why Swift needs the list too
    '\u000c', // FORM FEED
    '\u000d', // CARRIAGE RETURN
    '\u0020', // SPACE
    '\u00a0', // NO-BREAK SPACE -- arrives in every paste from a PDF or a bank statement
    '\u1680', // OGHAM SPACE MARK
    '\u2000', // EN QUAD
    '\u2001', // EM QUAD
    '\u2002', // EN SPACE
    '\u2003', // EM SPACE
    '\u2004', // THREE-PER-EM SPACE
    '\u2005', // FOUR-PER-EM SPACE
    '\u2006', // SIX-PER-EM SPACE
    '\u2007', // FIGURE SPACE
    '\u2008', // PUNCTUATION SPACE
    '\u2009', // THIN SPACE
    '\u200a', // HAIR SPACE
    '\u2028', // LINE SEPARATOR
    '\u2029', // PARAGRAPH SEPARATOR
    '\u202f', // NARROW NO-BREAK SPACE
    '\u205f', // MEDIUM MATHEMATICAL SPACE
    '\u3000', // IDEOGRAPHIC SPACE
    '\ufeff', // ZERO WIDTH NO-BREAK SPACE / BOM -- in JS's set, absent from Kotlin's
  )

/**
 * `String.prototype.trim()`, exactly.
 *
 * Kotlin's `trim()` is close but not equal: it strips U+001C..U+001F (the ASCII information
 * separators), which JavaScript keeps, and it leaves U+FEFF, which JavaScript strips. Either
 * difference produces a key one character away from the one the server stored.
 */
private fun String.jsTrim(): String {
  var start = 0
  var end = length
  while (start < end && this[start] in JS_WHITESPACE) start += 1
  while (end > start && this[end - 1] in JS_WHITESPACE) end -= 1
  return if (start == 0 && end == length) this else substring(start, end)
}

/**
 * E.164: a `+`, a country code that never starts with zero, then 7 to 15 digits in total.
 *
 * The SDK rejects anything else rather than guessing. The server cannot up-convert a local number
 * to E.164 — it does not know the dial code at that layer — so `081234567891` would quietly index
 * as a different entity from `+6281234567891`, and the resulting miss would still be billed.
 */
private val E164 = Regex("^\\+[1-9][0-9]{6,14}\$")

/**
 * ISO 3166-1 alpha-3 shape.
 *
 * Shape only, deliberately: the server's country canonicaliser trusts any `^[A-Z]{3}$` code as
 * already canonical and never checks it against a country list. Bundling a country list in three
 * SDKs would add a way for the SDK to reject a country the server accepts, and would need keeping
 * in sync forever. What this check is really for is catching an alpha-2 code: the server would
 * up-convert `ID` to `IDN`, but then the local cache key would not match the server's key.
 */
private val ALPHA3 = Regex("^[A-Z]{3}\$")

/**
 * An EVM (Ethereum-style) address: `0x` plus 40 hex digits.
 *
 * The `0x` prefix must be LOWER CASE. That is not an oversight to tidy up: the backend's test is
 * `/^0x[0-9a-fA-F]{40}$/`, so an address written `0X…` fails it and is stored unfolded. The write
 * path applies the same rule, so both sides still agree; "fixing" it here would be the drift.
 */
private val EVM_ADDRESS = Regex("^0x[0-9a-fA-F]{40}\$")

/**
 * `PHONE_<value>`: strip everything that is not a digit, keeping a single leading `+`.
 *
 * ASCII digits only, matching the backend's `/\D/g` — JavaScript's `\D` is exactly `[^0-9]`, so an
 * Arabic-Indic digit is removed rather than converted. `Char.isDigit()` is Unicode Nd and would
 * keep U+0669 as a digit; an ICU-based `\d` port in the Swift SDK has the same trap.
 *
 * The server folds phone keys too, but keep folding here regardless: this key is also the local
 * cache key, so an unfolded one would split a single entity across several cache entries and defeat
 * the cache-hit path. Never rely on the server to fold for us.
 */
internal fun foldPhoneKey(e164: String): String {
  val trimmed = e164.jsTrim()
  val folded = StringBuilder(trimmed.length + 1)
  if (trimmed.startsWith('+')) folded.append('+')
  for (index in trimmed.indices) {
    val character = trimmed[index]
    if (character in '0'..'9') folded.append(character)
  }
  val value = folded.toString()
  if (!E164.matches(value)) throw TraciveQueryException(TraciveQueryException.NOT_E164)
  return requireValidDocumentId("PHONE_$value")
}

/**
 * `EMAIL_<value>`: NFC, then lower case, then trim — in that order, as the backend does it.
 *
 * The order is part of the contract, not a style choice: `lowercase()` is a full case mapping and
 * can change length, so trimming first would trim a different string.
 */
internal fun foldEmailKey(value: String): String {
  val folded = Nfc.normalize(value).lowercase().jsTrim()
  if (folded.isEmpty()) throw TraciveQueryException(TraciveQueryException.EMPTY_VALUE)
  return requireValidDocumentId("EMAIL_$folded")
}

/** `BANK_IBAN_<accountId>`. `IBAN` is a literal marker in the id, not a country code. */
internal fun foldIbanKey(iban: String): String {
  val accountId = foldBankAccountId(iban)
  if (accountId.isEmpty()) throw TraciveQueryException(TraciveQueryException.EMPTY_ACCOUNT_ID)
  return requireValidDocumentId("BANK_IBAN_$accountId")
}

/** `BANK_<country>_<bankCode>_<accountId>`. */
internal fun foldBankAccountKey(countryAlpha3: String, bankCode: String, account: String): String {
  val country = countryAlpha3.uppercase().jsTrim()
  if (!ALPHA3.matches(country)) {
    throw TraciveQueryException(TraciveQueryException.COUNTRY_NOT_ALPHA3)
  }
  // Underscores are stripped from the bank code because the id is parsed by splitting on "_": an
  // underscore here would shift the account number into the bank-code position. They are kept in
  // the account number itself, where the backend's parser joins everything past the code back up.
  //
  // Stripping it is a client-side-only step in one direction that matters: the backend's WRITE path
  // strips it, its READ path does not. A bank code that still carries an underscore therefore
  // builds a key no document was ever written under — a guaranteed miss, and a miss is billed.
  val code = Nfc.normalize(bankCode).uppercase().jsTrim().replace("_", "")
  if (code.isEmpty()) throw TraciveQueryException(TraciveQueryException.EMPTY_BANK_CODE)
  val accountId = foldBankAccountId(account)
  if (accountId.isEmpty()) throw TraciveQueryException(TraciveQueryException.EMPTY_ACCOUNT_ID)
  return requireValidDocumentId("BANK_${country}_${code}_$accountId")
}

/**
 * `CRYPTO_<chain>_<address>`.
 *
 * The address fold is keyed on the address *shape*, never on the chain code. USDT, BNB and AVAX
 * each accept both an EVM address and a base58/bech32 one, so a chain-keyed rule would either miss
 * their EVM branch or corrupt the other. EIP-55 checksum casing is presentational, so an EVM
 * address folds to lower case; base58 is case-SENSITIVE and must not be touched (it cannot collide
 * with the EVM test either, since base58 excludes `0`). bech32 and Stellar base32 are left alone
 * because their validation regexes admit only one case, so the other case never reaches the index.
 *
 * NFC is deliberately NOT applied to the address — it is applied to the chain code only. Addresses
 * are ASCII by construction, and normalising one would be a fold the backend does not perform.
 *
 * BEYOND THE FIXTURE: an empty address is rejected with [TraciveQueryException.EMPTY_VALUE]. The
 * fixture has no vector for it, but the server's own normalizer refuses to fold a `CRYPTO_` key
 * whose address is empty and hands the key straight back, so `CRYPTO_ETH_` could only ever miss —
 * and be billed. The existing reason token is reused rather than a new one invented, because a
 * token this SDK made up would not exist in the Flutter or Swift SDKs. Mirror this there.
 */
internal fun foldCryptoKey(chain: String, address: String): String {
  val chainCode = Nfc.normalize(chain).uppercase().jsTrim()
  if (chainCode.isEmpty()) throw TraciveQueryException(TraciveQueryException.EMPTY_CHAIN)
  val trimmed = address.jsTrim()
  val folded = if (EVM_ADDRESS.matches(trimmed)) trimmed.lowercase() else trimmed
  if (folded.isEmpty()) throw TraciveQueryException(TraciveQueryException.EMPTY_VALUE)
  return requireValidDocumentId("CRYPTO_${chainCode}_$folded")
}

/**
 * `USERNAME_<platform>_<handle>`.
 *
 * A leading `@` is deliberately kept: the backend folds only case, NFC and whitespace, so a report
 * filed as `@scammerx` lives under a different id from one filed as `scammerx`. Stripping the `@`
 * client-side would build a key that misses the reports that carry it.
 *
 * BEYOND THE FIXTURE: an empty platform is rejected with [TraciveQueryException.EMPTY_VALUE], for
 * the same reason an empty crypto address is. See [foldCryptoKey].
 */
internal fun foldUsernameKey(platform: String, handle: String): String {
  val platformCode = Nfc.normalize(platform).uppercase().jsTrim()
  if (platformCode.isEmpty()) throw TraciveQueryException(TraciveQueryException.EMPTY_VALUE)
  val foldedHandle = Nfc.normalize(handle).lowercase().jsTrim()
  if (foldedHandle.isEmpty()) throw TraciveQueryException(TraciveQueryException.EMPTY_HANDLE)
  return requireValidDocumentId("USERNAME_${platformCode}_$foldedHandle")
}

/**
 * Upper case, then remove every character in [JS_WHITESPACE] and every ASCII hyphen.
 *
 * Upper-casing happens first and NFC is deliberately not applied, matching the backend exactly.
 * Upper-casing first is also why `ß123` becomes `SS123` rather than staying as it was. The fold is
 * length-changing, which is why the length guard runs afterwards and never on the caller's input.
 *
 * IBANs are printed in space-separated groups of four and domestic account numbers get typed with
 * dashes; all three spellings are the same account, so both separators go. The separator class is
 * the backend's `/[\s-]/`, which is [JS_WHITESPACE] — it includes U+00A0, the space that arrives in
 * every paste from a bank statement, and Kotlin's `Regex("\\s")` would leave that one behind.
 * Underscores stay: the id is split on `_`, but everything past the bank code is rejoined.
 */
private fun foldBankAccountId(account: String): String {
  val uppercased = account.uppercase()
  val folded = StringBuilder(uppercased.length)
  for (index in uppercased.indices) {
    val character = uppercased[index]
    if (character !in JS_WHITESPACE && character != '-') folded.append(character)
  }
  return folded.toString()
}

/**
 * Reject a folded key that the API cannot store under, before it is spent on a request.
 *
 * A document id may not be empty, may not contain `/`, may not be `.` or `..`, may not match
 * `__.*__` (that shape is reserved), may not carry an unpaired UTF-16 surrogate, and may not exceed
 * [MAX_DOCUMENT_ID_BYTES] UTF-8 bytes.
 *
 * Everything else is legal and must stay legal: spaces, a `.` inside the string, `@`, `+`, `#`,
 * `$`, `[`, `]`, `*`, `~`, emoji, and a lone leading or trailing `__` (`__x`, `x__` and `_` are all
 * valid ids — only the fully enclosing `__…__` shape is reserved).
 *
 * This runs on the FOLDED key. Case mapping is not length-preserving, so a byte count taken before
 * folding would be measuring a different string.
 *
 * @return [key] unchanged, so this can wrap the return of a fold.
 */
internal fun requireValidDocumentId(key: String): String {
  val invalid =
    key.isEmpty() ||
      key.contains('/') ||
      key == "." ||
      key == ".." ||
      (key.length >= 4 && key.startsWith("__") && key.endsWith("__")) ||
      key.hasUnpairedSurrogate() ||
      key.utf8Size() > MAX_DOCUMENT_ID_BYTES
  if (invalid) throw TraciveQueryException(TraciveQueryException.INVALID_DOCUMENT_ID)
  return key
}

/**
 * True if [this] holds a high surrogate not followed by a low one, or a low surrogate not preceded
 * by a high one.
 *
 * Such a string is not valid UTF-16 and has no UTF-8 encoding. the API rejects it; more to the
 * point, every layer between here and the server silently substitutes U+FFFD for it, so the key
 * that arrives is not the key that was built and the request is a guaranteed — billed — miss.
 */
private fun String.hasUnpairedSurrogate(): Boolean {
  var index = 0
  while (index < length) {
    val character = this[index]
    if (character.isHighSurrogate()) {
      if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return true
      index += 2
      continue
    }
    if (character.isLowSurrogate()) return true
    index += 1
  }
  return false
}

/**
 * The UTF-8 byte length of [this], counted rather than materialised.
 *
 * `encodeToByteArray()` would allocate a copy of a string that can be 1500 bytes long on a path
 * taken once per check, and it substitutes U+FFFD for an unpaired surrogate — which would make a
 * malformed key measure three bytes where it encodes none. Surrogates are rejected separately by
 * [hasUnpairedSurrogate]; a well-formed pair counts as the four bytes it really is.
 */
private fun String.utf8Size(): Int {
  var size = 0
  var index = 0
  while (index < length) {
    val code = this[index].code
    when {
      code < 0x80 -> size += 1
      code < 0x800 -> size += 2
      this[index].isHighSurrogate() -> {
        size += 4
        index += 1
      }
      else -> size += 3
    }
    index += 1
  }
  return size
}
