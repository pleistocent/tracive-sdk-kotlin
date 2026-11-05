package id.tracive.sdk.platform

import id.tracive.sdk.model.ReportTarget

/**
 * The platform half of `TraciveClient.openReportForm`: find out what the installed Tracive app
 * accepts, then hand it the report package or the identifier-only link.
 *
 * The client validates and builds the package, picks the [ReportTarget], and opens the install page
 * through the [AuthorizeLauncher] itself. Pass your own implementation to `openReportForm` to drive
 * the call without a device.
 *
 * Every method is `suspend` for the same reason [AuthorizeLauncher]'s are: the iOS implementation
 * has to hop to the main thread, and on iOS a call also waits for the user's choice.
 */
interface ReportFormLauncher {

  /**
   * Says how [target] can take a report on this device. Called once per call, before anything is
   * opened, so it is also where an implementation deletes what an earlier call left on disk.
   */
  suspend fun prepare(target: ReportTarget): ReportHandoff

  /**
   * Hands [reportPackage] (UTF-8 JSON) to [target].
   *
   * @return true once [target] has received it, false when the user sent it elsewhere or nowhere.
   * @throws Exception when the platform refuses; the client reports it as
   *   `ReportFormError.PlatformFailure`.
   */
  suspend fun deliverPackage(target: ReportTarget, reportPackage: ByteArray): Boolean

  /**
   * Opens [url], the identifier-only link, in [target].
   *
   * @return true once it is open, false when the user declined to open the app.
   * @throws Exception when the platform refuses.
   */
  suspend fun openLink(target: ReportTarget, url: String): Boolean
}

/** What the installed Tracive build accepts, as [ReportFormLauncher.prepare] found it. */
enum class ReportHandoff {
  /** The whole package: identifier, date and attachments. */
  PACKAGE,

  /** Only the identifier, through the app's URL scheme. An app older than the package handoff. */
  LINK,

  /** Nothing: the app is not installed, or this app cannot see it. */
  APP_NOT_INSTALLED,
}

/**
 * The platform's own launcher: an explicit intent through the SDK's `FileProvider` on Android, the
 * "Open in" sheet presented from the key window's top view controller on iOS.
 */
expect fun defaultReportFormLauncher(): ReportFormLauncher
