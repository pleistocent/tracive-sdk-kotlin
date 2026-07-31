package id.tracive.sdk.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The authorised user, as the grant sees them. Returned by `GET /profile`.
 *
 * Only [sub] is guaranteed; the rest depend on the scopes the user granted, so treat a null as "not
 * shared" rather than "not set". This is the user's personal data — keep it in memory for the
 * screen that needs it rather than logging or persisting it.
 */
@Serializable
data class ProfileInfo(
  /**
   * The OAuth subject: a stable, opaque id for this user under this client.
   *
   * The right key to store if the partner needs to remember a user across sessions — it is not a
   * phone number or an email address, so storing it does not put an identifier at rest.
   */
  @SerialName("sub") val sub: String,

  /** Display name, when the granted scopes include it. */
  @SerialName("name") val name: String? = null,

  /** ISO 3166-1 alpha-3 country of the account, when shared. */
  @SerialName("country") val country: String? = null,

  /** Subscription tier. A `String` for the same reason as [CreditsInfo.tier]. */
  @SerialName("tier") val tier: String? = null,

  /** When the account was created, when shared. */
  @SerialName("memberSince") val memberSince: Instant? = null,
)
