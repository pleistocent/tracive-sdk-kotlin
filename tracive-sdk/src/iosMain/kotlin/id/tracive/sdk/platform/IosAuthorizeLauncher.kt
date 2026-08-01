package id.tracive.sdk.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * **This hops to the main queue itself.** `UIApplication` is main-thread-only, and the dispatcher
 * the flow runs on is the host's choice: `TraciveClient`'s own example suggests a ViewModel scope,
 * which on iOS through a KMP bridge is frequently not main. A KDoc line asking the caller to be on
 * the main thread is not a mechanism when the caller decides the thread. `withContext` rather than
 * `dispatch_async` so that a malformed URL still reaches the caller as an exception — and therefore
 * as `AuthResult.Failed` — instead of being raised on a queue nobody is watching.
 */
class IosAuthorizeLauncher : AuthorizeLauncher {

  override suspend fun launchAuthorize(url: String) {
    open(url)
  }

  override suspend fun launchInstall(url: String) {
    open(url)
  }

  private suspend fun open(url: String) =
    withContext(Dispatchers.Main) {
      val target =
        NSURL.URLWithString(url)
          ?: throw IllegalArgumentException("The Tracive SDK was asked to open a malformed URL.")
      UIApplication.sharedApplication.openURL(target, emptyMap<Any?, Any>(), null)
    }

  override fun toString(): String = "IosAuthorizeLauncher"
}

/** `UIApplication.open`, so a Universal Link reaches the installed Tracive app. */
actual fun defaultAuthorizeLauncher(): AuthorizeLauncher = IosAuthorizeLauncher()
