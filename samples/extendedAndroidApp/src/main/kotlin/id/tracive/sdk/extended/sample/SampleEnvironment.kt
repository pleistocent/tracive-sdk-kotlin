package id.tracive.sdk.extended.sample

import android.content.Context
import id.tracive.sdk.model.ReportTarget
import id.tracive.sdk.model.TraciveEnvironment
import id.tracive.sdk.platform.AndroidAppPresenceChecker
import id.tracive.sdk.platform.AppPresenceChecker

/** Sandbox, unless `tracive.sample.*` in `local.properties` points this sample somewhere else. */
internal object SampleEnvironment {
  val environment: TraciveEnvironment =
    BuildConfig.TRACIVE_BASE_URL?.let { TraciveEnvironment.Custom(it) }
      ?: TraciveEnvironment.Sandbox

  // Every Tracive build names its report scheme and file extension after its URL scheme.
  val reportTarget: ReportTarget? =
    BuildConfig.TRACIVE_APP_ID?.let { applicationId ->
      val scheme = BuildConfig.TRACIVE_APP_SCHEME
      ReportTarget(applicationId, scheme, "$scheme-report", "${scheme}report")
    }

  fun appPresenceChecker(context: Context): AppPresenceChecker? =
    BuildConfig.TRACIVE_APP_ID?.let { AndroidAppPresenceChecker(context, it) }
}
