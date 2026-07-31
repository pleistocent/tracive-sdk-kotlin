package id.tracive.sdk.model

/**
 * Which Tracive deployment the client talks to.
 *
 * [baseUrl] never ends in a slash, so callers can join a path with a single `/` and get the same
 * result for every case.
 */
sealed class TraciveEnvironment {

  /** Root of the v1 API, with no trailing slash. */
  abstract val baseUrl: String

  /** The live API. Real credits, real users. */
  object Production : TraciveEnvironment() {
    override val baseUrl: String = "https://api.tracive.id/v1"

    override fun toString(): String = "Production"
  }

  /** The sandbox API: the same contract, test data, no real credits. */
  object Sandbox : TraciveEnvironment() {
    override val baseUrl: String = "https://api-sandbox.tracive.id/v1"

    override fun toString(): String = "Sandbox"
  }

  /**
   * Any other origin: a partner's own proxy, a staging deployment, or a local server during tests.
   *
   * @param baseUrl an absolute `http` or `https` URL. A trailing slash is trimmed. `http` is
   *   accepted for local testing only — never point a shipped build at a plaintext origin, because
   *   the `Authorization: Bearer` header would travel in the clear.
   * @throws IllegalArgumentException if [baseUrl] is not an absolute HTTP(S) URL.
   */
  class Custom(baseUrl: String) : TraciveEnvironment() {
    override val baseUrl: String = baseUrl.trim().trimEnd('/')

    init {
      require(this.baseUrl.startsWith("https://") || this.baseUrl.startsWith("http://")) {
        "A custom TraciveEnvironment needs an absolute http:// or https:// base URL."
      }
    }

    override fun equals(other: Any?): Boolean =
      this === other || (other is Custom && other.baseUrl == baseUrl)

    override fun hashCode(): Int = baseUrl.hashCode()

    override fun toString(): String = "Custom($baseUrl)"
  }
}
