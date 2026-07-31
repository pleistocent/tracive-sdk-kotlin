package id.tracive.sdk.platform

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * `canOpenURL` against the Tracive app's URL scheme.
 *
 * **The host app must declare the scheme in its `Info.plist`:**
 *
 * ```xml
 * <key>LSApplicationQueriesSchemes</key>
 * <array>
 *   <string>tracive</string>
 * </array>
 * ```
 *
 * Unlike Android's `<queries>`, this cannot ship from a library — `Info.plist` files are not merged
 * — so it belongs in the integration guide. Without it iOS answers `false` for every device,
 * installed or not, and every user is sent to the install page.
 *
 * Call from the main thread. `UIApplication` is main-thread-only, and this runs at the very start
 * of `authorize()`, which a host normally drives from a UI-bound coroutine.
 *
 * @param urlScheme which build to look for. The default is the production app; the sandbox build
 *   answers `tracivedev`.
 */
class IosAppPresenceChecker(private val urlScheme: String = AppPresenceChecker.IOS_URL_SCHEME) :
  AppPresenceChecker {

  /**
   * Fails **closed**: a malformed URL or any other failure reports "not installed".
   *
   * A false negative sends the user to the install page, which is recoverable. A false positive
   * opens a Universal Link nothing claims, and Safari shows a page asking for Tracive credentials —
   * training exactly the habit this app-to-app flow exists to prevent.
   */
  override fun isTraciveAppInstalled(): Boolean {
    val url = NSURL.URLWithString("$urlScheme://") ?: return false
    return UIApplication.sharedApplication.canOpenURL(url)
  }

  override fun toString(): String = "IosAppPresenceChecker($urlScheme)"
}

/** The production Tracive app, via `canOpenURL`. */
actual fun defaultAppPresenceChecker(): AppPresenceChecker = IosAppPresenceChecker()
