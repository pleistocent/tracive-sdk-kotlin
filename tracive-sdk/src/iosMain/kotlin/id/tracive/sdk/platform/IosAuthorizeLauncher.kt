package id.tracive.sdk.platform

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * Opens URLs with `UIApplication.open`.
 *
 * That call hands the URL to the system, which is what makes the Universal Link resolve to the
 * installed Tracive app. The two alternatives are both wrong here:
 * - `WKWebView` / `SFSafariViewController` do not trigger a Universal Link to another app, so the
 *   authorize URL would render as a web page and the redirect back would never arrive.
 * - An embedded web view also hides the address bar, which RFC 8252 §8.12 forbids for OAuth
 *   precisely because the user then cannot tell a real consent screen from a drawn one.
 *
 * Fire-and-forget: the completion handler is ignored because the outcome of an authorization does
 * not arrive here — it arrives at the app's `onOpenURL`, which the host forwards to
 * `TraciveOAuth.handleCallback`.
 *
 * Call from the main thread; `UIApplication` is main-thread-only.
 */
class IosAuthorizeLauncher : AuthorizeLauncher {

  override fun launchAuthorize(url: String) {
    open(url)
  }

  override fun launchInstall(url: String) {
    open(url)
  }

  private fun open(url: String) {
    val target =
      NSURL.URLWithString(url)
        ?: throw IllegalArgumentException("The Tracive SDK was asked to open a malformed URL.")
    UIApplication.sharedApplication.openURL(target, emptyMap<Any?, Any>(), null)
  }

  override fun toString(): String = "IosAuthorizeLauncher"
}

/** `UIApplication.open`, so a Universal Link reaches the installed Tracive app. */
actual fun defaultAuthorizeLauncher(): AuthorizeLauncher = IosAuthorizeLauncher()
