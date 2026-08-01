package id.tracive.sdk.platform

import android.content.Context
import android.content.pm.PackageManager
import id.tracive.sdk.TraciveAndroid

/**
 * Package-visibility check for the Tracive app.
 *
 * On API 30 and above an app cannot see another package unless it declares it. The SDK's own
 * `AndroidManifest.xml` carries `<queries><package
 * android:name="com.pleistocent.tracive"/></queries>`, and the manifest merger folds that into the
 * host app's manifest — so an integrator gets the declaration by depending on the AAR, with nothing
 * to remember. Without it `getPackageInfo` throws `NameNotFoundException` even when the app is
 * installed, and the SDK would send every user to the install page.
 *
 * @param packageName which build to look for. The default is the production app; the sandbox build
 *   is `com.pleistocent.tracive.dev`, and a host testing against it constructs this directly and
 *   passes the result to `TraciveOAuth`.
 */
class AndroidAppPresenceChecker(
  private val context: Context,
  private val packageName: String = AppPresenceChecker.ANDROID_PACKAGE,
) : AppPresenceChecker {

  /**
   * Fails **closed**: any failure to answer reports "not installed".
   *
   * That is the safe direction. A false "not installed" sends the user to the install page, which
   * is recoverable and honest. A false "installed" opens an authorize URL nothing will claim, and
   * the user lands on a web page asking for Tracive credentials — which is the phishing habit this
   * whole flow exists to avoid.
   */
  @Suppress("DEPRECATION")
  override suspend fun isTraciveAppInstalled(): Boolean =
    try {
      // The PackageInfoFlags overload arrived in API 33; this one works on every supported level
      // and behaves identically for a zero flag set.
      context.packageManager.getPackageInfo(packageName, 0)
      true
    } catch (_: PackageManager.NameNotFoundException) {
      false
    } catch (_: Throwable) {
      false
    }

  override fun toString(): String = "AndroidAppPresenceChecker($packageName)"
}

/** The production Tracive app, looked up through the host app's package manager. */
actual fun defaultAppPresenceChecker(): AppPresenceChecker =
  AndroidAppPresenceChecker(TraciveAndroid.requireContext())
