package id.tracive.sdk.model

import id.tracive.sdk.TraciveException

/**
 * How an authorization attempt ended.
 *
 * Sealed, so a `when` over it has to account for every outcome — including the two that are easy to
 * forget: the user said no, and the Tracive app is not installed.
 */
sealed class AuthResult {

  /**
   * Authorised, tokens exchanged and stored. The end state for a [ClientType.PUBLIC] client.
   *
   * @param grantedScopes what the user actually granted, which can be narrower than what was asked
   *   for. Check it before offering a feature that needs a particular scope, rather than
   *   discovering the gap as a `TraciveException.MissingScope` mid-flow.
   */
  data class Authorized(val grantedScopes: List<String>) : AuthResult()

  /**
   * The authorization code, for a [ClientType.CONFIDENTIAL] client. No exchange was performed and
   * no tokens were stored.
   *
   * Forward all three values to the partner's own backend, which completes the exchange with its
   * `client_secret`. Send them over TLS to that backend and nowhere else: [code] is single-use but
   * still a bearer credential until it is redeemed, and [codeVerifier] is what stops an attacker
   * who intercepted the code from redeeming it.
   */
  class CodeReceived(val code: String, val codeVerifier: String, val redirectUri: String) :
    AuthResult() {

    /** Redacted: [code] and [codeVerifier] are credentials and must not reach a log. */
    override fun toString(): String = "CodeReceived(redirectUri=$redirectUri, code=redacted)"
  }

  /** The user declined. Not an error — do not retry automatically. */
  object Denied : AuthResult() {
    override fun toString(): String = "Denied"
  }

  /**
   * The Tracive app is not installed, so the flow never started.
   *
   * The SDK has opened the plain install link — no OAuth parameters on it, since they would be
   * meaningless by the time the user came back. There is no deferred resume: the user installs
   * Tracive, returns to this app, and tries again.
   */
  object AppNotInstalled : AuthResult() {
    override fun toString(): String = "AppNotInstalled"
  }

  /**
   * The flow failed.
   *
   * [error] is `TraciveException.InvalidRequest` for a `state` mismatch — which means the callback
   * did not come from the request this SDK started, so the code in it must not be redeemed.
   */
  data class Failed(val error: TraciveException) : AuthResult()
}
