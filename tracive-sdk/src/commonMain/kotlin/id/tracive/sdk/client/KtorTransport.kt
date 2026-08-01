package id.tracive.sdk.client

import id.tracive.sdk.TraciveException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse as KtorResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod as KtorHttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray

/**
 * The default [HttpTransport], on Ktor.
 *
 * Deliberately thin: it moves bytes and normalises headers, and does not interpret status codes,
 * parse bodies or retry. All of that lives above the interface so it is testable without a socket.
 *
 * ### Timeouts
 *
 * The default client installs `HttpTimeout` with the explicit values in this class's companion. It
 * has to be explicit: without the plugin the numbers fall through to whatever the engine chose, and
 * OkHttp on Android and `NSURLSession` on Darwin do not choose the same ones — so the same call
 * would give up after different intervals on the two platforms, and neither number would be written
 * down anywhere. `RetryPolicy` and `TraciveException.Network` both talk about "a read timeout" as
 * an SDK-level concept, and these are it.
 *
 * A timeout surfaces as [TraciveException.Network], which for `checkScammer` is a request whose
 * outcome is genuinely unknown — the server may have processed and billed the check before the
 * client stopped waiting. That is why the request timeout is generous rather than snappy, and why
 * the SDK will not retry a check without an idempotency key.
 *
 * An injected client is left exactly as the caller configured it, timeouts included.
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
  private val client: HttpClient = defaultHttpClient(),
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
        readBoundedBody(response)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (failure: TraciveException) {
        throw failure
      } catch (failure: Throwable) {
        // The status line arrived but the body did not. Treat it as a transport failure rather than
        // reporting a status with an empty body, which would be indistinguishable from a real
        // no-content reply and could be mapped to the wrong error.
        throw TraciveException.Network(cause = failure)
      }

    return HttpResponse(status = response.status.value, body = body, headers = headers)
  }

  /**
   * The response body, refusing to materialise more than [MAX_RESPONSE_BYTES].
   *
   * `bodyAsText()` reads whatever arrives. Every reply this SDK understands is a small JSON
   * document — the largest is a full-detail `CheckResult` with narrative text — so an unbounded
   * read buys nothing and costs the ability of a misbehaving or hostile origin to make the SDK
   * allocate a string of its choosing. That origin is reachable: `TraciveEnvironment.Custom` points
   * the client anywhere the host names, and phase 2 puts a partner-operated proxy in the path.
   *
   * `Content-Length` is checked first when the reply declares one, so an oversized body is refused
   * before a byte of it is read. It is only a hint, though — a chunked reply has none and a hostile
   * one can lie — so the read itself is capped as well, and that cap is what actually holds.
   */
  private suspend fun readBoundedBody(response: KtorResponse): String {
    val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > MAX_RESPONSE_BYTES) throw oversizedBody()
    // One byte past the limit, so "exactly at the limit" and "over it" are distinguishable.
    val bytes = response.bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES + 1L).readByteArray()
    if (bytes.size > MAX_RESPONSE_BYTES) throw oversizedBody()
    return bytes.decodeToString()
  }

  private fun oversizedBody(): TraciveException =
    TraciveException.Network(
      "The Tracive API returned a response larger than the SDK will read ($MAX_RESPONSE_BYTES bytes)."
    )

  override fun close() {
    if (ownsClient) client.close()
  }

  companion object {
    /**
     * Whole-request ceiling, connect and read included. Generous because the cost of giving up
     * early on a `POST /scammer-checks` is an outcome nobody knows: the server may already have run
     * and billed the check.
     */
    const val REQUEST_TIMEOUT_MS: Long = 30_000

    /** Establishing the connection. Short: an unreachable host is not going to become reachable. */
    const val CONNECT_TIMEOUT_MS: Long = 10_000

    /** Between two bytes of the response. A stalled socket is the failure this one catches. */
    const val SOCKET_TIMEOUT_MS: Long = 30_000

    /**
     * Ceiling on a response body, in bytes.
     *
     * Comfortably above anything the API can legitimately send — a full-detail `CheckResult` with
     * narrative text and comments is a few kilobytes — and far below anything that would matter to
     * a phone. The point is that the number exists at all.
     */
    const val MAX_RESPONSE_BYTES: Int = 512 * 1024
  }
}

/** The client [KtorTransport] builds when the caller does not supply one. */
internal fun defaultHttpClient(): HttpClient = HttpClient {
  install(HttpTimeout) {
    requestTimeoutMillis = KtorTransport.REQUEST_TIMEOUT_MS
    connectTimeoutMillis = KtorTransport.CONNECT_TIMEOUT_MS
    socketTimeoutMillis = KtorTransport.SOCKET_TIMEOUT_MS
  }
}
