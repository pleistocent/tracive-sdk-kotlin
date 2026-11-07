package id.tracive.sdk.extended

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.Serializable

@Serializable
internal class WireDevice(
  val installId: String,
  val osName: String,
  val osVersion: String,
  val manufacturer: String,
  val model: String,
)

internal class ReportDevice(val wire: WireDevice, val language: String)

/** The device, with an install id that lasts until the app is uninstalled. */
internal expect fun platformDevice(): ReportDevice

/** Hands [url] to the OS. False when nothing on the device can open it. */
internal expect fun openExternalUrl(url: String): Boolean

internal expect fun reportHttpEngine(): HttpClientEngineFactory<HttpClientEngineConfig>

internal fun reportHttpClient(): HttpClient =
  HttpClient(reportHttpEngine()) {
    expectSuccess = false
    // Cloud Storage answers 308 with no Location to say an upload is incomplete.
    followRedirects = false
    install(HttpTimeout) {
      connectTimeoutMillis = 15_000
      socketTimeoutMillis = 60_000
      requestTimeoutMillis = 120_000
    }
  }
