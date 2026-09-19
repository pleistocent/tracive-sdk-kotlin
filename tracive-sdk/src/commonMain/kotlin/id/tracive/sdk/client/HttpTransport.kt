package id.tracive.sdk.client

/**
 * The one place the SDK touches the network.
 *
 * Everything above this interface — endpoint construction, the error table, the retry rules, the
 * single-flight refresh, the cache — is pure logic over [HttpRequest] and [HttpResponse]. That is
 * what lets the whole SDK be tested with no server and no sockets — including the replies no real
 * server gives on demand, such as a 402, a 429 or a revoked grant.
 *
 * Implementations must not log. A request carries `Authorization: Bearer <token>` and, on the token
 * endpoint, a refresh token in the body; a response body from `/oauth/token` carries both.
 */
interface HttpTransport {

  /**
   * Performs [request] and returns the reply.
   *
   * A non-2xx status is a normal return value, not an exception: the layer above reads the error
   * envelope out of the body and maps it to a typed `TraciveException`. Throw only when there is no
   * reply at all — DNS, TLS, connect or read failure — ideally as `TraciveException.Network`.
   * Anything else that escapes is wrapped as `Network` by the caller, so a fake transport can throw
   * whatever is convenient.
   *
   * Cancellation must propagate: rethrow `CancellationException` untouched.
   */
  suspend fun execute(request: HttpRequest): HttpResponse

  /** Releases the underlying client. Idempotent; the default does nothing. */
  fun close() {}
}

/** The HTTP verbs the Tracive API uses. */
enum class HttpMethod {
  GET,
  POST,
}

/**
 * One outbound request, fully resolved: absolute URL, literal headers, body already serialized.
 *
 * Not a `data class`, and [toString] is redacted — [headers] holds the bearer token and [body]
 * holds a refresh token on the OAuth endpoints. A generated `toString` would put both into any log
 * line that prints a request.
 */
class HttpRequest(
  val method: HttpMethod,
  val url: String,
  val headers: Map<String, String> = emptyMap(),
  val body: String? = null,
  val contentType: String? = null,
) {

  /** Case-insensitive header lookup. */
  fun header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

  /** Method and URL only. Header values and the body are credentials and never printed. */
  override fun toString(): String = "HttpRequest($method $url, headers=${headers.keys.sorted()})"
}

/**
 * One reply. [headers] keys are lower-cased by the transport so lookups are stable across engines.
 *
 * Not a `data class` for the same reason as [HttpRequest]: the `/oauth/token` reply body is a pair
 * of live credentials.
 */
class HttpResponse(
  val status: Int,
  val body: String = "",
  val headers: Map<String, String> = emptyMap(),
) {

  /** Case-insensitive header lookup. */
  fun header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

  /** True for 2xx. */
  val isSuccessful: Boolean
    get() = status in 200..299

  /** Status only. The body can be a token response. */
  override fun toString(): String = "HttpResponse($status, ${body.length} chars)"
}
