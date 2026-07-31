package id.tracive.sdk.client

import id.tracive.sdk.model.Detail
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The body of `POST /v1/scammer-checks`.
 *
 * No field has a default value, deliberately. `kotlinx.serialization` omits a property that still
 * holds its default unless `encodeDefaults` is on, so a `creditSource` defaulted to `"USER"` here
 * would silently vanish from the wire and the server would reject the request — or, worse, apply
 * its own default. The one field that *is* meant to disappear is [locale], and it does so by being
 * null under the SDK's `explicitNulls = false`.
 *
 * The request sends [query] — the pre-normalized escape hatch — rather than `input`. The SDK has
 * already folded the identifier into the exact key the backend indexes under, and that same folded
 * string is what the local cache hashes, so sending it keeps the two from ever disagreeing about
 * what was asked.
 */
@Serializable
internal class CheckRequestBody(
  /** Who pays. phase 1 always bills the authorised user's own credits. */
  @SerialName("creditSource") val creditSource: String,

  /** How much to return. */
  @SerialName("detail") val detail: Detail,

  /**
   * Language for narrative text. Null — and therefore absent — for `detail = minimal`, where the
   * server ignores it and sending it would only add a field that never affects the answer.
   */
  @SerialName("locale") val locale: String?,

  /** The folded `searchQueryIndex`. Contains the caller's identifier: never log this body. */
  @SerialName("query") val query: String,
) {

  /** Redacted: [query] carries a phone number, an email address or an account number. */
  override fun toString(): String =
    "CheckRequestBody(creditSource=$creditSource, detail=$detail, locale=$locale, query=redacted)"

  companion object {
    /** The only credit source phase 1 supports. `PARTNER` and `AUTO` arrive with Proxy mode. */
    const val CREDIT_SOURCE_USER: String = "USER"
  }
}
