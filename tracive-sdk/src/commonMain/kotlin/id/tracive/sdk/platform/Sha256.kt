package id.tracive.sdk.platform

/**
 * SHA-256 (FIPS 180-4), implemented in common Kotlin rather than delegated to each platform.
 *
 * Two things depend on this digest agreeing byte for byte everywhere: the PKCE `S256` challenge,
 * which the authorization server re-derives and compares, and the cache key, which is
 * `SHA-256(searchQueryIndex)` so that the raw identifier is never written to disk. A per-platform
 * digest (JCA on Android, CommonCrypto on iOS) would give the same answer in practice, but it also
 * gives every platform its own way to be unavailable, mis-linked or subtly different — and a wrong
 * challenge fails authorization with an error that says nothing about hashing.
 *
 * Hashing is not a secret-dependent operation in the sense that matters for timing attacks here:
 * the inputs are a PKCE verifier the client itself generated and an identifier the caller already
 * holds. Correctness is what matters, and it is pinned by the RFC 7636 appendix B vector and the
 * standard NIST vectors in `PkceGeneratorTest`.
 */
internal object Sha256 {

  private val ROUND_CONSTANTS =
    intArrayOf(
      0x428a2f98.toInt(),
      0x71374491.toInt(),
      0xb5c0fbcf.toInt(),
      0xe9b5dba5.toInt(),
      0x3956c25b.toInt(),
      0x59f111f1.toInt(),
      0x923f82a4.toInt(),
      0xab1c5ed5.toInt(),
      0xd807aa98.toInt(),
      0x12835b01.toInt(),
      0x243185be.toInt(),
      0x550c7dc3.toInt(),
      0x72be5d74.toInt(),
      0x80deb1fe.toInt(),
      0x9bdc06a7.toInt(),
      0xc19bf174.toInt(),
      0xe49b69c1.toInt(),
      0xefbe4786.toInt(),
      0x0fc19dc6.toInt(),
      0x240ca1cc.toInt(),
      0x2de92c6f.toInt(),
      0x4a7484aa.toInt(),
      0x5cb0a9dc.toInt(),
      0x76f988da.toInt(),
      0x983e5152.toInt(),
      0xa831c66d.toInt(),
      0xb00327c8.toInt(),
      0xbf597fc7.toInt(),
      0xc6e00bf3.toInt(),
      0xd5a79147.toInt(),
      0x06ca6351.toInt(),
      0x14292967.toInt(),
      0x27b70a85.toInt(),
      0x2e1b2138.toInt(),
      0x4d2c6dfc.toInt(),
      0x53380d13.toInt(),
      0x650a7354.toInt(),
      0x766a0abb.toInt(),
      0x81c2c92e.toInt(),
      0x92722c85.toInt(),
      0xa2bfe8a1.toInt(),
      0xa81a664b.toInt(),
      0xc24b8b70.toInt(),
      0xc76c51a3.toInt(),
      0xd192e819.toInt(),
      0xd6990624.toInt(),
      0xf40e3585.toInt(),
      0x106aa070.toInt(),
      0x19a4c116.toInt(),
      0x1e376c08.toInt(),
      0x2748774c.toInt(),
      0x34b0bcb5.toInt(),
      0x391c0cb3.toInt(),
      0x4ed8aa4a.toInt(),
      0x5b9cca4f.toInt(),
      0x682e6ff3.toInt(),
      0x748f82ee.toInt(),
      0x78a5636f.toInt(),
      0x84c87814.toInt(),
      0x8cc70208.toInt(),
      0x90befffa.toInt(),
      0xa4506ceb.toInt(),
      0xbef9a3f7.toInt(),
      0xc67178f2.toInt(),
    )

  /** The 32-byte digest of [message]. */
  fun digest(message: ByteArray): ByteArray {
    val state =
      intArrayOf(
        0x6a09e667.toInt(),
        0xbb67ae85.toInt(),
        0x3c6ef372.toInt(),
        0xa54ff53a.toInt(),
        0x510e527f.toInt(),
        0x9b05688c.toInt(),
        0x1f83d9ab.toInt(),
        0x5be0cd19.toInt(),
      )

    // Padding: the 0x80 marker, zeroes, then the message length in bits as a big-endian 64-bit
    // value, rounded up to a whole number of 64-byte blocks.
    val paddedSize = ((message.size + 9 + 63) / 64) * 64
    val padded = ByteArray(paddedSize)
    message.copyInto(padded)
    padded[message.size] = 0x80.toByte()
    val bitLength = message.size.toLong() * 8L
    for (index in 0 until 8) {
      padded[paddedSize - 1 - index] = ((bitLength ushr (8 * index)) and 0xFF).toByte()
    }

    val schedule = IntArray(64)
    var blockStart = 0
    while (blockStart < paddedSize) {
      for (index in 0 until 16) {
        val at = blockStart + index * 4
        schedule[index] =
          ((padded[at].toInt() and 0xFF) shl 24) or
            ((padded[at + 1].toInt() and 0xFF) shl 16) or
            ((padded[at + 2].toInt() and 0xFF) shl 8) or
            (padded[at + 3].toInt() and 0xFF)
      }
      for (index in 16 until 64) {
        val previous = schedule[index - 15]
        val recent = schedule[index - 2]
        val sigma0 = previous.rotateRightBy(7) xor previous.rotateRightBy(18) xor (previous ushr 3)
        val sigma1 = recent.rotateRightBy(17) xor recent.rotateRightBy(19) xor (recent ushr 10)
        schedule[index] = schedule[index - 16] + sigma0 + schedule[index - 7] + sigma1
      }

      var a = state[0]
      var b = state[1]
      var c = state[2]
      var d = state[3]
      var e = state[4]
      var f = state[5]
      var g = state[6]
      var h = state[7]

      for (index in 0 until 64) {
        val bigSigma1 = e.rotateRightBy(6) xor e.rotateRightBy(11) xor e.rotateRightBy(25)
        val choose = (e and f) xor (e.inv() and g)
        val temp1 = h + bigSigma1 + choose + ROUND_CONSTANTS[index] + schedule[index]
        val bigSigma0 = a.rotateRightBy(2) xor a.rotateRightBy(13) xor a.rotateRightBy(22)
        val majority = (a and b) xor (a and c) xor (b and c)
        val temp2 = bigSigma0 + majority

        h = g
        g = f
        f = e
        e = d + temp1
        d = c
        c = b
        b = a
        a = temp1 + temp2
      }

      state[0] += a
      state[1] += b
      state[2] += c
      state[3] += d
      state[4] += e
      state[5] += f
      state[6] += g
      state[7] += h

      blockStart += 64
    }

    return ByteArray(32) { index ->
      ((state[index / 4] ushr (24 - 8 * (index % 4))) and 0xFF).toByte()
    }
  }

  private fun Int.rotateRightBy(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))
}

/** Lower-case hex, the form the cache uses for its keys. */
internal fun ByteArray.toLowerHex(): String {
  val digits = "0123456789abcdef"
  val out = StringBuilder(size * 2)
  for (byte in this) {
    val value = byte.toInt() and 0xFF
    out.append(digits[value ushr 4])
    out.append(digits[value and 0x0F])
  }
  return out.toString()
}
