package id.tracive.sdk.client

import id.tracive.sdk.model.ReportTarget
import id.tracive.sdk.model.TraciveEnvironment
import id.tracive.sdk.model.TraciveMode
import id.tracive.sdk.oauth.InMemoryTokenStore
import id.tracive.sdk.platform.ReportFormLauncher
import id.tracive.sdk.platform.ReportHandoff
import kotlin.test.fail
import kotlinx.datetime.Clock

/** Answers [prepare] with a fixed [handoff] and records what would have been opened. */
internal class RecordingReportFormLauncher(
  private val handoff: ReportHandoff = ReportHandoff.PACKAGE,
  private val delivers: Boolean = true,
  private val opensLink: Boolean = true,
  private val failure: Throwable? = null,
) : ReportFormLauncher {

  val prepared: MutableList<ReportTarget> = mutableListOf()
  val packages: MutableList<ByteArray> = mutableListOf()
  val links: MutableList<String> = mutableListOf()

  override suspend fun prepare(target: ReportTarget): ReportHandoff {
    prepared += target
    failure?.let { throw it }
    return handoff
  }

  override suspend fun deliverPackage(target: ReportTarget, reportPackage: ByteArray): Boolean {
    packages += reportPackage
    return delivers
  }

  override suspend fun openLink(target: ReportTarget, url: String): Boolean {
    links += url
    return opensLink
  }
}

/**
 * A client for `openReportForm`: no grant in its store, and a transport that fails the test if
 * anything is sent, because opening the form must reach neither.
 */
internal fun reportClient(
  environment: TraciveEnvironment = TraciveEnvironment.Production,
  mode: TraciveMode = testOAuthMode(),
  installLauncher: RecordingAuthorizeLauncher = RecordingAuthorizeLauncher(),
  installUrl: String? = null,
  clock: Clock = FixedClock(),
  transport: FakeHttpTransport = FakeHttpTransport { fail("openReportForm sent a request") },
): TraciveClient =
  TraciveClient(
    mode = mode,
    environment = environment,
    transport = transport,
    clock = clock,
    tokenStore = InMemoryTokenStore(),
    authorizeLauncher = installLauncher,
    installUrl = installUrl,
  )
