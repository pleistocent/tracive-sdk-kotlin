package id.tracive.sdk.extended.sample

import android.app.Application
import id.tracive.sdk.client.TraciveClient
import id.tracive.sdk.extended.TraciveReportScopes
import id.tracive.sdk.extended.TraciveReports
import id.tracive.sdk.extended.ui.TraciveReportScreens
import id.tracive.sdk.model.ClientType
import id.tracive.sdk.model.TraciveMode

/**
 * One [TraciveClient] and one [TraciveReports] per process.
 *
 * The screens are configured here rather than in an Activity because Android can restore a screen
 * into a new process, and this runs first. Without it the restored screen closes as cancelled.
 */
class SampleApp : Application() {

  lateinit var client: TraciveClient
    private set

  lateinit var reports: TraciveReports
    private set

  override fun onCreate() {
    super.onCreate()
    client =
      TraciveClient(
        mode =
          TraciveMode.OAuth(
            clientId = BuildConfig.TRACIVE_CLIENT_ID,
            redirectUri = BuildConfig.TRACIVE_REDIRECT_URI,
            scopes = listOf(TraciveReportScopes.CREATE, TraciveReportScopes.READ),
            // TODO(partner): your own stable id for this user, never a phone number or an email.
            partnerUserId = "sample-user-1",
            clientType = ClientType.PUBLIC,
          ),
        environment = SampleEnvironment.environment,
        appPresenceChecker = SampleEnvironment.appPresenceChecker(this),
        authorizeEndpoint = BuildConfig.TRACIVE_AUTHORIZE_ENDPOINT,
      )
    reports = TraciveReports(client, SampleEnvironment.reportTarget)
    TraciveReportScreens.configure(reports)
  }
}
