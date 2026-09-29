package id.tracive.sdk.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Tracive API's table from a national bank code or a BIC to Tracive's own bank code, per
 * country: [BANK_CODES_JSON], a verbatim copy of the server's, so a partner's code resolves here to
 * the bank code the server resolves it to.
 *
 * Parsed into hash maps on the first lookup, never at start-up.
 */
internal object BankCodes {

  private class CountryCodes(
    val shortCodes: Map<String, String>,
    val bareShortCodes: Map<String, String>,
    val bics: Map<String, String>,
  )

  private val countries: Map<String, CountryCodes> by lazy { parse(BANK_CODES_JSON) }

  /** Tracive's bank code for [raw], a national bank code, or null when [country] lists none. */
  fun byShortCode(country: String, raw: String): String? {
    val codes = countries[country] ?: return null
    val code = foldBankShortCode(raw)
    val exact = codes.shortCodes[code]
    if (exact != null) return exact
    // National codes are fixed-width ("014"), but a partner that stores one as a number sends "14".
    return if (code.isAsciiDigits()) codes.bareShortCodes[code.withoutLeadingZeros()] else null
  }

  /**
   * Tracive's bank code for [raw], an 8- or 11-character BIC, or null when [country] lists none.
   */
  fun byBic(country: String, raw: String): String? {
    val institution = foldBic(raw) ?: return null
    return countries[country]?.bics?.get(institution)
  }

  private fun parse(json: String): Map<String, CountryCodes> =
    Json.parseToJsonElement(json).jsonObject.getValue("countries").jsonObject.mapValues { country ->
      val table = country.value.jsonObject
      val shortCodes =
        table.getValue("shortCode").jsonObject.mapValues { it.value.jsonPrimitive.content }
      CountryCodes(
        shortCodes = shortCodes,
        bareShortCodes =
          shortCodes.filterKeys { it.isAsciiDigits() }.mapKeys { it.key.withoutLeadingZeros() },
        bics = table.getValue("bic").jsonObject.mapValues { it.value.jsonPrimitive.content },
      )
    }
}

/**
 * JavaScript's `/^\d+$/`: ASCII digits only, where `Char.isDigit()` also takes Arabic-Indic ones.
 */
private fun String.isAsciiDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }

/** The server's `^0+(?=\d)` on an all-digit code: `014` is `14`, and `000` is `0`, not empty. */
private fun String.withoutLeadingZeros(): String = trimStart('0').ifEmpty { "0" }
