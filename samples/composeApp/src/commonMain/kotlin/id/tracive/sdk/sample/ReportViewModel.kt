package id.tracive.sdk.sample

import id.tracive.sdk.client.TraciveClient
import id.tracive.sdk.model.ReportAttachment
import id.tracive.sdk.model.ReportFormError
import id.tracive.sdk.model.ReportFormResult
import id.tracive.sdk.model.ReportTarget
import id.tracive.sdk.query.TraciveQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn

/** The transfer the sample's detail card shows. Made up. */
internal object MockTransaction {
  const val AMOUNT = "IDR 2,500,000"
  const val BANK = "BCA"
  const val ACCOUNT = "1234567890"
  const val REFERENCE = "TRF-SAMPLE-0001"
}

/**
 * Which Tracive build the Report button hands the report to.
 *
 * Only for trying the sample against each build. A partner app leaves `target` null, and the SDK
 * follows the client's environment.
 */
internal enum class ReportTargetChoice(val label: String, val target: ReportTarget) {
  SANDBOX("Sandbox", ReportTarget.Sandbox),
  PRODUCTION("Production", ReportTarget.Production),
  // The app's QA build. Android sees it through the `<queries>` entry in the sample's manifest,
  // iOS through the sample's LSApplicationQueriesSchemes.
  DEV(
    "Dev build (QA)",
    ReportTarget(
      applicationId = "com.pleistocent.tracive.dev",
      urlScheme = "tracivedev",
      capabilityScheme = "tracivedev-report",
      fileExtension = "tracivedevreport",
    ),
  ),
}

internal data class ReportUiState(
  val choice: ReportTargetChoice = ReportTargetChoice.SANDBOX,
  val busy: Boolean = false,
  val outcome: String? = null,
)

/** Drives the mock transaction detail card and its Report button. */
internal class ReportViewModel(
  private val client: TraciveClient,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {

  private val _state = MutableStateFlow(ReportUiState())
  val state: StateFlow<ReportUiState> = _state.asStateFlow()

  val transactionDate: LocalDate =
    Clock.System.todayIn(TimeZone.currentSystemDefault()).minus(1, DateTimeUnit.DAY)

  fun onTargetChosen(choice: ReportTargetChoice) {
    _state.update { it.copy(choice = choice, outcome = null) }
  }

  fun report() {
    if (_state.value.busy) return
    _state.update { it.copy(busy = true, outcome = null) }
    scope.launch {
      try {
        // No launcher passed: Compose has no UIView for this button, so on iOS the sheet comes from
        // the key window's top view controller.
        val result =
          client.openReportForm(
            query = TraciveQuery.bankAccount("IDN", MockTransaction.BANK, MockTransaction.ACCOUNT),
            incidentDate = transactionDate,
            attachments =
              listOf(
                ReportAttachment.image(SampleReceipt.png(), "image/png"),
                ReportAttachment.pdf(SampleReceipt.pdf(transactionDate)),
              ),
            target = _state.value.choice.target,
          )
        _state.update { it.copy(outcome = describe(result)) }
      } finally {
        _state.update { it.copy(busy = false) }
      }
    }
  }

  private fun describe(result: ReportFormResult): String =
    when (result) {
      is ReportFormResult.Opened ->
        if (result.attachmentsDelivered) {
          "Opened in Tracive with the account, the date and both receipt files."
        } else {
          // TODO(partner): tell the user to pick the date and attach the receipt in Tracive.
          "Opened in an older Tracive with the account only. Add the date and receipt there."
        }
      ReportFormResult.Cancelled -> "Cancelled: nothing was sent to Tracive."
      ReportFormResult.InstallPageOpened -> "Tracive is not installed. The install page is open."
      is ReportFormResult.Failed ->
        when (val error = result.error) {
          is ReportFormError.PlatformFailure -> "Failed: ${error.message}"
          else -> "Refused before opening anything: $error"
        }
    }
}
