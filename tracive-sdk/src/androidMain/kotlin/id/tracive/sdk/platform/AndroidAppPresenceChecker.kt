package id.tracive.sdk.platform

import android.content.Context
import android.content.pm.PackageManager
import id.tracive.sdk.TraciveAndroid
import id.tracive.sdk.model.TraciveEnvironment

/**
 * Package-visibility check for the Tracive app.
 *
 * On API 30 and above an app cannot see another package unless it declares it. The SDK's own
 * `AndroidManifest.xml` carries a `<queries><intent>` for each environment's authorize App Link,
 * and the manifest merger folds those into the host app's manifest — so an integrator gets the
 * declaration by depending on the AAR, with nothing to remember, for sandbox as well as production.
 * Without it `getPackageInfo` throws `NameNotFoundException` even when the app is installed, and
 * the SDK would send every user to the install page.
 *
 * **Visibility is granted by App Link, not by application id.** An `<intent>` query matches the
 * intent filters of other apps, so declaring the authorize URL reaches whichever build claims that
 * host and nothing else. That is what keeps a Tracive application id out of every partner's shipped
 * manifest — see CONTRIBUTING.md — while still letting this class name the exact package it wants.
 *
 * @param packageName which build to look for. Normally
 *   [AppPresenceChecker.androidPackageFor], i.e. selected by environment. Pass an explicit id only
 *   for a build the SDK does not know about; that one needs a `<queries>` entry in the host's own
 *   manifest, because the App Link queries above will not reach it.
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

/** The build claiming [environment]'s authorize App Link, via the host app's package manager. */
actual fun defaultAppPresenceChecker(environment: TraciveEnvironment): AppPresenceChecker =
  AndroidAppPresenceChecker(
    TraciveAndroid.requireContext(),
    AppPresenceChecker.androidPackageFor(environment),
  )
