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
 */
data class RetryPolicy(
  /** Total attempts, including the first. `1` disables retrying. */
  val maxAttempts: Int = 3,

  /** Wait before the second attempt. Each further wait multiplies by [backoffMultiplier]. */
  val initialBackoff: Duration = 200.milliseconds,

  /** Growth factor of the backoff. */
  val backoffMultiplier: Double = 2.0,

  /** Ceiling on a single wait, so a long chain of failures cannot stall a UI indefinitely. */
  val maxBackoff: Duration = 2.seconds,

  /**
   * How much of each wait is randomised, as a fraction of it.
   *
   * Without jitter, every client that saw the same outage retries at the same instant and rebuilds
   * the load spike that caused it. `0.0` disables it, which is what tests want.
   */
  val jitterRatio: Double = 0.25,
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
    /** Three attempts with jittered exponential backoff. */
    val Default: RetryPolicy = RetryPolicy()

    /** No retrying at all: one attempt, and the first failure is the answer. */
    val None: RetryPolicy = RetryPolicy(maxAttempts = 1)
  }
}
