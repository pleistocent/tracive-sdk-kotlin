package id.tracive.sdk.platform

import id.tracive.sdk.model.TraciveEnvironment

/**
 * Answers one question before the OAuth flow starts: is the Tracive app on this device?
 *
 * The check has to happen *before* any redirect. Without the app installed there is nobody to
 * authorize the grant, so opening the authorize URL would drop the user on a page that cannot
 * finish — and, worse, an unclaimed App Link opens in a browser, where the user is asked to sign in
 * to Tracive on a web form. Teaching users to type Tracive credentials into whatever a partner app
 * opened is precisely the habit this flow exists to avoid.
 *
 * The target follows [TraciveEnvironment], so a partner testing against sandbox needs no extra
 * wiring: [androidPackageFor] and [iosUrlSchemeFor] pick the build that claims that environment's
 * authorize link. A [TraciveEnvironment.Custom] base URL is a partner proxy or a local server and
 * says nothing about which app is on the device, so it maps to production.
 *
 * Both platform implementations need a manifest declaration to work at all, and both fail *closed*
 * (report "not installed") when it is missing:
 * - Android: package visibility. The SDK's own `AndroidManifest.xml` declares a `<queries><intent>`
 *   for each environment's authorize App Link, which the manifest merger folds into the host app,
 *   so integrators get this for free. It grants visibility to whichever build claims that host —
 *   which is why no application id appears in a partner's manifest. Without it `getPackageInfo`
 *   throws `NameNotFoundException` on API 30+ even when the app is installed.
 * - iOS: `canOpenURL` against the app's URL scheme, which requires that scheme in the host app's
 *   `LSApplicationQueriesSchemes`. That one *cannot* be shipped from a library — an Info.plist is
 *   not merged — so the integration guide has to state it, sandbox scheme included.
 */
interface AppPresenceChecker {

  /**
   * Whether the Tracive app is installed and visible to this app.
   *
   * `suspend` because of iOS: `UIApplication.canOpenURL` is main-thread-only, and the dispatcher
   * `authorize()` runs on is the host's choice — a ViewModel scope reached through a KMP bridge is
   * frequently not main. The iOS implementation therefore hops to the main queue itself, which it
   * can only do from a suspending function. Android has no such constraint but shares the
   * signature.
   */
  suspend fun isTraciveAppInstalled(): Boolean

  companion object {
    /** The production Tracive app's Android application id. */
    const val ANDROID_PACKAGE: String = "com.pleistocent.tracive"

    /**
     * The sandbox Tracive app's Android application id.
     *
     * Naming it here discloses nothing: the sandbox authorize host publishes this id and its
     * signing fingerprints at `/.well-known/assetlinks.json`, because that is what an App Link
     * association is. What may not appear in a partner's *manifest* is a different rule, and the
     * `<queries><intent>` approach is what keeps it out of one — see CONTRIBUTING.md.
     */
    const val ANDROID_SANDBOX_PACKAGE: String = "com.pleistocent.tracive.dev"

    /**
     * The production Tracive app's iOS URL scheme, which must be listed in
     * `LSApplicationQueriesSchemes`.
     */
    const val IOS_URL_SCHEME: String = "tracive"

    /**
     * The sandbox Tracive app's iOS URL scheme. Also needs an `LSApplicationQueriesSchemes` entry.
     */
    const val IOS_SANDBOX_URL_SCHEME: String = "tracivedev"

    /** Which Android build claims [environment]'s authorize App Link. */
    fun androidPackageFor(environment: TraciveEnvironment): String =
      if (environment == TraciveEnvironment.Sandbox) ANDROID_SANDBOX_PACKAGE else ANDROID_PACKAGE

    /** Which iOS build claims [environment]'s Universal Link. */
    fun iosUrlSchemeFor(environment: TraciveEnvironment): String =
      if (environment == TraciveEnvironment.Sandbox) IOS_SANDBOX_URL_SCHEME else IOS_URL_SCHEME
  }
}

/**
 * The platform's own presence check, aimed at the build that claims [environment]'s authorize link.
 */
expect fun defaultAppPresenceChecker(environment: TraciveEnvironment): AppPresenceChecker
