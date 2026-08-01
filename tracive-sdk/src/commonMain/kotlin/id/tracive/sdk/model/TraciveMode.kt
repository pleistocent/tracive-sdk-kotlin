package id.tracive.sdk.model

/**
 * How the client authenticates.
 *
 * Neither case takes an API key, and neither ever will: there is no API-key parameter anywhere in
 * this SDK's public surface, in any mode. That is a hard design invariant, not an oversight.
 */
sealed class TraciveMode {

  /**
   * OAuth 2.0 with PKCE against the Tracive app — the only mode phase 1 implements.
   *
   * The user authorises in the Tracive app, and the partner app receives a token scoped to that
   * user. Checks are billed to the user's own credits.
   *
   * @param clientId the client id issued to the partner.
   * @param redirectUri the App Link / Universal Link registered for [clientId]. It must be claimed
   *   by the host app; a custom scheme can be hijacked by any other installed app.
   * @param scopes the scopes to request. Ask for the narrowest set that works — `Detail.FULL` needs
   *   a scope that most integrations do not.
   * @param partnerUserId the partner's own identifier for the end user, so a grant can be
   *   correlated with the partner's account. **Required.** The Tracive app rejects an authorization
   *   request without it, client-side and before any network call, which produces no redirect at
   *   all — the partner app is handed nothing to handle and no server log records the attempt. It
   *   is also what the grant is keyed on: it is echoed back in `grant.revoked` and
   *   `grant.superseded` webhooks so a partner can map the event to their own customer, and it is
   *   what enforces one partner account to one Tracive account. An earlier version of this
   *   documentation said to pass `null` when the integration has no user accounts; that was wrong,
   *   and following it produced a consent screen that refused with no way to report why. Send an
   *   opaque identifier — never a raw phone number or email.
   * @param clientType whether the SDK completes the token exchange itself; see [ClientType].
   */
  class OAuth(
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String>,
    val partnerUserId: String,
    val clientType: ClientType = ClientType.PUBLIC,
  ) : TraciveMode() {

    init {
      require(clientId.isNotBlank()) { "TraciveMode.OAuth needs a clientId." }
      require(redirectUri.isNotBlank()) { "TraciveMode.OAuth needs a redirectUri." }
      require(partnerUserId.isNotBlank()) {
        "TraciveMode.OAuth needs a partnerUserId. The authorization request must carry one — the " +
          "Tracive app refuses a consent without it, client-side and with no redirect, so the " +
          "failure would reach you as silence rather than as an error."
      }
    }

    override fun toString(): String =
      "OAuth(clientId=$clientId, redirectUri=$redirectUri, scopes=$scopes, clientType=$clientType)"
  }

  /**
   * Calls routed through a partner's own backend, which holds the credentials.
   *
   * **phase 2.** The type exists in the surface now so that adding the behaviour later is not a
   * breaking change; until then every call made in this mode throws
   * `TraciveException.NotImplemented`.
   *
   * @param baseUrl the partner proxy's origin. A trailing slash is trimmed.
   * @param authHeaders headers to attach to every proxied request. These carry credentials, so they
   *   are copied on construction and are never included in [toString].
   */
  class Proxy(baseUrl: String, authHeaders: Map<String, String>) : TraciveMode() {

    val baseUrl: String = baseUrl.trim().trimEnd('/')

    val authHeaders: Map<String, String> = authHeaders.toMap()

    init {
      require(this.baseUrl.isNotEmpty()) { "TraciveMode.Proxy needs a baseUrl." }
    }

    /** Header *names* are safe to show; their values are credentials and are never printed. */
    override fun toString(): String =
      "Proxy(baseUrl=$baseUrl, authHeaders=${authHeaders.keys.sorted()} redacted)"
  }
}
