package id.tracive.sdk.cache

import id.tracive.sdk.crypto.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Known-answer tests for the cache's hand-written SHA-256, AES and AES-GCM.
 *
 * The SDK implements all three in common Kotlin rather than calling `MessageDigest`/`CryptoKit`,
 * for the reasons stated in `AesGcm.kt` and `Sha256.kt`. That decision is only defensible with
 * these tests, and only with *known answers* rather than round trips: an implementation that
 * encrypts and decrypts consistently with itself, and agrees with nothing else on earth, passes
 * every round-trip test ever written and produces records that no reference implementation — and
 * possibly no future version of this SDK — can read. Broken crypto that round-trips is the exact
 * shape of failure that is silent until it is expensive.
 *
 * Vectors:
 * - SHA-256 from FIPS 180-4 Appendix B.
 * - AES from FIPS 197 Appendix C.1 (128-bit) and C.3 (256-bit).
 * - AES-GCM from the McGrew–Viega test-case set that NIST SP 800-38D is specified against, cases 13
 *   to 16. Case 13 pins the empty-plaintext path, 14 a single block, 15 a multi-block message with
 *   no associated data, and 16 the same message truncated to a non-block-aligned length with
 *   associated data — which together cover every padding branch in GHASH.
 */
class CacheCryptoTest {

  // ------------------------------------------------------------------ SHA-256 ----

  @Test
  fun sha256MatchesTheFips1804Vectors() {
    assertEquals(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      Sha256.digest("abc".encodeToByteArray()).toHex(),
    )
    assertEquals(
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      Sha256.digest(ByteArray(0)).toHex(),
    )
    // 56 bytes: one byte short of needing a second padding block, the classic off-by-one.
    assertEquals(
      "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
      Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())
        .toHex(),
    )
  }

  @Test
  fun sha256HandlesAMessageManyBlocksLong() {
    // A million 'a'. The long-message vector exists to catch a message-schedule or length-encoding
    // bug that a short input cannot reach: the 64-bit length field only becomes interesting once
    // the message no longer fits in one block.
    val million = ByteArray(1_000_000) { 'a'.code.toByte() }
    assertEquals(
      "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
      Sha256.digest(million).toHex(),
    )
  }

  // ---------------------------------------------------------------------- AES ----

  @Test
  fun aesMatchesTheFips197KnownAnswers() {
    val plaintext = hexToBytes("00112233445566778899aabbccddeeff")

    // Appendix C.1, AES-128. Not a key length this SDK uses, but it exercises the branch of the
    // key schedule that AES-256 does not, and a key schedule that is wrong for one length is
    // usually wrong in a way that shows up here first.
    val out128 = ByteArray(16)
    Aes(hexToBytes("000102030405060708090a0b0c0d0e0f")).encryptBlock(plaintext, 0, out128, 0)
    assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", out128.toHex())

    // Appendix C.3, AES-256 — the cache's actual cipher.
    val out256 = ByteArray(16)
    Aes(hexToBytes("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"))
      .encryptBlock(plaintext, 0, out256, 0)
    assertEquals("8ea2b7ca516745bfeafc49904b496089", out256.toHex())
  }

  @Test
  fun aesRejectsAKeyLengthItCannotUse() {
    assertFailsWith<IllegalArgumentException> { Aes(ByteArray(20)) }
  }

  // ------------------------------------------------------------------ AES-GCM ----

  @Test
  fun gcmMatchesTestCase13EmptyPlaintextAndNoAssociatedData() {
    val sealed = AesGcm.seal(ByteArray(32), ByteArray(12), ByteArray(0), ByteArray(0))
    assertEquals("530f8afbc74536b9a963b4f1c4cb738b", sealed.toHex())
  }

  @Test
  fun gcmMatchesTestCase14OneBlock() {
    val sealed = AesGcm.seal(ByteArray(32), ByteArray(12), ByteArray(16), ByteArray(0))
    assertEquals(
      "cea7403d4d606b6e074ec5d3baf39d18" + "d0d1c8a799996bf0265b98b5d48ab919",
      sealed.toHex(),
    )
  }

  @Test
  fun gcmMatchesTestCase15FourBlocksWithNoAssociatedData() {
    val key = hexToBytes("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
    val nonce = hexToBytes("cafebabefacedbaddecaf888")
    val plaintext =
      hexToBytes(
        "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
          "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255"
      )

    val sealed = AesGcm.seal(key, nonce, plaintext, ByteArray(0))

    assertEquals(
      "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
        "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662898015ad" +
        "b094dac5d93471bdec1a502270e3cc6c",
      sealed.toHex(),
    )
    assertTrue(AesGcm.open(key, nonce, sealed, ByteArray(0))!!.contentEquals(plaintext))
  }

  @Test
  fun gcmMatchesTestCase16WithAssociatedDataAndAPartialBlock() {
    val key = hexToBytes("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
    val nonce = hexToBytes("cafebabefacedbaddecaf888")
    // 60 bytes — deliberately not a multiple of 16, so the final GHASH block is zero-padded.
    val plaintext =
      hexToBytes(
        "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
          "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39"
      )
    // 20 bytes — also not block-aligned, which pads on the associated-data side too.
    val aad = hexToBytes("feedfacedeadbeeffeedfacedeadbeefabaddad2")

    val sealed = AesGcm.seal(key, nonce, plaintext, aad)

    assertEquals(
      "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
        "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" +
        "76fc6ece0f4e1768cddf8853bb2d551b",
      sealed.toHex(),
    )
    assertTrue(AesGcm.open(key, nonce, sealed, aad)!!.contentEquals(plaintext))
  }

  @Test
  fun gcmRefusesToOpenWhatItCannotAuthenticate() {
    val key = ByteArray(32) { it.toByte() }
    val nonce = ByteArray(12) { (it + 1).toByte() }
    val plaintext = "a cached verdict".encodeToByteArray()
    val aad = "associated".encodeToByteArray()
    val sealed = AesGcm.seal(key, nonce, plaintext, aad)

    // Every one of these is a distinct real-world failure, and every one must be indistinguishable
    // from the others at the API: null, with no hint about which check failed. Reporting "bad tag"
    // separately from "bad associated data" is how a decryption oracle starts.
    assertNull(AesGcm.open(ByteArray(32), nonce, sealed, aad), "wrong key")
    assertNull(AesGcm.open(key, ByteArray(12), sealed, aad), "wrong nonce")
    assertNull(AesGcm.open(key, nonce, sealed, "different".encodeToByteArray()), "wrong AAD")
    assertNull(AesGcm.open(key, nonce, sealed.copyOf(sealed.size - 1), aad), "truncated")
    assertNull(AesGcm.open(key, nonce, ByteArray(8), aad), "shorter than a tag")

    val flipped = sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    assertNull(AesGcm.open(key, nonce, flipped, aad), "one flipped ciphertext bit")

    val flippedTag =
      sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
    assertNull(AesGcm.open(key, nonce, flippedTag, aad), "one flipped tag bit")
  }

  @Test
  fun gcmRejectsANonceOfTheWrongLength() {
    assertFailsWith<IllegalArgumentException> {
      AesGcm.seal(ByteArray(32), ByteArray(16), ByteArray(4), ByteArray(0))
    }
  }

  // ------------------------------------------------------------- envelope ----

  @Test
  fun theEnvelopeCarriesItsVersionAndNonceAndRoundTrips() {
    val cipher = AesGcmCacheCipher(TestDataKey)
    val nonce = cacheNonce(counter = 42)
    val aad = cacheAssociatedData("cafe", schemaVersion = 1)
    val plaintext = """{"riskTier":"HIGH"}""".encodeToByteArray()

    val envelope = cipher.seal(plaintext, nonce, aad)

    assertEquals(AesGcmCacheCipher.ENVELOPE_VERSION, envelope[0].toInt())
    assertTrue(envelope.copyOfRange(1, 13).contentEquals(nonce))
    assertEquals(1 + 12 + plaintext.size + 16, envelope.size)
    assertTrue(cipher.open(envelope, aad)!!.contentEquals(plaintext))
  }

  @Test
  fun anEnvelopeFromAnotherRowDoesNotOpen() {
    // The swap attack this cache is built to survive. Someone with write access to the database
    // file copies the sealed `NONE` record of a clean number over the `HIGH` record of a known
    // fraudster. Without the cache key in the associated data, the tag would still verify and the
    // SDK would confidently report "no evidence found" for the fraudster.
    val cipher = AesGcmCacheCipher(TestDataKey)
    val cleanRowKey = "0f".repeat(32)
    val scammerRowKey = "a1".repeat(32)

    val cleanRecord =
      cipher.seal(
        """{"riskTier":"NONE"}""".encodeToByteArray(),
        cacheNonce(1),
        cacheAssociatedData(cleanRowKey, schemaVersion = 1),
      )

    assertNull(cipher.open(cleanRecord, cacheAssociatedData(scammerRowKey, schemaVersion = 1)))
    // …and it still opens where it belongs, so the binding is not simply breaking everything.
    assertTrue(
      cipher
        .open(cleanRecord, cacheAssociatedData(cleanRowKey, schemaVersion = 1))!!
        .decodeToString() == """{"riskTier":"NONE"}"""
    )
  }

  @Test
  fun aRecordFromAnotherSchemaVersionDoesNotOpen() {
    val cipher = AesGcmCacheCipher(TestDataKey)
    val key = "b2".repeat(32)
    val record =
      cipher.seal("payload".encodeToByteArray(), cacheNonce(1), cacheAssociatedData(key, 1))

    assertNull(cipher.open(record, cacheAssociatedData(key, 2)))
  }

  @Test
  fun anEnvelopeVersionThisBuildDoesNotKnowIsAMissRatherThanACrash() {
    val cipher = AesGcmCacheCipher(TestDataKey)
    val aad = cacheAssociatedData("c3".repeat(32), 1)
    val envelope = cipher.seal("payload".encodeToByteArray(), cacheNonce(1), aad)

    val fromTheFuture = envelope.copyOf().also { it[0] = 99 }

    assertNull(cipher.open(fromTheFuture, aad))
  }

  @Test
  fun theNonceIsTheCounterInBigEndianAfterFourZeroBytes() {
    assertEquals("000000000000000000000001", cacheNonce(1).toHex())
    assertEquals("00000000000000000000ffff", cacheNonce(65_535).toHex())
    assertEquals("000000007fffffffffffffff", cacheNonce(Long.MAX_VALUE).toHex())
    // Distinct counters must give distinct nonces — the whole safety argument rests on it.
    assertNotEquals(cacheNonce(1).toHex(), cacheNonce(2).toHex())
  }

  @Test
  fun aCacheDataKeyDoesNotPrintItself() {
    val printed = CacheDataKey("key-1", TestDataKey).toString()
    assertTrue(printed.contains("key-1"))
    assertTrue(printed.contains("redacted"))
    assertTrue(!printed.contains(TestDataKey.toHex()))
  }

  @Test
  fun aCacheDataKeyCannotBeMutatedThroughTheArrayItWasGiven() {
    val source = TestDataKey.copyOf()
    val key = CacheDataKey("key-1", source)
    source.wipe()
    assertEquals(TestDataKey.toHex(), key.bytes.toHex())

    // …and the getter hands out a copy, so a caller cannot zero the stored key either.
    key.bytes.wipe()
    assertEquals(TestDataKey.toHex(), key.bytes.toHex())
  }

  @Test
  fun constantTimeEqualsAgreesWithContentEquals() {
    val a = ByteArray(16) { it.toByte() }
    assertTrue(constantTimeEquals(a, a.copyOf()))
    assertTrue(!constantTimeEquals(a, a.copyOf().also { it[15] = 99 }))
    assertTrue(!constantTimeEquals(a, ByteArray(15)))
  }
}
