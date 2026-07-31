package id.tracive.sdk.platform

/**
 * Opens the two URLs the OAuth flow needs: the authorize page, and the install page.
 *
 * **Never a WebView.** Both platform implementations use the system browser — Custom Tabs on
 * Android, `UIApplication.open` on iOS — and that is not a preference:
 * - An App Link / Universal Link does not fire from inside an in-app WebView, so the redirect back
 *   to the partner app would never be delivered and the flow would hang on the callback.
 * - The user cannot see the address bar in a WebView, so they cannot tell a real Tracive consent
 *   screen from one the host app drew. RFC 8252 §8.12 forbids embedded user-agents for exactly this
 *   reason.
 * - The host app can read a WebView's cookies and DOM, which means it can read the session of the
 *   authorization server it is supposed to be a third party to.
 *
 * Both methods are fire-and-forget. The result of an authorization arrives later, through
 * `TraciveOAuth.handleCallback`.
 */
interface AuthorizeLauncher {

  /**
   * Opens the authorization URL in the system browser.
   *
   * @throws Exception if no browser is available. `TraciveOAuth` turns that into
   *   `AuthResult.Failed` rather than letting it escape as a raw platform exception.
   */
  fun launchAuthorize(url: String)

  /**
   * Opens the Tracive install page.
   *
   * The URL carries no OAuth parameters — see `TraciveOAuth.installUrl` for why — so there is
   * nothing sensitive in it and nothing to resume when the user comes back.
   */
  fun launchInstall(url: String)
}

/** The platform's own launcher: Custom Tabs on Android, `UIApplication.open` on iOS. */
expect fun defaultAuthorizeLauncher(): AuthorizeLauncher
