package id.tracive.sdk.extended.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import id.tracive.sdk.extended.ReportErrorCode
import id.tracive.sdk.extended.TraciveReportException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal const val MIME_PDF = "application/pdf"

private const val THUMBNAIL_PIXELS = 168

// Not a product limit, the core has those. It only stops a huge file from running the heap out
// before the core sees it.
private const val MAX_SOURCE_BYTES = 64 * 1024 * 1024

internal class PickedFile(
  val bytes: ByteArray,
  val mimeType: String,
  val name: String?,
  val thumbnail: ImageBitmap?,
)

/** One file on its way into the form. */
internal fun interface EvidenceInput {
  suspend fun read(): PickedFile
}

internal class UriEvidence(
  context: Context,
  private val uri: Uri,
  private val fallbackMimeType: String,
) : EvidenceInput {
  private val resolver = context.applicationContext.contentResolver

  override suspend fun read(): PickedFile =
    runInterruptible(Dispatchers.IO) {
      val mimeType = resolver.getType(uri) ?: fallbackMimeType
      val bytes = readBounded()
      PickedFile(bytes, mimeType, displayName(), thumbnailOf(bytes, mimeType))
    }

  private fun readBounded(): ByteArray =
    try {
      checkNotNull(resolver.openInputStream(uri)).use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var read = stream.read(buffer)
        while (read >= 0) {
          output.write(buffer, 0, read)
          if (output.size() > MAX_SOURCE_BYTES) throw tooLarge()
          read = stream.read(buffer)
        }
        output.toByteArray()
      }
    } catch (_: OutOfMemoryError) {
      throw tooLarge()
    }

  private fun displayName(): String? =
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) cursor.getString(0) else null
    }

  private fun tooLarge() =
    TraciveReportException(ReportErrorCode.IMAGE_UNREADABLE, "The file is too large to read.")
}

internal class PrefillEvidence(private val attachment: ReportPrefillAttachment) : EvidenceInput {
  override suspend fun read(): PickedFile =
    withContext(Dispatchers.Default) {
      PickedFile(
        attachment.bytes,
        attachment.mimeType,
        name = null,
        thumbnailOf(attachment.bytes, attachment.mimeType),
      )
    }
}

private fun thumbnailOf(bytes: ByteArray, mimeType: String): ImageBitmap? {
  if (!mimeType.startsWith("image/")) return null
  val bitmap = runCatching { decodeSmall(bytes) }.getOrNull()
  return bitmap?.asImageBitmap()
}

private fun decodeSmall(bytes: ByteArray): Bitmap? {
  // ImageDecoder also applies the EXIF rotation, which BitmapFactory leaves out.
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) {
      decoder,
      info,
      _ ->
      decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height))
    }
  }
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  val options =
    BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
  return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun sampleSize(width: Int, height: Int): Int {
  var sample = 1
  while (minOf(width, height) / (sample * 2) >= THUMBNAIL_PIXELS) sample *= 2
  return sample
}
