package id.tracive.sdk.model

/**
 * Where a check may read from, and in what order.
 *
 * A client-side routing choice; it is never sent to the server.
 */
enum class Source {
  /**
   * A live cache entry if there is one, otherwise the server. The default, and the only setting
   * that reliably keeps the bill down.
   */
  CACHE_FIRST,

  /** Always the server. The cache is still written, but never read. */
  SERVER,

  /**
   * The server, falling back to a live cache entry if the request fails.
   *
   * Use this for freshness-sensitive flows that still need an answer offline. It costs a credit
   * whenever the server does answer.
   */
  SERVER_FIRST,

  /**
   * The cache only; never touches the network and never spends a credit.
   *
   * With no live entry this raises `TraciveException.CacheMiss` rather than returning an empty
   * result — an empty [CheckResult] reads as "this identifier is clean", which is the most
   * dangerous wrong answer this SDK can give.
   */
  CACHE,
}
