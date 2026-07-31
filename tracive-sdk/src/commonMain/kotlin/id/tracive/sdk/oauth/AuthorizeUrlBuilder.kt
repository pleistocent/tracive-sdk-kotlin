package id.tracive.sdk.oauth

import id.tracive.sdk.model.TraciveEnvironment
import id.tracive.sdk.model.TraciveMode

/**
 * Builds the URL the system browser opens to start an authorization.
 *
 * The authorize endpoint is **not** on the API host. `api.tracive.id/v1` answers the data calls;
 * the authorize URL points at `tracive.id`, which is the origin the Tracive app claims as an App
 * Link / Universal Link. That claim is what makes the flow app-to-app: the OS hands the URL to the
 * installed Tracive app instead of loading a page. Pointing this at the API host would defeat the
 * claim and drop the user on a web page — the one outcome the design rules out, because a user who
 * learns to type Tracive credentials into whatever a partner app opened is a user who can be
 * phished by the next partner app.
 *
 * Everything in the URL is written by the SDK from values the caller configured. There is no API
 * key in it, in any mode, and there never will be — that is a hard design invariant of this SDK.
 */
class AuthorizeUrlBuilder(
  /** Picks the default endpoint; ignored when [authorizeEndpoint] is given. */
  environment: TraciveEnvironment = TraciveEnvironment.Production,

  /**
   * An explicit authorize endpoint, for a staging deployment or a test double.
   *
   * A trailing slash is trimmed. Leave it null in production so the endpoint tracks [environment].
   */
  authorizeEndpoint: String? = null,
) {

  /** The absolute authorize endpoint this builder targets, with no query string. */
  val endpoint: String = (authorizeEndpoint?.trim()?.trimEnd('/')) ?: endpointFor(environment)

  init {
    require(endpoint.startsWith("https://") || endpoint.startsWith("http://")) {
      "An authorize endpoint must be an absolute http:// or https:// URL."
    }
  }

  /**
   * The full authorize URL for one attempt.
   *
   * Parameters are emitted in a fixed order so the result is reproducible and diffable in a test.
   * Every value is percent-encoded: a redirect URI contains `:` and `/`, and a scope list contains
   * spaces, all of which would otherwise truncate or split the query.
   *
   * @param mode the configured OAuth client.
   * @param pkce this attempt's proof pair. Only [Pkce.codeChallenge] is sent; the verifier stays on
   *   the device until the token exchange.
   * @param state this attempt's CSRF token, checked when the callback arrives.
   * @param extraParams additional parameters, for a deployment that needs one. Keys that would
   *   collide with an OAuth parameter are rejected rather than silently duplicated, because a
   *   duplicated `state` or `code_challenge` is a security bug and servers disagree about which
   *   copy wins.
   */
  fun build(
    mode: TraciveMode.OAuth,
    pkce: Pkce,
    state: String,
    extraParams: Map<String, String> = emptyMap(),
  ): String {
    require(state.isNotBlank()) { "An authorize URL needs a state value." }
    val parameters = mutableListOf<Pair<String, String>>()
    parameters += PARAM_RESPONSE_TYPE to "code"
    parameters += PARAM_CLIENT_ID to mode.clientId
    parameters += PARAM_REDIRECT_URI to mode.redirectUri
    if (mode.scopes.isNotEmpty()) parameters += PARAM_SCOPE to mode.scopes.joinToString(" ")
    parameters += PARAM_STATE to state
    parameters += PARAM_CODE_CHALLENGE to pkce.codeChallenge
    parameters += PARAM_CODE_CHALLENGE_METHOD to pkce.codeChallengeMethod
    mode.partnerUserId
      ?.takeIf { it.isNotBlank() }
      ?.let { parameters += PARAM_PARTNER_USER_ID to it }

    for ((key, value) in extraParams) {
      require(key.isNotBlank()) { "An authorize parameter needs a name." }
      require(key !in RESERVED_PARAMS) { "$key is set by the SDK and cannot be overridden." }
      parameters += key to value
    }

    return buildString {
      append(endpoint)
      append('?')
      parameters.forEachIndexed { index, (key, value) ->
        if (index > 0) append('&')
        append(percentEncode(key))
        append('=')
        append(percentEncode(value))
      }
    }
  }

  companion object {
    /** The production authorize origin: the host the shipping Tracive app claims. */
    const val PRODUCTION_AUTHORIZE_URL: String = "https://tracive.id/oauth/authorize"

    /** The sandbox authorize origin, claimed by the `.dev` build of the Tracive app. */
    const val SANDBOX_AUTHORIZE_URL: String = "https://sandbox.tracive.id/oauth/authorize"

    /** Path appended to a custom environment's base URL. */
    const val AUTHORIZE_PATH: String = "/oauth/authorize"

    const val PARAM_RESPONSE_TYPE: String = "response_type"
    const val PARAM_CLIENT_ID: String = "client_id"
    const val PARAM_REDIRECT_URI: String = "redirect_uri"
    const val PARAM_SCOPE: String = "scope"
    const val PARAM_STATE: String = "state"
    const val PARAM_CODE_CHALLENGE: String = "code_challenge"
    const val PARAM_CODE_CHALLENGE_METHOD: String = "code_challenge_method"
    const val PARAM_PARTNER_USER_ID: String = "partner_user_id"

    private val RESERVED_PARAMS =
      setOf(
        PARAM_RESPONSE_TYPE,
        PARAM_CLIENT_ID,
        PARAM_REDIRECT_URI,
        PARAM_SCOPE,
        PARAM_STATE,
        PARAM_CODE_CHALLENGE,
        PARAM_CODE_CHALLENGE_METHOD,
        PARAM_PARTNER_USER_ID,
      )

    /** The authorize endpoint for [environment]. */
    fun endpointFor(environment: TraciveEnvironment): String =
      when (environment) {
        is TraciveEnvironment.Production -> PRODUCTION_AUTHORIZE_URL
        is TraciveEnvironment.Sandbox -> SANDBOX_AUTHORIZE_URL
        // A custom environment is a partner proxy or a local server, which serves both halves.
        is TraciveEnvironment.Custom -> environment.baseUrl + AUTHORIZE_PATH
      }

    /**
     * Percent-encodes [value] for a query string, leaving only RFC 3986's `unreserved` set intact.
     *
     * Deliberately stricter than a URL encoder has to be — `+`, `&`, `=` and `/` are all encoded
     * rather than passed through. A `+` in a raw query is decoded as a space by many servers, and a
     * stray `&` in a value silently splits it into two parameters, which is how a redirect URI ends
     * up truncated at the point where it matters most.
     */
    fun percentEncode(value: String): String {
      val bytes = value.encodeToByteArray()
      val out = StringBuilder(bytes.size)
      val digits = "0123456789ABCDEF"
      for (byte in bytes) {
        val code = byte.toInt() and 0xFF
        val character = code.toChar()
        val unreserved =
          character in 'A'..'Z' ||
            character in 'a'..'z' ||
            character in '0'..'9' ||
            character == '-' ||
            character == '.' ||
            character == '_' ||
            character == '~'
        if (unreserved) {
          out.append(character)
        } else {
          out.append('%')
          out.append(digits[code ushr 4])
          out.append(digits[code and 0x0F])
        }
      }
      return out.toString()
    }
  }
}
