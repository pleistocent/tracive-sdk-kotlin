package id.tracive.sdk.client

import id.tracive.sdk.TraciveException
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod as KtorHttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

/**
 * The default [HttpTransport], on Ktor.
 *
 * Deliberately thin: it moves bytes and normalises headers, and does not interpret status codes,
 * parse bodies or retry. All of that lives above the interface so it is testable without a socket.
 *
 * @param client the Ktor client to use. The default builds one from whatever engine is on the
 *   classpath, which is what the host app already has — `ktor-client-core` alone ships no engine,
 *   so an integration adds `ktor-client-okhttp` (Android) or `ktor-client-darwin` (iOS), or passes
 *   its own configured client here. Passing an existing client is usually the better choice: it
 *   shares the connection pool and any interceptors the app already has.
 * @param ownsClient whether [close] should also close [client]. False when the caller passed a
 *   shared client, so closing the SDK does not kill the host app's HTTP stack.
 */
class KtorTransport(
  private val client: HttpClient = HttpClient(),
  private val ownsClient: Boolean = true,
) : HttpTransport {

  override suspend fun execute(request: HttpRequest): HttpResponse {
    val response =
      try {
        client.request(request.url) {
          method =
            when (request.method) {
              HttpMethod.GET -> KtorHttpMethod.Get
              HttpMethod.POST -> KtorHttpMethod.Post
            }
          request.headers.forEach { (name, value) -> header(name, value) }
          val payload = request.body
          if (payload != null) {
            request.contentType?.let { contentType(ContentType.parse(it)) }
            setBody(payload)
          }
        }
      } catch (cancellation: CancellationException) {
        // A cancelled call is not a network failure, and swallowing it here would leave the
        // caller's coroutine looking successful while its scope is already dead.
        throw cancellation
      } catch (failure: Throwable) {
        throw TraciveException.Network(cause = failure)
      }

    val headers = mutableMapOf<String, String>()
    response.headers.entries().forEach { entry ->
      entry.value.firstOrNull()?.let { headers[entry.key.lowercase()] = it }
    }

    val body =
      try {
        response.bodyAsText()
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (failure: Throwable) {
        // The status line arrived but the body did not. Treat it as a transport failure rather than
        // reporting a status with an empty body, which would be indistinguishable from a real
        // no-content reply and could be mapped to the wrong error.
        throw TraciveException.Network(cause = failure)
      }

    return HttpResponse(status = response.status.value, body = body, headers = headers)
  }

  override fun close() {
    if (ownsClient) client.close()
  }
}
