package id.tracive.sdk.sample

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.tracive.sdk.TraciveException
import id.tracive.sdk.TraciveQueryException
import id.tracive.sdk.model.AuthResult
import id.tracive.sdk.model.CheckResult
import id.tracive.sdk.query.TraciveQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the one screen in this sample renders. */
data class SampleUiState(
  val phone: String = "+62",
  val busy: Boolean = false,
  val authorized: Boolean = false,
  val result: CheckResult? = null,
  val message: String? = null,
  val cachedEntries: Int = 0,
)

/**
 * Drives the sample screen: authorize, check, sign out, clear cache.
 *
 * Worth reading for three things a partner integration has to get right, all of which are easier to
 * see here than in prose:
 *  - the query is **built before** anything is sent, so bad input costs nothing (see [check]);
 *  - errors are handled by *type*, not by string matching (see [describe]); and
 *  - the disclaimer travels with the result and is rendered next to it, which the SDK requires of
 *    any UI built on it.
 */
class CheckViewModel(application: Application) : AndroidViewModel(application) {

  private val client = TraciveSample.client(application)

  private val _state = MutableStateFlow(SampleUiState())
  val state: StateFlow<SampleUiState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      _state.update {
        it.copy(
          authorized = client.oauth?.isAuthorized() == true,
          cachedEntries = client.cache.size(),
        )
      }
    }
  }

  fun onPhoneChanged(value: String) {
    _state.update { it.copy(phone = value, message = null) }
  }

  /**
   * Starts the OAuth flow and suspends until the redirect comes back through
   * [MainActivity.onNewIntent].
   *
   * There is no deferred resume by design: if the Tracive app is not installed the SDK opens the
   * install page and stops. The user installs it and taps this again.
   */
  fun authorize() {
    launchGuarded {
      when (val outcome = client.oauth?.authorize()) {
        is AuthResult.Authorized ->
          _state.update {
            it.copy(
              authorized = true,
              message = "Authorized. Scopes granted: ${outcome.grantedScopes.joinToString()}",
            )
          }

        // Only reachable for a CONFIDENTIAL client. This sample is PUBLIC, so it never gets here —
        // the branch exists because the `when` is exhaustive and silently dropping an outcome is
        // how a flow ends up hanging with no explanation.
        is AuthResult.CodeReceived ->
          _state.update {
            it.copy(message = "Authorization code received. Forward it to your own backend.")
          }

        AuthResult.Denied -> _state.update { it.copy(message = "You declined the request.") }

        AuthResult.AppNotInstalled ->
          _state.update {
            it.copy(message = "Install the Tracive app first — the install page has been opened.")
          }

        is AuthResult.Failed -> _state.update { it.copy(message = describe(outcome.error)) }

        null -> _state.update { it.copy(message = "Proxy mode has no OAuth flow of its own.") }
      }
    }
  }

  /** Hands a redirect back to the SDK. Returns false when the link was not ours. */
  fun handleRedirect(url: String): Boolean = client.oauth?.handleCallback(url) == true

  fun check() {
    val input = _state.value.phone

    // Validation first, and locally. A malformed number cannot match anything the server has, but
    // the server still bills the lookup that discovers that — so the SDK refuses to send it, and
    // this is where that refusal surfaces.
    val query =
      try {
        TraciveQuery.phone(input)
      } catch (invalid: TraciveQueryException) {
        _state.update { it.copy(message = describeQuery(invalid), result = null) }
        return
      }

    launchGuarded {
      // Deliberately no `credits()` call before this. Pre-flighting the balance doubles the request
      // count on every transaction and still races with concurrent spending; handling the 402 below
      // is both cheaper and correct.
      val result = client.checkScammer(query)
      _state.update {
        it.copy(result = result, message = null, cachedEntries = client.cache.size())
      }
    }
  }

  fun signOut() {
    launchGuarded {
      client.signOut()
      // Sign-out has to clear the cache as well. The entries belong to the user who was authorised
      // when they were fetched, and on a shared device the next person must not inherit them.
      client.cache.clear()
      _state.update {
        it.copy(authorized = false, result = null, cachedEntries = 0, message = "Signed out.")
      }
    }
  }

  fun clearCache() {
    launchGuarded {
      client.cache.clear()
      _state.update { it.copy(cachedEntries = 0, message = "Cache cleared.") }
    }
  }

  override fun onCleared() {
    // The client owns an HTTP engine. This sample shares one instance for the whole process, so it
    // is not closed here; an app that builds a client per screen must close it.
    super.onCleared()
  }

  private fun launchGuarded(block: suspend () -> Unit) {
    if (_state.value.busy) return
    _state.update { it.copy(busy = true, message = null) }
    viewModelScope.launch {
      try {
        block()
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: TraciveException) {
        _state.update { it.copy(message = describe(error)) }
      } finally {
        _state.update { it.copy(busy = false) }
      }
    }
  }

  /**
   * Turns a typed SDK error into something a person can act on.
   *
   * Branching on the type rather than on `message` is the point. The wording of a server message is
   * not a contract and will be translated; the type is.
   */
  private fun describe(error: TraciveException): String =
    when (error) {
      is TraciveException.InsufficientCredits ->
        "Out of credits (${error.which}). Offer a top-up or a subscription here."
      is TraciveException.RateLimited ->
        "Rate limited." +
          (error.retryAfter?.let { " Try again in $it." }
            ?: " Back off before retrying.") +
          " Pass an Idempotency-Key when you retry a check, or you may be charged twice."
      is TraciveException.GrantRevoked -> "Access was revoked. Authorize again."
      is TraciveException.ExpiredToken,
      is TraciveException.InvalidToken -> "Session expired. Authorize again."
      is TraciveException.MissingScope -> "This build did not request the scope this call needs."
      is TraciveException.DetailNotPermitted -> "This client may not request full detail."
      is TraciveException.CacheMiss -> "Nothing cached for that identifier yet."
      is TraciveException.NotImplemented -> error.message ?: "Not available in this release."
      is TraciveException.Network ->
        "Could not reach Tracive. Note that the Open API is not live yet, so this is expected today."
      is TraciveException.Server -> "Tracive had a server error (${error.httpStatus})."
      else -> error.message ?: "The check failed."
    }

  private fun describeQuery(error: TraciveQueryException): String =
    when (error.reason) {
      TraciveQueryException.NOT_E164 ->
        "Enter the number in international form, e.g. +6281234567891. A local format such as " +
          "081234567891 is a different entity to the server, and looking it up would still cost a " +
          "credit."
      else -> "That identifier cannot be looked up (${error.reason})."
    }
}
