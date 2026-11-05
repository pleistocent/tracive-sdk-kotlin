package id.tracive.sdk.model

/**
 * A receipt for [id.tracive.sdk.client.TraciveClient.openReportForm], as a screenshot or a PDF.
 *
 * Nothing is checked when one is built. `openReportForm` checks every attachment against the
 * Tracive app's own limits and returns the first problem as a [ReportFormError] carrying the
 * attachment's index, so a list can be assembled without any try/catch.
 *
 * The bytes are copied in, and [toString] never prints them.
 */
class ReportAttachment
internal constructor(
  /** The MIME type exactly as it was given. */
  val mimeType: String,
  bytes: ByteArray,
) {

  internal val bytes: ByteArray = bytes.copyOf()

  val sizeBytes: Int
    get() = bytes.size

  override fun toString(): String = "ReportAttachment($mimeType, $sizeBytes bytes)"

  companion object {
    /**
     * A screenshot. Accepted types are `image/jpeg` (`image/jpg` is read as JPEG), `image/png`,
     * `image/webp`, `image/heic`, `image/heif` and `image/bmp`, in any letter case. GIF is refused.
     * There is no size cap per image; the app compresses images itself.
     *
     * The bytes must really be that type: a PNG label on JPEG bytes is refused as
     * [ReportFormError.ContentMismatch].
     */
    fun image(bytes: ByteArray, mimeType: String): ReportAttachment =
      ReportAttachment(mimeType, bytes)

    /** A PDF receipt. It must be smaller than 1 MiB: 1,048,576 bytes is already refused. */
    fun pdf(bytes: ByteArray): ReportAttachment = ReportAttachment(PDF_MIME_TYPE, bytes)

    private const val PDF_MIME_TYPE = "application/pdf"
  }
}
