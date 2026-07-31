package id.tracive.sdk.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import id.tracive.sdk.TraciveAndroid

/**
 * Opens URLs in the system browser through Custom Tabs.
 *
 * **Never a WebView**, and this is not a style preference:
 * - An App Link does not fire from inside an in-app WebView, so the redirect back to the partner
 *   app would never be delivered — the user would sit on a fallback page with Tracive installed.
 * - A WebView has no address bar, so the user cannot tell a real consent screen from one the host
 *   app drew. RFC 8252 §8.12 forbids embedded user-agents for exactly this reason.
 * - The host app can read a WebView's cookies and DOM, i.e. the session of the authorization server
 *   it is supposed to be a third party to.
 *
 * Custom Tabs keeps the URL bar and the browser's own cookie jar while still looking in-app. Where
 * no Custom Tabs provider exists, this falls back to a plain `ACTION_VIEW`, which is a full browser
 * — still the system's, still outside this app's reach.
 *
 * @param context normally the application context, which is why every intent carries
 *   `FLAG_ACTIVITY_NEW_TASK`. Pass an Activity when one is available and the tab should sit in the
 *   app's own task.
 */
class AndroidAuthorizeLauncher(private val context: Context) : AuthorizeLauncher {

  override fun launchAuthorize(url: String) {
    open(url)
  }

  override fun launchInstall(url: String) {
    open(url)
  }

  private fun open(url: String) {
    val uri = Uri.parse(url)
    try {
      val customTabs = CustomTabsIntent.Builder().setShowTitle(true).build()
      customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      customTabs.launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
      val fallback = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      // Deliberately not caught: with no browser at all there is nothing sensible to do, and
      // TraciveOAuth turns the failure into AuthResult.Failed rather than letting it escape.
      context.startActivity(fallback)
    }
  }

  override fun toString(): String = "AndroidAuthorizeLauncher"
}

/** Custom Tabs, on the host app's application context. */
actual fun defaultAuthorizeLauncher(): AuthorizeLauncher =
  AndroidAuthorizeLauncher(TraciveAndroid.requireContext())
