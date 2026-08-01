package id.tracive.sdk.client

import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * When the SDK may repeat a request that failed for a reason that might be transient.
 *
 * The policy applies to 5xx replies and to transport failures, and **only to requests the SDK knows
 * are safe to repeat**:
 * - `GET /credits` and `GET /profile` are safe by definition. They read.
 * - `POST /scammer-checks` is safe **only when the caller supplied an `Idempotency-Key`**. A blind
 *   retry of a check is a request to be charged twice: a 5xx or a read timeout does not tell the
 *   client whether the server processed the check before it failed, and the check is billed on the
 *   server side. With a key the server collapses the repeat onto the original charge; without one
 *   there is nothing to collapse it onto, so the SDK does not retry, no matter what this policy
 *   says. That decision lives in `TraciveClient.checkScammer`, not here.
 *
 * 429 is never auto-retried either. The server has told the caller to slow down and for how long;
 * spending the wait inside a suspended SDK call hides a queueing problem the app should see.
 * `TraciveException.RateLimited.retryAfter` carries the hint instead.
 *
 * ### The numbers, and why they are these numbers
 *
 * Every Tracive SDK ships this same set. None of them was derived from production data — there is
 * no production yet — so what follows is the reasoning, written down so the next person can
 * disagree with an argument rather than quietly pick different values. Four SDKs drifting into four
 * sets of these numbers is what actually happened, and it happened because nobody wrote this
 * section.
 *
 * - **3 attempts.** One try plus two retries rides out a single unhealthy instance or a rolling
 *   redeploy, which is what a retry can fix. A fourth does not fix an outage; it only makes the app
 *   wait longer before telling the user that something is wrong.
 * - **×2 growth.** The unexamined default, and rightly so: it puts the second wait far enough from
 *   the first to matter, without needing a third parameter to justify itself.
 * - **500 ms first wait.** Long enough for a load balancer to take a failing instance out and send
 *   the retry elsewhere, and short enough that someone waiting on a fraud check before sending
 *   money does not read it as the app having stopped. Much below that the retry often lands on the
 *   same unhealthy instance, costing a round trip and buying nothing.
 * - **20% jitter.** Enough to smear the retries of every app that saw one outage across a window
 *   instead of rebuilding the spike that caused it. Small enough that the schedule is still roughly
 *   the advertised one when somebody is reading a trace.
 * - **8 s ceiling.** At three attempts the waits are 500 ms and 1 s, so this bound is never reached
 *   with the defaults; it is there for a caller who raises [maxAttempts]. Eight seconds keeps the
 *   worst single wait under a typical client-side HTTP timeout, so this SDK is never the reason a
 *   request is abandoned by something above it.
 */
data class RetryPolicy(
  /** Total attempts, including the first. `1` disables retrying. */
  val maxAttempts: Int = 3,

  /** Wait before the second attempt. Each further wait multiplies by [backoffMultiplier]. */
  val initialBackoff: Duration = 500.milliseconds,

  /** Growth factor of the backoff. */
  val backoffMultiplier: Double = 2.0,

  /** Ceiling on a single wait, so a long chain of failures cannot stall a UI indefinitely. */
  val maxBackoff: Duration = 8.seconds,

  /**
   * How much of each wait is randomised, as a fraction of it.
   *
   * Without jitter, every client that saw the same outage retries at the same instant and rebuilds
   * the load spike that caused it. `0.0` disables it, which is what tests want.
   */
  val jitterRatio: Double = 0.2,
) {

  init {
    require(maxAttempts >= 1) { "RetryPolicy needs at least one attempt." }
    require(backoffMultiplier >= 1.0) { "RetryPolicy's backoff must not shrink." }
    require(jitterRatio in 0.0..1.0) { "RetryPolicy's jitterRatio is a fraction of the wait." }
  }

  /**
   * How long to wait before attempt number [nextAttempt] (2 for the first retry).
   *
   * Returns [Duration.ZERO] when [initialBackoff] is zero, which lets a test run the retry path
   * with no wall-clock delay at all.
   */
  fun backoffBefore(nextAttempt: Int): Duration {
    if (initialBackoff <= Duration.ZERO) return Duration.ZERO
    val exponent = (nextAttempt - 2).coerceAtLeast(0)
    val scaled = initialBackoff * backoffMultiplier.pow(exponent)
    val capped = if (scaled > maxBackoff) maxBackoff else scaled
    if (jitterRatio == 0.0) return capped
    val jitter = capped * (jitterRatio * Random.nextDouble())
    return capped - jitter
  }

  companion object {
    /**
     * Three attempts, 500 ms then 1 s, 20% jitter, capped at 8 s.
     *
     * The same values in every Tracive SDK. The block above this class says why each one is what it
     * is; change one here and it has to change in all four, with that reasoning updated.
     */
    val Default: RetryPolicy = RetryPolicy()

    /** No retrying at all: one attempt, and the first failure is the answer. */
    val None: RetryPolicy = RetryPolicy(maxAttempts = 1)
  }
}
