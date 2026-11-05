package id.tracive.sdk.model

import id.tracive.sdk.platform.AppPresenceChecker

/**
 * Which Tracive build [id.tracive.sdk.client.TraciveClient.openReportForm] hands the report to.
 *
 * Normally chosen for you by [forEnvironment]: [Sandbox] for [TraciveEnvironment.Sandbox], and
 * [Production] for everything else, including a [TraciveEnvironment.Custom] base URL, which names a
 * server and says nothing about which app is on the device.
 *
 * Construct one yourself only for testing against a build the SDK does not know about. On Android
 * that build also needs a `<queries><package>` entry in your own manifest, because the SDK's
 * manifest only makes the production and sandbox builds visible; on iOS both of its schemes need
 * `LSApplicationQueriesSchemes` entries.
 *
 * Each environment has its own iOS file extension, so a device holding both the sandbox and the
 * production app never offers a sandbox package to the production app.
 *
 * @param applicationId the Android application id, which is also the iOS bundle id.
 * @param urlScheme the app's URL scheme, used for the identifier-only link an older app accepts.
 * @param capabilityScheme the iOS URL scheme an app registers once it accepts the report package.
 * @param fileExtension the iOS file extension the app claims for the report package, without a dot.
 * @throws IllegalArgumentException if a value is blank or not shaped like what it names.
 */
class ReportTarget(
  val applicationId: String,
  val urlScheme: String,
  val capabilityScheme: String,
  val fileExtension: String,
) {

  init {
    require(applicationId.isNotBlank()) { "A ReportTarget needs an applicationId." }
    require(SCHEME.matches(urlScheme)) { "A ReportTarget's urlScheme must be a bare URL scheme." }
    require(SCHEME.matches(capabilityScheme)) {
      "A ReportTarget's capabilityScheme must be a bare URL scheme."
    }
    require(EXTENSION.matches(fileExtension)) {
      "A ReportTarget's fileExtension must be letters and digits, without a dot."
    }
  }

  override fun equals(other: Any?): Boolean =
    this === other ||
      (other is ReportTarget &&
        other.applicationId == applicationId &&
        other.urlScheme == urlScheme &&
        other.capabilityScheme == capabilityScheme &&
        other.fileExtension == fileExtension)

  override fun hashCode(): Int =
    listOf(applicationId, urlScheme, capabilityScheme, fileExtension).hashCode()

  override fun toString(): String = "ReportTarget($applicationId)"

  companion object {
    /** The MIME type of the report package on Android, the same for every build. */
    const val ANDROID_MIME_TYPE: String = "application/vnd.tracive.report+json"

    // Above Production and Sandbox: their init blocks read these, in declaration order.
    private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*")
    private val EXTENSION = Regex("[A-Za-z0-9]+")

    /** The production Tracive app. */
    val Production: ReportTarget =
      ReportTarget(
        applicationId = AppPresenceChecker.ANDROID_PACKAGE,
        urlScheme = AppPresenceChecker.IOS_URL_SCHEME,
        capabilityScheme = "tracive-report",
        fileExtension = "tracivereport",
      )

    /** The sandbox Tracive app. */
    val Sandbox: ReportTarget =
      ReportTarget(
        applicationId = AppPresenceChecker.ANDROID_SANDBOX_PACKAGE,
        urlScheme = AppPresenceChecker.IOS_SANDBOX_URL_SCHEME,
        capabilityScheme = "tracivesandbox-report",
        fileExtension = "tracivesandboxreport",
      )

    /** The build that claims [environment], as [AppPresenceChecker] picks it. */
    fun forEnvironment(environment: TraciveEnvironment): ReportTarget =
      if (environment == TraciveEnvironment.Sandbox) Sandbox else Production
  }
}
