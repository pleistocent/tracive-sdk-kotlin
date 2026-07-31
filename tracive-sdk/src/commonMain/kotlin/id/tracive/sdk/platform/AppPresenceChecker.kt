package id.tracive.sdk.platform

/**
 * Answers one question before the OAuth flow starts: is the Tracive app on this device?
 *
 * The check has to happen *before* any redirect. Without the app installed there is nobody to
 * authorize the grant, so opening the authorize URL would drop the user on a page that cannot
 * finish — and, worse, an unclaimed App Link opens in a browser, where the user is asked to sign in
 * to Tracive on a web form. Teaching users to type Tracive credentials into whatever a partner app
 * opened is precisely the habit this flow exists to avoid.
 *
 * Both platform implementations need a manifest declaration to work at all, and both fail *closed*
 * (report "not installed") when it is missing:
 * - Android: package visibility. The SDK's own `AndroidManifest.xml` declares `<queries><package
 *   android:name="com.pleistocent.tracive"/></queries>`, which the manifest merger folds into the
 *   host app, so integrators get this for free. Without it `getPackageInfo` throws
 *   `NameNotFoundException` on API 30+ even when the app is installed.
 * - iOS: `canOpenURL` against the `tracive:` scheme, which requires `tracive` in the host app's
 *   `LSApplicationQueriesSchemes`. That one *cannot* be shipped from a library — an Info.plist is
 *   not merged — so the integration guide has to state it.
 */
interface AppPresenceChecker {

  /** Whether the Tracive app is installed and visible to this app. */
  fun isTraciveAppInstalled(): Boolean

  companion object {
    /** The Tracive app's Android application id. */
    const val ANDROID_PACKAGE: String = "com.pleistocent.tracive"

    /** The Tracive app's iOS URL scheme, which must be listed in `LSApplicationQueriesSchemes`. */
    const val IOS_URL_SCHEME: String = "tracive"
  }
}

/** The platform's own presence check. */
expect fun defaultAppPresenceChecker(): AppPresenceChecker
