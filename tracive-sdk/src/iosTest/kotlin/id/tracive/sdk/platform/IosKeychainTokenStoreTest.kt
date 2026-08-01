package id.tracive.sdk.platform

import id.tracive.sdk.TraciveException
import id.tracive.sdk.oauth.TokenSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import platform.Security.errSecAuthFailed
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecSuccess

/**
 * The iOS token store, which nothing else in this suite reaches.
 *
 * `commonTest` cannot see `iosMain`, so before this file the Keychain writer had no test at all —
 * which is how it came to discard every `OSStatus` it received. These tests run in the
 * Kotlin/Native simulator binary, which is *not* an app bundle and therefore may be refused
 * Keychain access outright. That is accounted for below rather than worked around: a refusal is a
 * perfectly good input for the property being tested.
 */
@OptIn(ExperimentalForeignApi::class)
class IosKeychainTokenStoreTest {

  /**
   * A save either persists the grant or says it could not. It never does neither, quietly.
   *
   * This is the whole of H1. A refresh rotates the refresh token: the server issues a new one and
   * retires the old one together. If the replacement is lost and `save` returns normally anyway,
   * the device keeps presenting the retired token, the server reads that as a replay, and the
   * entire grant is revoked — the user is signed out of an app that did nothing wrong, with no log
   * line anywhere explaining it.
   *
   * Both outcomes are asserted because either is legitimate here. In a host process that can reach
   * the Keychain, the tokens must come back. In one that cannot — a bare test binary with no
   * keychain access group is refused with `errSecMissingEntitlement` — the refusal must arrive as
   * an exception. Before the fix the second branch returned normally and stored nothing, so
   * `load()` came back null and this test failed.
   */
  @Test
  fun aSaveEitherPersistsTheGrantOrReportsThatItCouldNot() = runBlocking {
    val store =
      IosKeychainTokenStore(service = "id.tracive.sdk.tests", account = "grant.v1.iostest")
    val tokens = TokenSet(accessToken = "access-1", refreshToken = "refresh-1")

    try {
      store.save(tokens)
    } catch (failure: TraciveException.TokenStorageFailed) {
      // Refused. Reporting the refusal is the behaviour under test, so this is a pass.
      assertNotNull(failure.platformStatus, "a Keychain refusal must carry its OSStatus")
      return@runBlocking
    }

    try {
      assertEquals("access-1", assertNotNull(store.load()).accessToken)
      assertEquals("refresh-1", assertNotNull(store.load()).refreshToken)
    } finally {
      store.clear()
    }
    assertNull(store.load(), "clear() must remove the item it just wrote")
  }

  /**
   * The status-to-exception decision, pinned independently of whether a Keychain is reachable.
   *
   * The two statuses named here are the ones a support engineer actually meets:
   * `errSecInteractionNotAllowed` means the device has not been unlocked since boot, and
   * `errSecAuthFailed` means the item exists but this process may not have it. They are different
   * problems with different answers, so the numeric status has to survive into the exception.
   */
  @Test
  fun onlyErrSecSuccessCountsAsAWrite() {
    assertNull(keychainWriteFailure(errSecSuccess))
    assertEquals(
      errSecInteractionNotAllowed,
      assertNotNull(keychainWriteFailure(errSecInteractionNotAllowed)).platformStatus,
    )
    assertEquals(
      errSecAuthFailed,
      assertNotNull(keychainWriteFailure(errSecAuthFailed)).platformStatus,
    )
  }
}
