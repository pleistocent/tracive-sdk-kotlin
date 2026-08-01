package id.tracive.sdk.model

/**
 * The OAuth scopes the authorization server recognises, as their exact wire strings.
 *
 * Prefer these over string literals. A scope name is only ever validated by the authorization
 * server, and it rejects the whole consent request when one is unknown — so a typo does not degrade
 * gracefully into a narrower grant, it stops the integration at its first authorization and the
 * partner sees a generic failure with nothing local to debug.
 *
 * The names are dot-separated (`resource.action`), and the resource is the thing being acted on,
 * not the endpoint's path segment. Ask for the narrowest set that works: an unused scope on the
 * consent screen is one more permission the user has to accept.
 */
object TraciveScopes {

  /** Run a scammer check. Required by `TraciveClient.checkScammer`. */
  const val SCAMMER_CHECK: String = "scammer.check"

  /** Read the user's remaining subscription credits. Required by `TraciveClient.credits`. */
  const val CREDITS_READ: String = "credits.read"

  /**
   * Read the minimal user profile. Required by `TraciveClient.profile`.
   *
   * Which fields of [ProfileInfo] actually come back still depends on what the user granted, so a
   * null field means "not shared" rather than "not set".
   */
  const val PROFILE_READ: String = "profile.read"
}
