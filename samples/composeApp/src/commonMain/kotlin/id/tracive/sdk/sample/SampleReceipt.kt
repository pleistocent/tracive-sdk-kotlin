package id.tracive.sdk.sample

import kotlinx.datetime.LocalDate

/**
 * A made-up transfer receipt, as one PNG and one small PDF, built in common code so the sample has
 * real files to hand to `openReportForm` on both platforms.
 *
 * TODO(partner): your own receipt screenshot and PDF for the transaction on screen.
 */
internal object SampleReceipt {

  fun png(): ByteArray {
    val width = 240
    val height = 320
    val rows = ByteArray(height * (1 + width * 3))
    for (y in 0 until height) {
      val rowStart = y * (1 + width * 3)
      // Filter byte 0 (None) leads every scanline.
      for (x in 0 until width) {
        val (r, g, b) = receiptPixel(x, y)
        val at = rowStart + 1 + x * 3
        rows[at] = r.toByte()
        rows[at + 1] = g.toByte()
        rows[at + 2] = b.toByte()
      }
    }
    val header =
      ByteArray(13).also {
        it.putInt(0, width)
        it.putInt(4, height)
        it[8] = 8 // bit depth
        it[9] = 2 // truecolour RGB
      }
    return PNG_SIGNATURE + chunk("IHDR", header) + chunk("IDAT", zlibStored(rows)) + chunk("IEND")
  }

  fun pdf(date: LocalDate): ByteArray {
    val lines =
      listOf(
        "Transfer receipt - sample",
        "Amount: ${MockTransaction.AMOUNT}",
        "Date: $date",
        "To: ${MockTransaction.BANK} ${MockTransaction.ACCOUNT}",
        "Reference: ${MockTransaction.REFERENCE}",
      )
    val text = buildString {
      append("BT /F1 14 Tf 40 780 Td 18 TL\n")
      lines.forEach { append("(").append(it).append(") '\n") }
      append("ET")
    }
    val objects =
      listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
          "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        "<< /Length ${text.length} >>\nstream\n$text\nendstream",
      )
    val out = StringBuilder("%PDF-1.4\n")
    val offsets = objects.mapIndexed { index, body ->
      out.length.also { out.append("${index + 1} 0 obj\n$body\nendobj\n") }
    }
    val xref = out.length
    out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
    offsets.forEach { out.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
    out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    return out.toString().encodeToByteArray()
  }

  /** A white slip with a coloured header, an amount bar and grey lines standing in for text. */
  private fun receiptPixel(x: Int, y: Int): Triple<Int, Int, Int> =
    when {
      y < 56 -> Triple(0x0E, 0x7C, 0x86)
      y in 88..108 && x in 24..180 -> Triple(0x22, 0x22, 0x22)
      (y - 140) % 28 in 0..7 && y in 140..290 && x in 24..(216 - (y % 3) * 30) ->
        Triple(0xB0, 0xB0, 0xB0)
      else -> Triple(0xFF, 0xFF, 0xFF)
    }

  private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

  private fun chunk(type: String, data: ByteArray = ByteArray(0)): ByteArray {
    val typeBytes = type.encodeToByteArray()
    val out = ByteArray(12 + data.size)
    out.putInt(0, data.size)
    typeBytes.copyInto(out, 4)
    data.copyInto(out, 8)
    out.putInt(8 + data.size, crc32(typeBytes + data).toInt())
    return out
  }

  /** zlib with stored (uncompressed) deflate blocks: valid, and needs no compressor. */
  private fun zlibStored(data: ByteArray): ByteArray {
    val blocks = data.toList().chunked(65_535)
    val out = ArrayList<Byte>(data.size + blocks.size * 5 + 6)
    out += 0x78
    out += 0x01
    blocks.forEachIndexed { index, block ->
      out += (if (index == blocks.lastIndex) 1 else 0).toByte()
      out += (block.size and 0xFF).toByte()
      out += (block.size shr 8).toByte()
      out += (block.size.inv() and 0xFF).toByte()
      out += ((block.size.inv() shr 8) and 0xFF).toByte()
      out += block
    }
    var a = 1L
    var b = 0L
    for (byte in data) {
      a = (a + (byte.toInt() and 0xFF)) % 65_521
      b = (b + a) % 65_521
    }
    val adler = ((b shl 16) or a).toInt()
    return out.toByteArray() + ByteArray(4).also { it.putInt(0, adler) }
  }

  private fun crc32(data: ByteArray): Long {
    var crc = 0xFFFFFFFFL
    for (byte in data) {
      crc = crc xor (byte.toLong() and 0xFF)
      repeat(8) { crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1 }
    }
    return crc xor 0xFFFFFFFFL
  }

  private fun ByteArray.putInt(at: Int, value: Int) {
    this[at] = (value ushr 24).toByte()
    this[at + 1] = (value ushr 16).toByte()
    this[at + 2] = (value ushr 8).toByte()
    this[at + 3] = value.toByte()
  }
}
