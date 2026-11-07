package id.tracive.sdk.extended

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream

internal actual fun platformFallbacks(): List<FallbackEncoder> =
  listOf(
    FallbackEncoder("image/webp", BitmapEncoder(webpFormat())),
    FallbackEncoder("image/jpeg", BitmapEncoder(Bitmap.CompressFormat.JPEG)),
  )

private fun webpFormat(): Bitmap.CompressFormat =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    Bitmap.CompressFormat.WEBP_LOSSY
  } else {
    @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
  }

// Once quality bottoms out, each pass shrinks the image instead: past that point a lower quality
// mostly adds blocking for few bytes saved.
private const val DOWNSCALE_PASS_FACTOR = 0.85f
private const val DOWNSCALE_PASS_QUALITY = 75
private const val DOWNSCALE_MIN_DIMENSION = 64
private const val MAX_DOWNSCALE_PASSES = 10
private const val MIN_QUALITY = 10

private class BitmapEncoder(private val format: Bitmap.CompressFormat) : ImageEncoder {

  override suspend fun encode(image: ByteArray, maxBytes: Int, maxShortestSide: Int): ByteArray {
    // Bounds first: a full-resolution decode can alone exceed a low-end device's heap.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
    check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not a decodable image." }

    val scale =
      maxOf(
          maxShortestSide.toFloat() / bounds.outWidth,
          maxShortestSide.toFloat() / bounds.outHeight,
        )
        .coerceAtMost(1f)
    var width = (bounds.outWidth * scale).toInt()
    var height = (bounds.outHeight * scale).toInt()

    val sampled =
      BitmapFactory.decodeByteArray(
        image,
        0,
        image.size,
        BitmapFactory.Options().apply {
          inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height)
        },
      ) ?: error("Not a decodable image.")
    var scaled = Bitmap.createScaledBitmap(sampled, width, height, true)

    var quality = 90
    var encoded = scaled.encoded(quality)
    while (encoded.size > maxBytes && quality > MIN_QUALITY) {
      quality -= 5
      encoded = scaled.encoded(quality)
    }

    var passes = 0
    while (
      encoded.size > maxBytes &&
        passes < MAX_DOWNSCALE_PASSES &&
        minOf(width, height) > DOWNSCALE_MIN_DIMENSION
    ) {
      width = (width * DOWNSCALE_PASS_FACTOR).toInt().coerceAtLeast(1)
      height = (height * DOWNSCALE_PASS_FACTOR).toInt().coerceAtLeast(1)
      val smaller = Bitmap.createScaledBitmap(scaled, width, height, true)
      if (scaled != sampled) scaled.recycle()
      scaled = smaller
      encoded = scaled.encoded(DOWNSCALE_PASS_QUALITY)
      passes++
    }

    if (scaled != sampled) scaled.recycle()
    sampled.recycle()
    return encoded
  }

  private fun Bitmap.encoded(quality: Int): ByteArray =
    ByteArrayOutputStream().also { compress(format, quality, it) }.toByteArray()

  private fun sampleSize(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): Int {
    var size = 1
    while (sourceWidth / (size * 2) >= width && sourceHeight / (size * 2) >= height) size *= 2
    return size
  }
}
