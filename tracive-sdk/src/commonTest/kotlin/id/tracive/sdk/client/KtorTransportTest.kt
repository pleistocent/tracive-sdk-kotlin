package id.tracive.sdk.client

import id.tracive.sdk.TraciveException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The default transport's timeouts.
 *
 * What is worth pinning is that the plugin is installed at all. Without it the numbers fall through
 * to whatever engine is on the classpath — OkHttp on Android, `NSURLSession` on Darwin — which do
 * not agree, so the same call would give up after different intervals on the two platforms with
 * neither number written down anywhere. No socket is opened here: building a client is not making a
 * request, and nothing in this suite ever makes one.
 */
class KtorTransportTest {

  @Test
  fun theDefaultClientCarriesExplicitTimeouts() {
    val client = defaultHttpClient()
    try {
      assertNotNull(
        client.pluginOrNull(HttpTimeout),
        "the default client has no HttpTimeout, so its timeouts are the engine's and differ per " +
          "platform",
      )
    } finally {
      client.close()
    }
  }

  @Test
  fun theTimeoutValuesAreOrdered() {
    // Connecting must not be allowed to consume the whole request budget, and a stalled socket must
    // be caught no later than the request as a whole.
    assertTrue(KtorTransport.CONNECT_TIMEOUT_MS < KtorTransport.REQUEST_TIMEOUT_MS)
    assertTrue(KtorTransport.SOCKET_TIMEOUT_MS <= KtorTransport.REQUEST_TIMEOUT_MS)
  }

  /**
   * A body larger than the cap is refused rather than materialised.
   *
   * `TraciveEnvironment.Custom` lets a host point this client at any origin, and phase 2 puts a
   * partner-operated proxy in the path — so "the origin is well behaved" is not an assumption this
   * layer gets to make. Before the cap, `bodyAsText()` would build a string of whatever size the
   * origin chose.
   */
  @Test
  fun aBodyOverTheCapIsRefusedRatherThanRead() = runTest {
    val oversized = ByteArray(KtorTransport.MAX_RESPONSE_BYTES + 1) { 'a'.code.toByte() }
    val engine = MockEngine { respond(oversized, HttpStatusCode.OK) }
    val transport = KtorTransport(HttpClient(engine))

    val failure =
      assertFailsWith<TraciveException.Network> {
        transport.execute(HttpRequest(HttpMethod.GET, "https://api.test.invalid/v1/credits"))
      }
    assertTrue(failure.message!!.contains("larger than"), failure.message!!)
    transport.close()
  }

  /** The control: a body the size of a real reply still arrives intact. */
  @Test
  fun aNormalBodyIsReadWhole() = runTest {
    val payload = "{\"ok\":true,\"filler\":\"" + "x".repeat(4096) + "\"}"
    val engine = MockEngine { respond(payload, HttpStatusCode.OK) }
    val transport = KtorTransport(HttpClient(engine))

    val response =
      transport.execute(HttpRequest(HttpMethod.GET, "https://api.test.invalid/v1/credits"))

    assertEquals(200, response.status)
    assertEquals(payload, response.body)
    transport.close()
  }
}
