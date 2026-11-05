package id.tracive.sdk.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import id.tracive.sdk.TraciveAndroid
import id.tracive.sdk.model.ReportTarget
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hands a report to the Tracive app with an explicit intent: `ACTION_VIEW` on a `content://` URI
 * from the SDK's own [TraciveFileProvider], typed [ReportTarget.ANDROID_MIME_TYPE] and addressed to
 * the target's package, so no other app can receive it and the user sees no chooser.
 *
 * The package is written to `cache/tracive-sdk/report/`, the only folder the provider serves, and
 * deleted on the next call.
 *
 * @param context normally the application context, which is why every intent then carries
 *   `FLAG_ACTIVITY_NEW_TASK`. Pass an Activity to open Tracive on top of your own task instead.
 */
class AndroidReportFormLauncher(private val context: Context) : ReportFormLauncher {

  /**
   * Visibility of the target package comes from the SDK manifest's App Link queries, or from the
   * host's own `<queries>` for a build the SDK does not know about.
   */
  @Suppress("DEPRECATION")
  override suspend fun prepare(target: ReportTarget): ReportHandoff {
    withContext(Dispatchers.IO) { packageDirectory().deleteRecursively() }
    if (!AndroidAppPresenceChecker(context, target.applicationId).isTraciveAppInstalled()) {
      return ReportHandoff.APP_NOT_INSTALLED
    }
    // Resolving needs a URI of the right shape, not a file: no filter matches on the path.
    val probe = packageIntent(Uri.parse("content://${authority()}/report/probe"), target)
    val accepts =
      context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
    return if (accepts.isNotEmpty()) ReportHandoff.PACKAGE else ReportHandoff.LINK
  }

  override suspend fun deliverPackage(target: ReportTarget, reportPackage: ByteArray): Boolean {
    val file =
      withContext(Dispatchers.IO) {
        val directory = packageDirectory().apply { mkdirs() }
        File(directory, "report.${target.fileExtension}").apply { writeBytes(reportPackage) }
      }
    val uri = FileProvider.getUriForFile(context, authority(), file)
    context.startActivity(packageIntent(uri, target))
    return true
  }

  override suspend fun openLink(target: ReportTarget, url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(target.applicationId)
    context.startActivity(intent.inHostTask())
    return true
  }

  private fun packageIntent(uri: Uri, target: ReportTarget): Intent =
    Intent(Intent.ACTION_VIEW)
      .setDataAndType(uri, ReportTarget.ANDROID_MIME_TYPE)
      .setPackage(target.applicationId)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      .inHostTask()

  private fun Intent.inHostTask(): Intent =
    if (context is Activity) this else addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  private fun packageDirectory(): File = File(context.cacheDir, ReportPackageFiles.DIRECTORY)

  private fun authority(): String = context.packageName + ReportPackageFiles.AUTHORITY_SUFFIX

  override fun toString(): String = "AndroidReportFormLauncher"
}

/** Where the report package lives, shared with the manifest and the paths XML. */
internal object ReportPackageFiles {
  /** Must match `cache-path` in `res/xml/tracive_sdk_report_paths.xml`. */
  const val DIRECTORY = "tracive-sdk/report"

  /** Must match the provider's authority in the SDK manifest, after `${applicationId}`. */
  const val AUTHORITY_SUFFIX = ".tracive-sdk-files"
}

/**
 * The SDK's own `FileProvider`, serving only the report package folder.
 *
 * A subclass, because a second plain `androidx.core.content.FileProvider` entry collides in the
 * manifest merger with the one most host apps already declare.
 */
internal class TraciveFileProvider : FileProvider()

/** An explicit intent on the host app's application context. */
actual fun defaultReportFormLauncher(): ReportFormLauncher =
  AndroidReportFormLauncher(TraciveAndroid.requireContext())
