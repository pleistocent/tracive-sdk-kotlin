package id.tracive.sdk.client

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
}
