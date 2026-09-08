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
   * Opens the consent screen and returns; the answer arrives at [handleRedirect].
   *
   * `authorize()` has exactly three answers and none of them is a grant. Null means the page is
   * open and the user is now in the Tracive app. `AppNotInstalled` means nothing was started and
   * the install page has already been opened — there is no deferred resume, so the user installs
   * Tracive and taps this again. `Failed` means the device could not open the page at all.
   *
   * Note the explicit null check on `client.oauth` before the call rather than `client.oauth?.`:
   * with a nullable result, a safe call would fold "this is Proxy mode" and "the page opened" into
   * the same null.
   */
  fun authorize() {
    val oauth = client.oauth
    if (oauth == null) {
      _state.update { it.copy(message = "Proxy mode has no OAuth flow of its own.") }
      return
    }
    launchGuarded {
      when (val start = oauth.authorize()) {
        null ->
          _state.update {
            it.copy(message = "Consent screen opened. Finish it in the Tracive app.")
          }

        AuthResult.AppNotInstalled ->
          _state.update {
            it.copy(message = "Install the Tracive app first — the install page has been opened.")
          }

        is AuthResult.Failed -> _state.update { it.copy(message = describe(start.error)) }

        else ->
          // Unreachable: authorize() returns only the three answers above. The branch exists
          // because the `when` is exhaustive and quietly dropping an outcome is how a flow ends up
          // stuck with nothing on screen.
          _state.update { it.copy(message = "Unexpected result from authorize(): $start") }
      }
    }
  }

  /**
   * Hands a redirect to the SDK and renders whatever it says.
   *
   * This is where an authorization actually succeeds, including one begun by a process Android has
   * since killed — so the result is never discarded. A null means the link was not a Tracive
   * redirect, and a real app would go on routing it to whatever else handles deep links.
   *
   * Deliberately not [launchGuarded]: that drops the work when something else is already running,
   * and a redirect is the one thing here that must never be dropped.
   */
  fun handleRedirect(url: String) {
    viewModelScope.launch {
      when (val result = client.oauth?.handleCallback(url)) {
        // TODO(partner): not a Tracive redirect. Route it to whatever else in your app handles
        // deep links, instead of dropping it as this sample does.
        null -> Unit

        is AuthResult.Authorized ->
          _state.update {
            it.copy(
              authorized = true,
              message = "Authorized. Scopes granted: ${result.grantedScopes.joinToString()}",
            )
          }

        // Only reachable for a CONFIDENTIAL client. This sample is PUBLIC, so it never gets here.
        // TODO(partner): CONFIDENTIAL clients POST result.code, result.codeVerifier and
        // result.redirectUri to your own backend over TLS, and it completes the exchange with the
        // client secret. The code is single-use but is a bearer credential until redeemed, and the
        // verifier is what stops whoever intercepted the code from redeeming it.
        is AuthResult.CodeReceived ->
          _state.update {
            it.copy(message = "Authorization code received. Forward it to your own backend.")
          }

        AuthResult.Denied -> _state.update { it.copy(message = "You declined the request.") }

        // Never returned by handleCallback; the `when` is exhaustive over AuthResult.
        AuthResult.AppNotInstalled -> Unit

        is AuthResult.Failed -> _state.update { it.copy(message = describe(result.error)) }
      }
    }
  }

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
      // TODO(partner): pass an idempotencyKey — one UUID per logical check, reused only to retry
      // that same check. Without one the SDK will not retry a 5xx or a timeout at all, because a
      // blind retry is a request to be charged twice.
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
      // TODO(partner): send the user into your own top-up or subscription flow here, rather than
      // leaving them on a dead end.
      is TraciveException.InsufficientCredits -> "Out of credits (${error.which})."
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
