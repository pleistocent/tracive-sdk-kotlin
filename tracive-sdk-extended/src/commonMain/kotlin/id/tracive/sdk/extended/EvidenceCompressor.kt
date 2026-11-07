package id.tracive.sdk.extended

import com.alfikri.rizky.avifkit.AvifConverter
import com.alfikri.rizky.avifkit.CompressionStrategy
import com.alfikri.rizky.avifkit.EncodingOptions
import com.alfikri.rizky.avifkit.ImageInput
import com.alfikri.rizky.avifkit.Priority
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal const val MAX_IMAGE_BYTES = 200 * 1024
internal const val MAX_SHORTEST_SIDE = 1024
internal const val PDF_LIMIT_BYTES = 1_048_576
internal const val MIME_PDF = "application/pdf"

/** Encodes an image or throws. A result over the limit counts as a failure. */
internal fun interface ImageEncoder {
  suspend fun encode(image: ByteArray, maxBytes: Int, maxShortestSide: Int): ByteArray
}

internal class FallbackEncoder(val mimeType: String, val encoder: ImageEncoder)

/** The non-AVIF encoders, in the order they are tried. */
internal expect fun platformFallbacks(): List<FallbackEncoder>

/**
 * AVIF always runs first. Every other format exists only for when avifkit fails, which the server
 * does not require: it accepts any of them. That is a product decision, not a contract.
 */
internal class EvidenceCompressor(
  private val avif: ImageEncoder = AvifKitEncoder,
  private val fallbacks: List<FallbackEncoder> = platformFallbacks(),
) {

  // One at a time: decoding several large photos at once ran low-memory devices out of heap.
  private val gate = Semaphore(permits = 1)

  suspend fun prepare(bytes: ByteArray, mimeType: String): PreparedEvidence {
    if (mimeType == MIME_PDF) return pdf(bytes)
    if (!mimeType.startsWith("image/")) {
      throw TraciveReportException(
        ReportErrorCode.UNSUPPORTED_FILE,
        "Evidence must be an image or a PDF.",
      )
    }
    return gate.withPermit { withContext(Dispatchers.Default) { image(bytes) } }
  }

  private fun pdf(bytes: ByteArray): PreparedEvidence {
    if (bytes.size >= PDF_LIMIT_BYTES) {
      throw TraciveReportException(
        ReportErrorCode.FILE_TOO_LARGE,
        "A PDF must be smaller than 1 MiB.",
      )
    }
    if (!bytes.startsWithAscii("%PDF-")) {
      throw TraciveReportException(ReportErrorCode.UNSUPPORTED_FILE, "This file is not a PDF.")
    }
    return PreparedEvidence(bytes, MIME_PDF)
  }

  private suspend fun image(bytes: ByteArray): PreparedEvidence {
    attempt(avif, bytes)?.let {
      return PreparedEvidence(it, MIME_AVIF)
    }
    for (fallback in fallbacks) {
      attempt(fallback.encoder, bytes)?.let {
        return PreparedEvidence(it, fallback.mimeType)
      }
    }
    throw TraciveReportException(
      ReportErrorCode.IMAGE_UNREADABLE,
      "The image could not be read or made small enough.",
    )
  }

  private suspend fun attempt(encoder: ImageEncoder, bytes: ByteArray): ByteArray? =
    try {
      encoder.encode(bytes, MAX_IMAGE_BYTES, MAX_SHORTEST_SIDE).takeIf {
        it.isNotEmpty() && it.size <= MAX_IMAGE_BYTES
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
    }

  private fun ByteArray.startsWithAscii(prefix: String): Boolean =
    size >= prefix.length && prefix.indices.all { this[it] == prefix[it].code.toByte() }

  private companion object {
    const val MIME_AVIF = "image/avif"
  }
}

internal object AvifKitEncoder : ImageEncoder {

  override suspend fun encode(image: ByteArray, maxBytes: Int, maxShortestSide: Int): ByteArray {
    val converter = AvifConverter()
    val input = ImageInput.from(image)
    return converter.encodeAvif(
      input = input,
      priority = Priority.STORAGE,
      options =
        EncodingOptions(
          maxSize = maxBytes.toLong(),
          maxDimension = longestSideFor(converter, input, maxShortestSide),
          compressionStrategy = CompressionStrategy.STRICT,
          // EXIF would carry the photo's GPS into the stored file and on to the analysis.
          preserveMetadata = false,
        ),
    )
  }

  /** avifkit caps the longest side, and the limit here is on the shortest. Null never upscales. */
  private suspend fun longestSideFor(
    converter: AvifConverter,
    input: ImageInput,
    maxShortestSide: Int,
  ): Int? =
    try {
      val info = converter.getImageInfo(input)
      val shortest = minOf(info.width, info.height)
      val longest = maxOf(info.width, info.height)
      if (shortest > maxShortestSide) ceil(longest.toDouble() * maxShortestSide / shortest).toInt()
      else null
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
    }
}
