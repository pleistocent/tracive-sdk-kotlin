package id.tracive.sdk.model

/**
 * Whether this client can keep a secret, which decides how far the SDK carries the OAuth flow.
 *
 * Note what is *not* here: there is no API-key option, in either case, because there is no API-key
 * parameter anywhere in the SDK's public surface. That is a hard design invariant — a key shipped
 * inside a mobile binary is a key that has been published.
 */
enum class ClientType {
  /**
   * A mobile or desktop app, which cannot hold a client secret.
   *
   * The SDK runs the whole PKCE flow itself: it generates the verifier, exchanges the authorization
   * code at `POST /oauth/token`, and stores the tokens in the Keystore or Keychain. Authorising
   * ends in `AuthResult.Authorized`.
   */
  PUBLIC,

  /**
   * A client whose token exchange happens on the partner's own backend, which holds the secret.
   *
   * The SDK stops at the authorization code and hands it back in `AuthResult.CodeReceived`,
   * together with the PKCE verifier and the redirect URI. It performs no exchange and stores no
   * tokens; the host app forwards those three values to its backend, which completes the exchange
   * with its `client_secret`.
   */
  CONFIDENTIAL,
}
