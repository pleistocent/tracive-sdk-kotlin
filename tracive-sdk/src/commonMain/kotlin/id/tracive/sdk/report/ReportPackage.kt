package id.tracive.sdk.report

import id.tracive.sdk.model.ReportAttachment
import id.tracive.sdk.model.ReportFormError
import kotlin.io.encoding.Base64
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonPrimitive

/**
 * The report package (v1): validation and encoding.
 *
 * `report-package-vectors.json` in commonTest is the contract, shared byte-identically by every
 * Tracive SDK; the limits below are asserted against it.
 */
internal object ReportPackage {

  const val VERSION: Int = 1
  const val PDF_MAX_BYTES_EXCLUSIVE: Int = 1_048_576
  const val MAX_ATTACHMENTS: Int = 5
  const val MAX_ATTACHMENTS_PHONE: Int = 10
  const val MAX_PACKAGE_BYTES: Int = 20_971_520

  private const val PHONE_PREFIX = "PHONE_"

  /** The first problem in the order the contract fixes, or null when the package may be built. */
  fun validate(
    query: String,
    incidentDate: LocalDate?,
    today: LocalDate,
    attachments: List<ReportAttachment>,
  ): ReportFormError? {
    if (incidentDate != null && incidentDate > today) return ReportFormError.IncidentDateInFuture

    val maxAttachments =
      if (query.startsWith(PHONE_PREFIX)) MAX_ATTACHMENTS_PHONE else MAX_ATTACHMENTS
    if (attachments.size > maxAttachments) {
      return ReportFormError.TooManyAttachments(maxAttachments)
    }

    attachments.forEachIndexed { index, attachment ->
      val type =
        AttachmentType.of(attachment.mimeType) ?: return ReportFormError.UnsupportedType(index)
      if (!type.matches(attachment.bytes)) return ReportFormError.ContentMismatch(index)
      if (type == AttachmentType.PDF && attachment.bytes.size >= PDF_MAX_BYTES_EXCLUSIVE) {
        return ReportFormError.PdfTooLarge(index)
      }
    }

    // Measured, not built: refusing a 70 MB package should not first allocate it.
    if (Layout(query, incidentDate, attachments).size > MAX_PACKAGE_BYTES) {
      return ReportFormError.PackageTooLarge(MAX_PACKAGE_BYTES)
    }
    return null
  }

  /** The package as UTF-8 JSON. Only for input [validate] accepted. */
  fun encode(
    query: String,
    incidentDate: LocalDate?,
    attachments: List<ReportAttachment>,
  ): ByteArray = Layout(query, incidentDate, attachments).encode()

  /**
   * The JSON, written straight into one array of exactly the right size. Everything around the
   * attachment data is ASCII apart from the query, which is measured as UTF-8.
   */
  private class Layout(
    query: String,
    incidentDate: LocalDate?,
    private val attachments: List<ReportAttachment>,
  ) {

    private val head: ByteArray =
      buildString {
          append("{\"v\":").append(VERSION)
          append(",\"query\":").append(JsonPrimitive(query).toString())
          if (incidentDate != null) append(",\"incidentDate\":\"").append(incidentDate).append('"')
          append(",\"attachments\":[")
        }
        .encodeToByteArray()

    private val openings: List<ByteArray> = attachments.mapIndexed { index, attachment ->
      val type = AttachmentType.of(attachment.mimeType)!!
      buildString {
          if (index > 0) append(',')
          append("{\"name\":\"attachment-").append(index + 1).append('.').append(type.extension)
          append("\",\"mimeType\":\"").append(type.mimeType)
          append("\",\"data\":\"")
        }
        .encodeToByteArray()
    }

    val size: Long =
      head.size.toLong() +
        attachments.indices.sumOf { index ->
          openings[index].size + base64Length(attachments[index].bytes.size) + CLOSE.size
        } +
        TAIL.size

    fun encode(): ByteArray {
      val out = ByteArray(size.toInt())
      head.copyInto(out)
      var offset = head.size
      attachments.forEachIndexed { index, attachment ->
        openings[index].copyInto(out, offset)
        offset += openings[index].size
        offset += Base64.Default.encodeIntoByteArray(attachment.bytes, out, offset)
        CLOSE.copyInto(out, offset)
        offset += CLOSE.size
      }
      TAIL.copyInto(out, offset)
      return out
    }

    private companion object {
      val CLOSE = "\"}".encodeToByteArray()
      val TAIL = "]}".encodeToByteArray()

      fun base64Length(bytes: Int): Long = 4L * ((bytes.toLong() + 2) / 3)
    }
  }
}

/** The attachment types the Tracive app accepts, with the bytes each one must start with. */
internal enum class AttachmentType(val mimeType: String, val extension: String) {
  JPEG("image/jpeg", "jpg"),
  PNG("image/png", "png"),
  WEBP("image/webp", "webp"),
  HEIC("image/heic", "heic"),
  HEIF("image/heif", "heif"),
  BMP("image/bmp", "bmp"),
  PDF("application/pdf", "pdf");

  fun matches(bytes: ByteArray): Boolean =
    when (this) {
      JPEG -> bytes.hasAt(0, JPEG_MAGIC)
      PNG -> bytes.hasAt(0, PNG_MAGIC)
      WEBP -> bytes.hasAt(0, RIFF) && bytes.hasAt(8, WEBP_MAGIC)
      BMP -> bytes.hasAt(0, BMP_MAGIC)
      HEIC,
      HEIF -> bytes.hasAt(4, FTYP) && HEIF_BRANDS.any { bytes.hasAt(8, it) }
      PDF -> bytes.hasAt(0, PDF_MAGIC)
    }

  companion object {
    /** Case-insensitive, with `image/jpg` read as `image/jpeg`. */
    fun of(mimeType: String): AttachmentType? {
      val lower = mimeType.lowercase()
      val canonical = if (lower == "image/jpg") JPEG.mimeType else lower
      return entries.firstOrNull { it.mimeType == canonical }
    }

    private val JPEG_MAGIC = bytesOf(0xFF, 0xD8, 0xFF)
    private val PNG_MAGIC = bytesOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val RIFF = "RIFF".encodeToByteArray()
    private val WEBP_MAGIC = "WEBP".encodeToByteArray()
    private val BMP_MAGIC = "BM".encodeToByteArray()
    private val FTYP = "ftyp".encodeToByteArray()
    private val PDF_MAGIC = "%PDF-".encodeToByteArray()
    private val HEIF_BRANDS =
      listOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1").map {
        it.encodeToByteArray()
      }

    private fun bytesOf(vararg values: Int): ByteArray =
      ByteArray(values.size) { values[it].toByte() }

    private fun ByteArray.hasAt(offset: Int, expected: ByteArray): Boolean =
      size >= offset + expected.size && expected.indices.all { this[offset + it] == expected[it] }
  }
}
