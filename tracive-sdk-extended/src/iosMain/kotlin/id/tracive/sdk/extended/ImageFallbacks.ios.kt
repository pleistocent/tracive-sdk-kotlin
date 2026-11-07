@file:OptIn(ExperimentalForeignApi::class)

package id.tracive.sdk.extended

import kotlin.math.max
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

// JPEG only. The app gets WebP on iOS from a pod, which a published core would push onto every
// partner.
internal actual fun platformFallbacks(): List<FallbackEncoder> =
  listOf(FallbackEncoder("image/jpeg", JpegEncoder))

private object JpegEncoder : ImageEncoder {

  override suspend fun encode(image: ByteArray, maxBytes: Int, maxShortestSide: Int): ByteArray {
    val source = UIImage.imageWithData(image.toNSData()) ?: error("Not a decodable image.")
    val resized = source.fitted(maxShortestSide.toDouble())

    var quality = 0.9
    while (quality >= 0.05) {
      val encoded = UIImageJPEGRepresentation(resized, quality)
      if (encoded != null && encoded.length.toInt() <= maxBytes) return encoded.toByteArray()
      quality -= 0.1
    }
    error("The image cannot be made small enough.")
  }

  private fun UIImage.fitted(maxShortestSide: Double): UIImage {
    val width = size.useContents { this.width }
    val height = size.useContents { this.height }
    val scale = max(maxShortestSide / width, maxShortestSide / height).coerceAtMost(1.0)
    if (scale == 1.0) return this

    UIGraphicsBeginImageContextWithOptions(CGSizeMake(width * scale, height * scale), false, 1.0)
    drawInRect(CGRectMake(0.0, 0.0, width * scale, height * scale))
    val result = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return result ?: error("The image cannot be resized.")
  }
}
