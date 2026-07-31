package id.tracive.sdk.cache

/*
 * AES and AES-GCM (FIPS 197, NIST SP 800-38D) in common Kotlin.
 *
 * Kotlin Multiplatform has no common crypto API, so the alternatives were an expect/actual pair
 * over `javax.crypto` and `CryptoKit`, or one implementation compiled for both. This SDK takes the
 * second, for three reasons:
 *
 *  1. A cache record written on one platform must decrypt byte-for-byte identically on the other.
 *     Two implementations of an envelope format are two chances to disagree about padding, tag
 *     placement or associated-data ordering, and the disagreement shows up as unreadable records
 *     rather than as a failing test.
 *  2. The whole cache layer stays testable in `commonTest` with no device, emulator or simulator.
 *  3. The platform seam that genuinely needs hardware backing is the *data key*, not the cipher —
 *     see `CacheDataKeyStore`. Keeping the cipher in common code narrows the platform surface to
 *     the one thing only the platform can do.
 *
 * Scope and limits, stated plainly:
 *  - This is a software implementation with table lookups. It is not constant-time against an
 *    attacker who can measure this process's cache timing. That attacker is already inside the app
 *    sandbox, where the plaintext (a risk tier and some counts, already known to the user) and the
 *    data key are both reachable by easier means. Do not reuse this code for anything where the
 *    plaintext is a secret the local user must not learn.
 *  - Nonces are supplied by the caller and must never repeat for one key. `DefaultCheckCache`
 *    guarantees that with a durable counter; see `CacheNonce`.
 *
 * `CacheCryptoTest` pins this against the FIPS 197 known-answer test and the NIST GCM test cases.
 */

/** Block size of AES, in bytes. */
private const val BLOCK_SIZE = 16

/** Round constants for the key schedule; ten is enough for every AES key length. */
private val RCON = intArrayOf(0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36)

/**
 * The AES S-box, derived rather than transcribed.
 *
 * A 256-entry table typed by hand is 256 chances to introduce a single-byte error that no
 * round-trip test can catch — encrypt and decrypt would agree with each other and disagree with
 * every other AES implementation on earth. Computing it from its definition (the multiplicative
 * inverse in GF(2⁸) followed by the FIPS 197 §5.1.1 affine transform) makes it self-checking.
 */
private val SBOX: IntArray by lazy {
  val exp = IntArray(256)
  val log = IntArray(256)
  var x = 1
  for (i in 0 until 256) {
    exp[i] = x
    log[x] = i
    // x *= 3 in GF(2^8) with the AES reduction polynomial 0x11B.
    val doubled = ((x shl 1) and 0xFF) xor (if (x and 0x80 != 0) 0x1B else 0)
    x = doubled xor x
  }
  IntArray(256) { i ->
    val inverse = if (i == 0) 0 else exp[255 - log[i]]
    var b = inverse
    var result = inverse
    for (round in 0 until 4) {
      b = ((b shl 1) or (b ushr 7)) and 0xFF
      result = result xor b
    }
    result xor 0x63
  }
}

/** Multiplication by x (0x02) in GF(2⁸) with the AES reduction polynomial. */
private fun xtime(a: Int): Int = ((a shl 1) and 0xFF) xor (if (a and 0x80 != 0) 0x1B else 0)

/**
 * An AES block cipher instance with an expanded key schedule.
 *
 * Only the forward direction is implemented: GCM is a counter mode, so decryption also uses
 * `encryptBlock`. There is no `decryptBlock` anywhere in this SDK, and there does not need to be.
 */
internal class Aes(key: ByteArray) {

  private val rounds: Int
  private val roundKeys: IntArray

  init {
    require(key.size == 16 || key.size == 24 || key.size == 32) {
      "An AES key must be 16, 24 or 32 bytes."
    }
    val nk = key.size / 4
    rounds = nk + 6
    val wordCount = 4 * (rounds + 1)
    val w = IntArray(wordCount)
    for (i in 0 until nk) {
      w[i] =
        ((key[4 * i].toInt() and 0xFF) shl 24) or
          ((key[4 * i + 1].toInt() and 0xFF) shl 16) or
          ((key[4 * i + 2].toInt() and 0xFF) shl 8) or
          (key[4 * i + 3].toInt() and 0xFF)
    }
    for (i in nk until wordCount) {
      var temp = w[i - 1]
      if (i % nk == 0) {
        temp = subWord(rotWord(temp)) xor (RCON[i / nk - 1] shl 24)
      } else if (nk > 6 && i % nk == 4) {
        temp = subWord(temp)
      }
      w[i] = w[i - nk] xor temp
    }
    roundKeys = w
  }

  /** Encrypts the 16 bytes at [inOffset] of [input] into [outOffset] of [output]. */
  fun encryptBlock(input: ByteArray, inOffset: Int, output: ByteArray, outOffset: Int) {
    val state = IntArray(BLOCK_SIZE) { input[inOffset + it].toInt() and 0xFF }

    addRoundKey(state, 0)
    for (round in 1 until rounds) {
      subBytes(state)
      shiftRows(state)
      mixColumns(state)
      addRoundKey(state, round)
    }
    subBytes(state)
    shiftRows(state)
    addRoundKey(state, rounds)

    for (i in 0 until BLOCK_SIZE) {
      output[outOffset + i] = state[i].toByte()
    }
  }

  private fun addRoundKey(state: IntArray, round: Int) {
    for (c in 0 until 4) {
      val word = roundKeys[round * 4 + c]
      state[4 * c] = state[4 * c] xor ((word ushr 24) and 0xFF)
      state[4 * c + 1] = state[4 * c + 1] xor ((word ushr 16) and 0xFF)
      state[4 * c + 2] = state[4 * c + 2] xor ((word ushr 8) and 0xFF)
      state[4 * c + 3] = state[4 * c + 3] xor (word and 0xFF)
    }
  }

  private fun subBytes(state: IntArray) {
    for (i in 0 until BLOCK_SIZE) state[i] = SBOX[state[i]]
  }

  /** Row r of the (column-major) state is bytes r, r+4, r+8, r+12, rotated left by r. */
  private fun shiftRows(state: IntArray) {
    for (row in 1 until 4) {
      val a = state[row]
      val b = state[row + 4]
      val c = state[row + 8]
      val d = state[row + 12]
      when (row) {
        1 -> {
          state[1] = b
          state[5] = c
          state[9] = d
          state[13] = a
        }
        2 -> {
          state[2] = c
          state[6] = d
          state[10] = a
          state[14] = b
        }
        else -> {
          state[3] = d
          state[7] = a
          state[11] = b
          state[15] = c
        }
      }
    }
  }

  private fun mixColumns(state: IntArray) {
    for (c in 0 until 4) {
      val a0 = state[4 * c]
      val a1 = state[4 * c + 1]
      val a2 = state[4 * c + 2]
      val a3 = state[4 * c + 3]
      state[4 * c] = xtime(a0) xor (xtime(a1) xor a1) xor a2 xor a3
      state[4 * c + 1] = a0 xor xtime(a1) xor (xtime(a2) xor a2) xor a3
      state[4 * c + 2] = a0 xor a1 xor xtime(a2) xor (xtime(a3) xor a3)
      state[4 * c + 3] = (xtime(a0) xor a0) xor a1 xor a2 xor xtime(a3)
    }
  }

  private fun subWord(word: Int): Int =
    (SBOX[(word ushr 24) and 0xFF] shl 24) or
      (SBOX[(word ushr 16) and 0xFF] shl 16) or
      (SBOX[(word ushr 8) and 0xFF] shl 8) or
      SBOX[word and 0xFF]

  private fun rotWord(word: Int): Int = (word shl 8) or (word ushr 24)
}

/**
 * AES-GCM with a 96-bit nonce and a 128-bit tag — the only parameter set this SDK uses, and the
 * only one NIST SP 800-38D recommends for new designs.
 */
internal object AesGcm {

  /** Nonce length in bytes. 96 bits is the length GCM handles without hashing the nonce first. */
  const val NONCE_SIZE: Int = 12

  /** Authentication tag length in bytes. Never truncate it: a short tag is forgeable. */
  const val TAG_SIZE: Int = 16

  /**
   * Encrypts [plaintext] and returns `ciphertext || tag`.
   *
   * @param nonce exactly [NONCE_SIZE] bytes, and never repeated for this [key]. Repeating one
   *   reveals the GHASH subkey, which lets an attacker forge tags for every other record — GCM
   *   fails catastrophically here, not gracefully.
   * @param associatedData authenticated but not encrypted. The cache binds each record to its own
   *   cache key this way, so a row copied over another row fails to open.
   */
  fun seal(
    key: ByteArray,
    nonce: ByteArray,
    plaintext: ByteArray,
    associatedData: ByteArray,
  ): ByteArray {
    require(nonce.size == NONCE_SIZE) { "AES-GCM needs a $NONCE_SIZE-byte nonce." }
    val aes = Aes(key)
    val h = ByteArray(BLOCK_SIZE)
    aes.encryptBlock(ByteArray(BLOCK_SIZE), 0, h, 0)

    val j0 = ByteArray(BLOCK_SIZE)
    nonce.copyInto(j0)
    j0[15] = 1

    val out = ByteArray(plaintext.size + TAG_SIZE)
    gctr(aes, incrementCounter(j0), plaintext, out)

    val tag = computeTag(aes, h, j0, associatedData, out, plaintext.size)
    tag.copyInto(out, plaintext.size)
    return out
  }

  /**
   * Verifies and decrypts `ciphertext || tag`, or returns null.
   *
   * Null rather than an exception, and null for every failure mode — wrong key, wrong associated
   * data, truncated input, flipped bit. A caller cannot do anything different for one than for
   * another, and distinguishing them in a message is how padding-oracle-shaped bugs start. The
   * cache treats null as a miss and deletes the row.
   */
  fun open(
    key: ByteArray,
    nonce: ByteArray,
    ciphertextAndTag: ByteArray,
    associatedData: ByteArray,
  ): ByteArray? {
    if (nonce.size != NONCE_SIZE) return null
    if (ciphertextAndTag.size < TAG_SIZE) return null
    val cipherLength = ciphertextAndTag.size - TAG_SIZE

    val aes = Aes(key)
    val h = ByteArray(BLOCK_SIZE)
    aes.encryptBlock(ByteArray(BLOCK_SIZE), 0, h, 0)

    val j0 = ByteArray(BLOCK_SIZE)
    nonce.copyInto(j0)
    j0[15] = 1

    val expected = computeTag(aes, h, j0, associatedData, ciphertextAndTag, cipherLength)
    val actual = ciphertextAndTag.copyOfRange(cipherLength, ciphertextAndTag.size)
    // Verify before decrypting: never hand a caller bytes that failed authentication.
    if (!constantTimeEquals(expected, actual)) return null

    val plaintext = ByteArray(cipherLength)
    gctr(aes, incrementCounter(j0), ciphertextAndTag.copyOfRange(0, cipherLength), plaintext)
    return plaintext
  }

  /** GCTR: CTR-mode keystream from [counter], XORed over [input] into the front of [output]. */
  private fun gctr(aes: Aes, counter: ByteArray, input: ByteArray, output: ByteArray) {
    val block = ByteArray(BLOCK_SIZE)
    val keystream = ByteArray(BLOCK_SIZE)
    counter.copyInto(block)
    var offset = 0
    while (offset < input.size) {
      aes.encryptBlock(block, 0, keystream, 0)
      val remaining = minOf(BLOCK_SIZE, input.size - offset)
      for (i in 0 until remaining) {
        output[offset + i] = (input[offset + i].toInt() xor keystream[i].toInt()).toByte()
      }
      offset += remaining
      incrementCounterInPlace(block)
    }
  }

  /**
   * GHASH over `A ‖ pad ‖ C ‖ pad ‖ len(A)₆₄ ‖ len(C)₆₄`, then XOR with `E(K, J0)`.
   *
   * [cipher] is read for its first [cipherLength] bytes only, so the caller can pass the combined
   * `ciphertext || tag` buffer without copying it.
   */
  private fun computeTag(
    aes: Aes,
    h: ByteArray,
    j0: ByteArray,
    associatedData: ByteArray,
    cipher: ByteArray,
    cipherLength: Int,
  ): ByteArray {
    val s = ByteArray(BLOCK_SIZE)
    ghashUpdate(s, h, associatedData, associatedData.size)
    ghashUpdate(s, h, cipher, cipherLength)

    val lengths = ByteArray(BLOCK_SIZE)
    writeBigEndianLong(lengths, 0, associatedData.size.toLong() * 8L)
    writeBigEndianLong(lengths, 8, cipherLength.toLong() * 8L)
    xorInto(s, lengths, 0, BLOCK_SIZE)
    ghashMultiply(s, h)

    val tag = ByteArray(BLOCK_SIZE)
    aes.encryptBlock(j0, 0, tag, 0)
    xorInto(tag, s, 0, BLOCK_SIZE)
    return tag
  }

  /** Folds [length] bytes of [data] into the GHASH accumulator [s], zero-padding the last block. */
  private fun ghashUpdate(s: ByteArray, h: ByteArray, data: ByteArray, length: Int) {
    var offset = 0
    while (offset < length) {
      val remaining = minOf(BLOCK_SIZE, length - offset)
      xorInto(s, data, offset, remaining)
      ghashMultiply(s, h)
      offset += remaining
    }
  }

  /**
   * `s := s · h` in GF(2¹²⁸) with GCM's bit ordering (NIST SP 800-38D Algorithm 1).
   *
   * The shift is toward the least significant *bit position as GCM numbers them*, which is why the
   * reduction polynomial appears as 0xE1 in the first byte rather than 0x87 in the last.
   */
  private fun ghashMultiply(s: ByteArray, h: ByteArray) {
    val z = ByteArray(BLOCK_SIZE)
    val v = h.copyOf()
    for (i in 0 until 128) {
      val bit = (s[i / 8].toInt() ushr (7 - (i % 8))) and 1
      if (bit == 1) {
        for (j in 0 until BLOCK_SIZE) z[j] = (z[j].toInt() xor v[j].toInt()).toByte()
      }
      val lsb = v[BLOCK_SIZE - 1].toInt() and 1
      shiftRight(v)
      if (lsb == 1) v[0] = (v[0].toInt() xor 0xE1).toByte()
    }
    z.copyInto(s)
  }

  /** Shifts a 128-bit big-endian value right by one bit. */
  private fun shiftRight(v: ByteArray) {
    var carry = 0
    for (i in 0 until BLOCK_SIZE) {
      val current = v[i].toInt() and 0xFF
      v[i] = ((current ushr 1) or carry).toByte()
      carry = (current and 1) shl 7
    }
  }

  private fun xorInto(target: ByteArray, source: ByteArray, sourceOffset: Int, length: Int) {
    for (i in 0 until length) {
      target[i] = (target[i].toInt() xor source[sourceOffset + i].toInt()).toByte()
    }
  }

  /** inc32: the last four bytes are a big-endian counter that wraps within itself. */
  private fun incrementCounter(block: ByteArray): ByteArray {
    val next = block.copyOf()
    incrementCounterInPlace(next)
    return next
  }

  private fun incrementCounterInPlace(block: ByteArray) {
    for (i in 15 downTo 12) {
      val incremented = ((block[i].toInt() and 0xFF) + 1) and 0xFF
      block[i] = incremented.toByte()
      if (incremented != 0) return
    }
  }

  private fun writeBigEndianLong(target: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 8) {
      target[offset + i] = ((value ushr (8 * (7 - i))) and 0xFF).toByte()
    }
  }
}
