package id.tracive.sdk.query

import id.tracive.sdk.TraciveQueryException

/**
 * One identifier to check, already folded into the exact key the backend indexes under.
 *
 * Build one with a factory below; there is no public constructor, so a `TraciveQuery` that exists
 * is a query that has already passed validation. Every factory throws [TraciveQueryException] on
 * input that cannot produce a usable key, and it throws *before* any network call — the server
 * bills a miss, so a key that can never hit must never be sent.
 *
 * ```
 * val query = TraciveQuery.phone("+62 812 3456 7891")
 * // query.searchQueryIndex == "PHONE_+6281234567891"
 * ```
 *
 * The folding rules and the full vector set live in `query-grammar-vectors.json`, vendored verbatim
 * into this repo and into the Flutter and Swift SDKs. `QueryFolding.kt` documents what each rule
 * does and why.
 *
 * **There is deliberately no `domain()` factory.** The Tracive API indexes only PHONE, EMAIL, BANK,
 * CRYPTO and USERNAME, and no record with a `DOMAIN_` id is ever written. A `domain()` factory
 * would therefore build keys that can never hit — and every one of those misses would be billed.
 */
class TraciveQuery
private constructor(
  /**
   * The folded key. This is what goes on the wire as the request's `query` field, and it is also
   * what the local cache hashes to form its key, so the two can never disagree.
   *
   * It contains the caller's identifier in cleartext. Never log it, and never store it: the cache
   * stores `SHA-256(searchQueryIndex)` precisely so the raw identifier is not kept at rest.
   */
  val searchQueryIndex: String
) {

  override fun equals(other: Any?): Boolean =
    this === other || (other is TraciveQuery && other.searchQueryIndex == searchQueryIndex)

  override fun hashCode(): Int = searchQueryIndex.hashCode()

  /**
   * Deliberately redacted: it names the identifier's type but never its value.
   *
   * [searchQueryIndex] embeds a phone number, email address, bank account or wallet address, and
   * `toString` output ends up in log lines, crash reports and IDE watch windows. This class is not
   * a `data class` for the same reason.
   */
  override fun toString(): String {
    val separator = searchQueryIndex.indexOf('_')
    val kind = if (separator <= 0) "UNKNOWN" else searchQueryIndex.substring(0, separator)
    return "TraciveQuery($kind, redacted)"
  }

  companion object {
    /**
     * A phone number in E.164, with or without formatting punctuation.
     *
     * `+62-812-3456-7891`, `+62 812 3456 7891` and `+6281234567891` all fold to the same key.
     *
     * @throws TraciveQueryException [TraciveQueryException.NOT_E164] if the number is not E.164
     *   once punctuation is stripped. A local format such as `081234567891` is rejected rather than
     *   guessed at, because the SDK does not know the caller's dial code and a wrong guess still
     *   costs a credit.
     */
    fun phone(e164: String): TraciveQuery = TraciveQuery(foldPhoneKey(e164))

    /**
     * An email address. Case and Unicode composition are folded, so `Budi@Gmail.com` and
     * `budi@gmail.com` are one entity.
     *
     * @throws TraciveQueryException [TraciveQueryException.EMPTY_VALUE] if the address is blank.
     */
    fun email(value: String): TraciveQuery = TraciveQuery(foldEmailKey(value))

    /**
     * A domestic bank account: an ISO 3166-1 alpha-3 country, a bank code, and an account number.
     *
     * Dashes and spaces in the account number are presentational and are removed, so
     * `3242-4242-422` and `32424242422` are one account.
     *
     * @param countryAlpha3 must be alpha-3 (`IDN`, not `ID`). The server would up-convert an
     *   alpha-2 code, but then the local cache key would no longer match the server's.
     * @throws TraciveQueryException [TraciveQueryException.COUNTRY_NOT_ALPHA3],
     *   [TraciveQueryException.EMPTY_BANK_CODE] or [TraciveQueryException.EMPTY_ACCOUNT_ID].
     */
    fun bankAccount(countryAlpha3: String, bankCode: String, account: String): TraciveQuery =
      TraciveQuery(foldBankAccountKey(countryAlpha3, bankCode, account))

    /**
     * An IBAN, in either printed groups of four or unbroken.
     *
     * @throws TraciveQueryException [TraciveQueryException.EMPTY_ACCOUNT_ID] if it is blank.
     */
    fun iban(iban: String): TraciveQuery = TraciveQuery(foldIbanKey(iban))

    /**
     * A crypto wallet address on a named chain.
     *
     * EVM addresses fold to lower case because EIP-55 checksum casing is presentational. base58 and
     * bech32 addresses are left exactly as given — base58 is case-sensitive, and lower-casing one
     * produces a different, invalid address.
     *
     * @throws TraciveQueryException [TraciveQueryException.EMPTY_CHAIN] or
     *   [TraciveQueryException.EMPTY_VALUE].
     */
    fun crypto(chain: String, address: String): TraciveQuery =
      TraciveQuery(foldCryptoKey(chain, address))

    /**
     * A social-media handle on a named platform.
     *
     * A leading `@` is kept, because the backend keeps it: `@scammerx` and `scammerx` are separate
     * entities there, and stripping it here would miss every report filed with it.
     *
     * @throws TraciveQueryException [TraciveQueryException.EMPTY_HANDLE] or
     *   [TraciveQueryException.EMPTY_VALUE].
     */
    fun username(platform: String, handle: String): TraciveQuery =
      TraciveQuery(foldUsernameKey(platform, handle))
  }
}
