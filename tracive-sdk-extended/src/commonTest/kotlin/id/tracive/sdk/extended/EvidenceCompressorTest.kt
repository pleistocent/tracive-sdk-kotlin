package id.tracive.sdk.extended

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val PDF_HEAD = "%PDF-1.7".encodeToByteArray()

class EvidenceCompressorTest {

  private fun pdf(size: Int) = PDF_HEAD.copyOf(size)

  @Test
  fun avifSucceedingRunsNoOtherEncoder() = runTest {
    val ran = mutableListOf<String>()
    val compressor =
      EvidenceCompressor(
        avif = { _, _, _ ->
          ran += "avif"
          byteArrayOf(1)
        },
        fallbacks =
          listOf(
            FallbackEncoder("image/webp") { _, _, _ ->
              ran += "webp"
              byteArrayOf(2)
            }
          ),
      )

    val evidence = compressor.prepare(byteArrayOf(0), "image/png")

    assertEquals("image/avif", evidence.mimeType)
    assertEquals(listOf("avif"), ran)
  }

  @Test
  fun avifFailingTriesTheFallbacksInOrder() = runTest {
    val ran = mutableListOf<String>()
    val compressor =
      EvidenceCompressor(
        avif = { _, _, _ -> error("avifkit failed") },
        fallbacks =
          listOf(
            FallbackEncoder("image/webp") { _, _, _ ->
              ran += "webp"
              error("webp failed")
            },
            FallbackEncoder("image/jpeg") { _, _, _ ->
              ran += "jpeg"
              byteArrayOf(3)
            },
          ),
      )

    val evidence = compressor.prepare(byteArrayOf(0), "image/png")

    assertEquals("image/jpeg", evidence.mimeType)
    assertContentEquals(byteArrayOf(3), evidence.bytes)
    assertEquals(listOf("webp", "jpeg"), ran)
  }

  @Test
  fun aResultOverTheCapCountsAsAFailure() = runTest {
    val compressor =
      EvidenceCompressor(
        avif = { _, _, _ -> ByteArray(MAX_IMAGE_BYTES + 1) },
        fallbacks = listOf(FallbackEncoder("image/jpeg") { _, _, _ -> ByteArray(MAX_IMAGE_BYTES) }),
      )

    val evidence = compressor.prepare(byteArrayOf(0), "image/png")

    assertEquals("image/jpeg", evidence.mimeType)
    assertTrue(evidence.bytes.size <= MAX_IMAGE_BYTES)
  }

  @Test
  fun nothingWorkingIsUnreadable() = runTest {
    val compressor = EvidenceCompressor(avif = { _, _, _ -> error("no") }, fallbacks = emptyList())

    val failure =
      assertFailsWith<TraciveReportException> { compressor.prepare(byteArrayOf(0), "image/png") }

    assertEquals(ReportErrorCode.IMAGE_UNREADABLE, failure.code)
  }

  @Test
  fun aPdfOf1MiBIsRefusedAndOneByteLessPasses() = runTest {
    val compressor = EvidenceCompressor(avif = { _, _, _ -> error("no") }, fallbacks = emptyList())

    val failure =
      assertFailsWith<TraciveReportException> { compressor.prepare(pdf(PDF_LIMIT_BYTES), MIME_PDF) }
    assertEquals(ReportErrorCode.FILE_TOO_LARGE, failure.code)

    assertEquals(
      PDF_LIMIT_BYTES - 1,
      compressor.prepare(pdf(PDF_LIMIT_BYTES - 1), MIME_PDF).bytes.size,
    )
  }

  @Test
  fun aFileThatIsNotAPdfOrAnImageIsRefused() = runTest {
    val compressor = EvidenceCompressor(avif = { _, _, _ -> error("no") }, fallbacks = emptyList())

    val notPdf =
      assertFailsWith<TraciveReportException> { compressor.prepare(byteArrayOf(0, 1), MIME_PDF) }
    val text =
      assertFailsWith<TraciveReportException> { compressor.prepare(byteArrayOf(0), "text/plain") }

    assertEquals(ReportErrorCode.UNSUPPORTED_FILE, notPdf.code)
    assertEquals(ReportErrorCode.UNSUPPORTED_FILE, text.code)
  }
}
