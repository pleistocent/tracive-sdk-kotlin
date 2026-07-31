package id.tracive.sdk.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PKCE, pinned to RFC 7636.
 *
 * The challenge is the one value in this SDK that a *third party* recomputes: the authorization
 * server hashes the verifier the token exchange sends and compares it byte for byte with the
 * challenge the authorize URL carried. Any drift — a different digest, one padding character, an
 * encoding difference on a multibyte verifier — makes every exchange fail with a bare
 * `invalid_grant` that names none of those causes. Pinning the RFC's own vector here is what turns
 * that into a build failure instead of an integration mystery.
 */
class PkceGeneratorTest {

  /**
   * RFC 7636 Appendix B, verbatim. If this ever fails, the SDK's S256 implementation is wrong and
   * no partner's authorization can succeed.
   */
  @Test
  fun theRfc7636AppendixBVectorIsReproduced() {
    assertEquals(
      RFC_7636_CHALLENGE,
      PkceGenerator.challengeFor(RFC_7636_VERIFIER),
      "the RFC 7636 appendix B vector no longer reproduces",
    )
  }

  /** Independent SHA-256 vectors, so a failure distinguishes "wrong digest" from "wrong base64". */
  @Test
  fun otherS256VectorsAlsoReproduce() {
    assertEquals("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0", PkceGenerator.challengeFor("abc"))
    // A multibyte verifier: the digest is over UTF-8 bytes, never over UTF-16 code units.
    assertEquals(
      "aKGLNG1mpMrz6YmCFJX5K_bXIB9T3HHoJIAJV2rgkNA",
      PkceGenerator.challengeFor("héllo-wörld"),
    )
  }

  /**
   * A SHA-256 digest is 32 bytes, which base64 would normally pad with a single `=`. RFC 7636 wants
   * it unpadded, and a stray `=` is enough to make the server compute a different string from the
   * one it stored.
   */
  @Test
  fun theChallengeIsUnpaddedBase64Url() {
    val challenge = PkceGenerator.generate().codeChallenge

    assertEquals(43, challenge.length, "an unpadded SHA-256 digest is 43 base64url characters")
    assertFalse(challenge.contains('='), "the challenge was padded")
    assertTrue(challenge.all { it in BASE64URL }, "the challenge left the base64url alphabet")
  }

  @Test
  fun theChallengeMethodIsAlwaysS256() {
    assertEquals("S256", PkceGenerator.METHOD_S256)
    assertEquals(PkceGenerator.METHOD_S256, PkceGenerator.generate().codeChallengeMethod)
  }

  @Test
  fun aVerifierUsesOnlyUnreservedCharactersSoItNeedsNoEncoding() {
    val verifier = PkceGenerator.generate().codeVerifier

    assertEquals(PkceGenerator.DEFAULT_VERIFIER_LENGTH, verifier.length)
    assertTrue(verifier.all { it in BASE64URL }, "a verifier character would need percent-encoding")
  }

  @Test
  fun verifierLengthsAtBothRfcBoundsAreAccepted() {
    for (length in listOf(PkceGenerator.MIN_VERIFIER_LENGTH, PkceGenerator.MAX_VERIFIER_LENGTH)) {
      val pkce = PkceGenerator.generate(length)
      assertEquals(length, pkce.codeVerifier.length)
      // The pair must stay consistent whatever the length: this is what the server checks.
      assertEquals(PkceGenerator.challengeFor(pkce.codeVerifier), pkce.codeChallenge)
    }
  }

  /**
   * Clamping silently would let a caller believe it had asked for more entropy than it received,
   * which is the kind of downgrade nobody ever notices.
   */
  @Test
  fun aVerifierLengthOutsideTheRfcRangeIsRejected() {
    assertFailsWith<IllegalArgumentException> {
      PkceGenerator.generate(PkceGenerator.MIN_VERIFIER_LENGTH - 1)
    }
    assertFailsWith<IllegalArgumentException> {
      PkceGenerator.generate(PkceGenerator.MAX_VERIFIER_LENGTH + 1)
    }
  }

  @Test
  fun anEmptyVerifierIsRejectedRatherThanHashed() {
    assertFailsWith<IllegalArgumentException> { PkceGenerator.challengeFor("") }
  }

  /**
   * A crude but load-bearing check on the random source. A verifier an attacker can predict removes
   * the whole protection PKCE gives against an intercepted authorization code, and the cheapest way
   * that regression arrives is someone swapping the platform CSPRNG for `kotlin.random.Random`
   * seeded per call.
   */
  @Test
  fun verifiersAndStatesAreNotRepeated() {
    val verifiers = HashSet<String>()
    val states = HashSet<String>()
    repeat(SAMPLES) {
      verifiers += PkceGenerator.generate().codeVerifier
      states += PkceGenerator.randomState()
    }

    assertEquals(SAMPLES, verifiers.size, "a code verifier repeated across $SAMPLES draws")
    assertEquals(SAMPLES, states.size, "an OAuth state repeated across $SAMPLES draws")
  }

  @Test
  fun aStateWithTooLittleEntropyIsRejected() {
    assertFailsWith<IllegalArgumentException> { PkceGenerator.randomState(byteCount = 8) }
  }

  @Test
  fun aStateIsUrlSafe() {
    val state = PkceGenerator.randomState()

    assertTrue(state.isNotEmpty())
    assertTrue(state.all { it in BASE64URL }, "a state character would need percent-encoding")
  }

  /**
   * The verifier is a live credential until the code is redeemed, so it must not reach a log line
   * through a `toString`. This is why [Pkce] is not a data class.
   */
  @Test
  fun theVerifierNeverAppearsInAToString() {
    val pkce = PkceGenerator.generate()

    assertFalse(pkce.toString().contains(pkce.codeVerifier), "a toString leaked the code verifier")
  }

  private companion object {
    /** RFC 7636 Appendix B. */
    const val RFC_7636_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

    /** RFC 7636 Appendix B. */
    const val RFC_7636_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

    const val BASE64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    const val SAMPLES = 200
  }
}
