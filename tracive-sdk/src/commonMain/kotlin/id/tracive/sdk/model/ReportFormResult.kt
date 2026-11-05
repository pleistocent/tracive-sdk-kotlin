package id.tracive.sdk.model

/**
 * How [id.tracive.sdk.client.TraciveClient.openReportForm] ended.
 *
 * Sealed, so a `when` over it has to handle the older-app case and the missing-app case as well as
 * the one where everything arrived.
 */
sealed class ReportFormResult {

  /**
   * The Tracive app opened its report form with the identifier filled in.
   *
   * @param attachmentsDelivered true when the app received the whole package: identifier, date and
   *   attachments. False when the installed app is older than the package handoff, so it was opened
   *   with the identifier only; ask the user to attach the receipt and pick the date in Tracive.
   */
  data class Opened(val attachmentsDelivered: Boolean) : ReportFormResult()

  /**
   * Nothing was handed over because the user chose not to. iOS only: the user closed the "Open in"
   * sheet, picked "Save to Files" or another app, or declined the system prompt to open Tracive.
   */
  object Cancelled : ReportFormResult() {
    override fun toString(): String = "Cancelled"
  }

  /**
   * The Tracive app is not installed, so the install page was opened instead, exactly as
   * `authorize()` opens it. That link carries nothing from the report, and nothing is resumed after
   * the install: the user installs Tracive, comes back and taps Report again.
   */
  object InstallPageOpened : ReportFormResult() {
    override fun toString(): String = "InstallPageOpened"
  }

  /** Nothing was opened. See [ReportFormError] for what went wrong. */
  data class Failed(val error: ReportFormError) : ReportFormResult()
}

/**
 * Why [id.tracive.sdk.client.TraciveClient.openReportForm] opened nothing.
 *
 * Every case except [PlatformFailure] is a validation failure, found before anything is opened. The
 * checks run in this order and the first failure wins: [IncidentDateInFuture], then
 * [TooManyAttachments], then each attachment in order ([UnsupportedType], [ContentMismatch],
 * [PdfTooLarge]), then [PackageTooLarge].
 *
 * [code] is the same string in every Tracive SDK, so an integration spanning several of them can
 * log or match one value.
 */
sealed class ReportFormError {

  abstract val code: String

  /** The incident date is after today in the device's own time zone. */
  object IncidentDateInFuture : ReportFormError() {
    override val code: String
      get() = "INCIDENT_DATE_IN_FUTURE"

    override fun toString(): String = "IncidentDateInFuture"
  }

  /** More attachments than the app accepts: [max] is 5, or 10 for a phone-number query. */
  data class TooManyAttachments(val max: Int) : ReportFormError() {
    override val code: String
      get() = "TOO_MANY_ATTACHMENTS"
  }

  /** The attachment at [index] declares a type the app does not accept. */
  data class UnsupportedType(val index: Int) : ReportFormError() {
    override val code: String
      get() = "UNSUPPORTED_TYPE"
  }

  /** The bytes of the attachment at [index] are not the type it declares, or are empty. */
  data class ContentMismatch(val index: Int) : ReportFormError() {
    override val code: String
      get() = "CONTENT_MISMATCH"
  }

  /** The PDF at [index] is 1 MiB (1,048,576 bytes) or larger. */
  data class PdfTooLarge(val index: Int) : ReportFormError() {
    override val code: String
      get() = "PDF_TOO_LARGE"
  }

  /**
   * The encoded package would be larger than [max] bytes (20 MiB). Base64 makes each attachment
   * about a third larger than its bytes.
   */
  data class PackageTooLarge(val max: Int) : ReportFormError() {
    override val code: String
      get() = "PACKAGE_TOO_LARGE"
  }

  /**
   * The operating system refused a step: no window to present from, an activity that would not
   * start, a file that could not be written.
   *
   * [message] is written by the SDK and names the step and the platform error's type only. The
   * platform's exception is not kept: its text can quote the link, which carries the identifier.
   */
  class PlatformFailure(val message: String) : ReportFormError() {
    override val code: String
      get() = "PLATFORM_FAILURE"

    override fun toString(): String = "PlatformFailure($message)"
  }
}
