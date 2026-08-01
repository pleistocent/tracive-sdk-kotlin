package id.tracive.sdk.oauth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The comparison the OAuth callback measures `state` with.
 *
 * What this test can and cannot show is worth being plain about. It pins the *answers*, which is
 * what stops a rewrite from breaking the callback, and it pins the fact that the function exists at
 * all, which is what stops this SDK drifting back to `!=` while the other three compare in constant
 * time. It does not measure timing, and no unit test on a JIT-compiled runtime honestly could: a
 * test that timed two comparisons would pass or fail on scheduler noise, so it would be worse than
 * no test — it would look like proof.
 *
 * The behaviour is identical to the `!=` this replaced, deliberately. A callback with a mismatched
 * `state` is refused exactly as before; `TraciveOAuthTest` covers that end to end.
 */
class ConstantTimeTest {

  @Test
  fun equalValuesMatch() {
    assertTrue(constantTimeEquals("", ""))
    assertTrue(constantTimeEquals("a", "a"))
    assertTrue(constantTimeEquals(STATE, STATE))
    // A separately built string, so this cannot pass on reference identity.
    assertTrue(constantTimeEquals(STATE, buildString { append(STATE) }))
  }

  @Test
  fun aDifferenceIsFoundWhereverItSits() {
    // First byte, last byte and the middle: an implementation that stopped early would still get
    // all three right, but one that compared only a prefix or only a suffix would not.
    assertFalse(constantTimeEquals(STATE, "Z" + STATE.drop(1)))
    assertFalse(constantTimeEquals(STATE, STATE.dropLast(1) + "Z"))
    assertFalse(constantTimeEquals(STATE, STATE.take(10) + "Z" + STATE.drop(11)))
  }

  @Test
  fun lengthAloneIsEnoughToRefuse() {
    assertFalse(constantTimeEquals(STATE, STATE + "x"))
    assertFalse(constantTimeEquals(STATE, STATE.dropLast(1)))
    assertFalse(constantTimeEquals("", STATE))
    assertFalse(constantTimeEquals(STATE, ""))
  }

  /**
   * Bytes, not chars. Two strings that differ only outside the BMP must still compare unequal, and
   * the accumulator must not lose a difference that lands in the high bit of a byte.
   */
  @Test
  fun multiByteCharactersAreComparedByTheirBytes() {
    assertTrue(constantTimeEquals("héllo·世界", "héllo·世界"))
    assertFalse(constantTimeEquals("héllo·世界", "héllo·世堺"))
    assertFalse(constantTimeEquals("é", "e"))
  }

  private companion object {
    /** The shape `PkceGenerator.randomState()` produces: 32 bytes of base64url. */
    const val STATE = "bV9xR2h0LWEyUTdfWnhLcGxNbjNzVHZXdVkwaUNkRWY"
  }
}
