package id.tracive.sdk.cache

/**
 * Seals and opens one cache record.
 *
 * Split out from the store so the encryption is testable on its own, and so a host with a hardware
 * cipher requirement can substitute one without touching the storage logic. The default is
 * [AesGcmCacheCipher].
 *
 * Implementations must be safe to call from several coroutines at once.
 */
interface CacheCipher {

  /**
   * Encrypts [plaintext] into a self-describing envelope.
   *
   * @param nonce exactly [AesGcm.NONCE_SIZE] bytes. **It must never repeat for one key**; the
   *   caller owns that guarantee (see `CacheNonce`).
   * @param associatedData authenticated but not encrypted, so a record cannot be moved to another
   *   row or replayed under another schema version.
   */
  fun seal(plaintext: ByteArray, nonce: ByteArray, associatedData: ByteArray): ByteArray

  /**
   * Opens an envelope produced by [seal], or returns null if it cannot be authenticated.
   *
   * Null covers every failure: wrong key, wrong associated data, an unknown envelope version, a
   * truncated blob, a flipped bit. The caller cannot act differently on any of them, and reporting
   * which one failed would be an oracle. The cache treats null as a miss and deletes the row.
   */
  fun open(envelope: ByteArray, associatedData: ByteArray): ByteArray?
}

/**
 * AES-256-GCM, with the layout
 *
 * ```
 * byte  0        envelope version (currently 1)
 * bytes 1..12    96-bit nonce
 * bytes 13..     AES-256-GCM ciphertext, followed by the 128-bit tag
 * ```
 *
 * The version byte is what makes changing the layout later a non-event: an old record with an
 * unrecognised version opens as null, which the cache already treats as a miss.
 *
 * The key is held in memory for the lifetime of the cache instance, which is unavoidable — a
 * software cipher has to see its key. What the platform key store buys is that the key is not *at
 * rest* anywhere the cache controls: it lives in the Keystore/Keychain and is fetched once at open.
 */
class AesGcmCacheCipher(key: ByteArray) : CacheCipher {

  private val key: ByteArray = key.copyOf()

  init {
    require(this.key.size == CacheDataKey.KEY_SIZE) {
      "AesGcmCacheCipher needs a ${CacheDataKey.KEY_SIZE}-byte key."
    }
  }

  constructor(key: CacheDataKey) : this(key.bytes)

  override fun seal(plaintext: ByteArray, nonce: ByteArray, associatedData: ByteArray): ByteArray {
    require(nonce.size == AesGcm.NONCE_SIZE) {
      "A cache record nonce must be ${AesGcm.NONCE_SIZE} bytes."
    }
    val body = AesGcm.seal(key, nonce, plaintext, associatedData)
    val envelope = ByteArray(1 + AesGcm.NONCE_SIZE + body.size)
    envelope[0] = ENVELOPE_VERSION.toByte()
    nonce.copyInto(envelope, 1)
    body.copyInto(envelope, 1 + AesGcm.NONCE_SIZE)
    return envelope
  }

  override fun open(envelope: ByteArray, associatedData: ByteArray): ByteArray? {
    if (envelope.size < 1 + AesGcm.NONCE_SIZE + AesGcm.TAG_SIZE) return null
    if (envelope[0].toInt() != ENVELOPE_VERSION) return null
    val nonce = envelope.copyOfRange(1, 1 + AesGcm.NONCE_SIZE)
    val body = envelope.copyOfRange(1 + AesGcm.NONCE_SIZE, envelope.size)
    return AesGcm.open(key, nonce, body, associatedData)
  }

  companion object {
    /**
     * Bump when the envelope layout changes. Records written under an older version stop opening.
     */
    const val ENVELOPE_VERSION: Int = 1
  }
}

/**
 * The associated data every record is authenticated under: the schema version, the envelope
 * version, and the record's own cache key.
 *
 * Binding the cache key in is what stops a swap attack. Without it, someone with write access to
 * the database file could copy the `NONE` record of a clean number over the `HIGH` record of a
 * scammer's number: the ciphertext would still authenticate, and the cache would confidently return
 * "no evidence found" for a known fraudster. With the key bound in, the moved row fails to open and
 * degrades to a cache miss, which costs a credit and gets the right answer.
 */
internal fun cacheAssociatedData(cacheKey: String, schemaVersion: Int): ByteArray {
  val keyBytes = cacheKey.encodeToByteArray()
  val out = ByteArray(5 + keyBytes.size)
  out[0] = ((schemaVersion ushr 24) and 0xFF).toByte()
  out[1] = ((schemaVersion ushr 16) and 0xFF).toByte()
  out[2] = ((schemaVersion ushr 8) and 0xFF).toByte()
  out[3] = (schemaVersion and 0xFF).toByte()
  out[4] = AesGcmCacheCipher.ENVELOPE_VERSION.toByte()
  keyBytes.copyInto(out, 5)
  return out
}

/**
 * The per-record nonce: four zero bytes, then a big-endian 64-bit counter.
 *
 * This is the deterministic construction of NIST SP 800-38D §8.2.1, not a random one, and the
 * reason is that common Kotlin has no cryptographically secure RNG (see [CacheDataKeyStore]). GCM
 * needs its nonces to be *unique*, not unpredictable, so a counter is a legitimate construction —
 * provided the counter can never rewind. Two things make that hold:
 *
 * - the counter is incremented **in the database, inside the write transaction**, so a crash loses
 *   a value rather than replaying one; and
 * - a database that is recreated (wiped, restored, or invalidated by a schema bump) gets a **new
 *   data key**, so the counter restarting at zero pairs it with a key it has never been used with.
 *
 * At one write per record, 2⁶⁴ values is not a limit any device will reach.
 */
internal fun cacheNonce(counter: Long): ByteArray {
  val nonce = ByteArray(AesGcm.NONCE_SIZE)
  for (i in 0 until 8) {
    nonce[4 + i] = ((counter ushr (8 * (7 - i))) and 0xFF).toByte()
  }
  return nonce
}
