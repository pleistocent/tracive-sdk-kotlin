package id.tracive.sdk.model

/**
 * The server's rule for `partner_user_id` (backend `partnerUserIdProblem`), checked before anything
 * is opened. A value the server refuses is refused before a redirect exists, so the partner app
 * would get nothing back at all — here it fails loudly at construction instead.
 *
 * KEEP IN SYNC with the server and the other three SDKs.
 */
internal object PartnerUserIdRule {
  const val MAX_LENGTH: Int = 200

  private val separators = Regex("[\\s\\-().]")
  private val e164 = Regex("^\\+\\d{7,15}$")
  private val indonesianMobile = Regex("^(08|628)\\d{8,11}$")

  /** The reason [value] would be refused, or null when the server accepts it. */
  fun problem(value: String): String? {
    val v = value.trim()
    if (v.isEmpty()) return "is blank"
    if (v.length > MAX_LENGTH) return "is longer than $MAX_LENGTH characters"
    if ('@' in v) return "looks like an email address"
    val compact = separators.replace(v, "")
    if (e164.matches(compact) || indonesianMobile.matches(compact)) {
      return "looks like a phone number"
    }
    return null
  }
}
