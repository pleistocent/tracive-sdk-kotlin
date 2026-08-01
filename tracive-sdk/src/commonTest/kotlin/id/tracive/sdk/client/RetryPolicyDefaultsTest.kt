package id.tracive.sdk.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The retry defaults, pinned to the values every Tracive SDK ships.
 *
 * Pinning constants normally proves nothing. It proves something here, because these five numbers
 * had already drifted into four different sets across four SDKs — each one locally reasonable, none
 * of them written down, and nothing anywhere able to notice. A test is the cheapest thing that
 * turns "someone changed a default" into a conversation.
 *
 * `RetryPolicy`'s own documentation carries the argument for each number. If one of these
 * assertions fails, the fix is not to update the expected value on its own: it is to change all
 * four SDKs and the reasoning together, or to leave it alone.
 */
class RetryPolicyDefaultsTest {

  @Test
  fun theDefaultsAreTheSharedOnes() {
    val policy = RetryPolicy.Default

    assertEquals(3, policy.maxAttempts)
    assertEquals(500.milliseconds, policy.initialBackoff)
    assertEquals(2.0, policy.backoffMultiplier)
    assertEquals(8.seconds, policy.maxBackoff)
    assertEquals(0.2, policy.jitterRatio)
  }

  /**
   * The schedule those numbers produce, which is what a caller actually experiences. Read with
   * jitter off, so the assertion is exact rather than a range.
   */
  @Test
  fun theScheduleIsFiveHundredMillisecondsThenOneSecond() {
    val policy = RetryPolicy.Default.copy(jitterRatio = 0.0)

    assertEquals(500.milliseconds, policy.backoffBefore(2))
    assertEquals(1.seconds, policy.backoffBefore(3))
    // Two waits before the third and last attempt: a caller sees at most 1.5 s of added latency.
    assertEquals(1500.milliseconds, policy.backoffBefore(2) + policy.backoffBefore(3))
  }

  /**
   * The ceiling is not reached at the defaults — it is there for a caller who raises `maxAttempts`,
   * and 8 s keeps the worst single wait under a typical client-side HTTP timeout.
   */
  @Test
  fun theCeilingBoundsALongerChainOfAttempts() {
    val policy = RetryPolicy.Default.copy(maxAttempts = 8, jitterRatio = 0.0)

    assertEquals(4.seconds, policy.backoffBefore(5))
    assertEquals(8.seconds, policy.backoffBefore(6))
    assertEquals(8.seconds, policy.backoffBefore(20), "the wait must stop growing at the ceiling")
  }

  /** Jitter only ever shortens a wait, and never past zero. */
  @Test
  fun jitterStaysInsideItsShareOfTheWait() {
    val policy = RetryPolicy.Default
    val floor = 500.milliseconds * (1.0 - policy.jitterRatio)

    repeat(200) {
      val wait = policy.backoffBefore(2)
      assertTrue(wait in floor..500.milliseconds, "a jittered wait left its band: $wait")
    }
  }
}
