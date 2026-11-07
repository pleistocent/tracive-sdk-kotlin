package id.tracive.sdk.extended.sample

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.tracive.sdk.extended.ReportDraft
import id.tracive.sdk.extended.ReportErrorCode
import id.tracive.sdk.extended.RequiredTerm
import id.tracive.sdk.extended.SubmitResult
import id.tracive.sdk.extended.TraciveReportException
import id.tracive.sdk.extended.ui.ReportFormOutcome
import id.tracive.sdk.extended.ui.ReportFormRequest
import id.tracive.sdk.extended.ui.ReportPrefill
import id.tracive.sdk.extended.ui.ReportPrefillAttachment
import id.tracive.sdk.extended.ui.ReportStatusOutcome
import id.tracive.sdk.extended.ui.ReportStatusRequest
import id.tracive.sdk.extended.ui.TraciveReportTheme
import id.tracive.sdk.model.AuthResult
import id.tracive.sdk.query.TraciveQuery
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn

// A made-up account, so the sample runs with one tap and reports nobody real.
private val FAKE_SCAMMER = TraciveQuery.bankAccount("IDN", "BCA", "0000000001")

// TODO(partner): your own brand.
private val THEME = TraciveReportTheme(primaryColor = 0xFF00695C.toInt(), cornerRadius = 16)

internal class SampleModel(application: Application) : AndroidViewModel(application) {

  private val app = application as SampleApp

  var message: String by mutableStateOf("Authorize first, unless this device already did.")
    private set

  var lastReportId: String? by mutableStateOf(null)
    private set

  var terms: List<RequiredTerm> by mutableStateOf(emptyList())
    private set

  var chronology: String by mutableStateOf("")

  var busy: Boolean by mutableStateOf(false)
    private set

  /** Kept across retries: its idempotency key is what stops a retry from filing twice. */
  private var ownDraft: ReportDraft? = null

  val formRequest: ReportFormRequest
    get() =
      ReportFormRequest(
        ReportPrefill(
          FAKE_SCAMMER,
          incidentDate = yesterday(),
          title = "Sample: fake seller",
          attachments = listOf(ReportPrefillAttachment(sampleReceipt(), "image/png")),
        ),
        THEME,
      )

  fun statusRequest(reportId: String) = ReportStatusRequest(reportId, THEME)

  fun onFormOutcome(outcome: ReportFormOutcome) {
    message =
      when (outcome) {
        is ReportFormOutcome.Submitted -> {
          lastReportId = outcome.reportId
          "Form: submitted ${outcome.reportId}, ${outcome.status}"
        }
        ReportFormOutcome.NeedsConsent -> "Form: needs consent. Authorize, then open it again."
        ReportFormOutcome.Cancelled -> "Form: cancelled."
      }
  }

  fun onStatusOutcome(outcome: ReportStatusOutcome) {
    message =
      when (outcome) {
        ReportStatusOutcome.Closed -> "Status screen closed."
        ReportStatusOutcome.NeedsConsent -> "Status: needs consent. Authorize, then open it again."
      }
  }

  fun authorize() = guarded {
    when (val start = app.client.authorize()) {
      null -> message = "Consent opened. Finish it in the Tracive app."
      AuthResult.AppNotInstalled -> message = "Install Tracive first. Its page has been opened."
      is AuthResult.Failed -> message = "Authorize failed: ${start.error.message}"
      else -> Unit
    }
  }

  fun handleRedirect(url: String) {
    viewModelScope.launch {
      when (val result = app.client.handleCallback(url)) {
        is AuthResult.Authorized -> message = "Granted: ${result.grantedScopes.joinToString()}"
        AuthResult.Denied -> message = "You declined."
        is AuthResult.Failed -> message = "Authorize failed: ${result.error.message}"
        else -> Unit
      }
    }
  }

  /** Your own UI, step 1: what to show before the user types. */
  fun loadRequirements() = guarded {
    val requirements = app.reports.requirements()
    terms = requirements.terms
    message = "Today: ${requirements.remainingToday} left. Keep the terms links next to Send."
  }

  /** Your own UI, step 2: compress, check, file. */
  fun sendWithOwnUi() = guarded {
    val requirements = app.reports.requirements()
    terms = requirements.terms
    val draft =
      ownDraft?.takeIf { it.chronology == chronology }
        ?: ReportDraft(
            query = FAKE_SCAMMER,
            title = "Sample: own UI",
            chronology = chronology,
            incidentDate = yesterday(),
            evidence = listOf(app.reports.prepare(sampleReceipt(), "image/png")),
            acceptedTerms = requirements.terms,
          )
          .also { ownDraft = it }

    val problems = draft.problems(requirements.limits, today())
    if (problems.isNotEmpty()) {
      message = "Fix first: ${problems.joinToString()}"
      return@guarded
    }
    when (val result = app.reports.submit(draft)) {
      is SubmitResult.Submitted -> {
        lastReportId = result.reportId
        ownDraft = null
        message = "Own UI: submitted ${result.reportId}, ${result.status}"
      }
      SubmitResult.NeedsConsent -> message = "Own UI: needs consent. Authorize, then try again."
      is SubmitResult.Failed -> message = "Own UI failed: ${result.error.code}. Try again."
    }
  }

  /** Your own UI, step 3: status by id. There is no way to list reports. */
  fun checkStatusWithOwnUi(reportId: String) = guarded {
    message = "Own UI: status is ${app.reports.status(reportId)}"
  }

  private fun guarded(block: suspend () -> Unit) {
    viewModelScope.launch {
      busy = true
      try {
        block()
      } catch (failure: TraciveReportException) {
        message =
          if (failure.code == ReportErrorCode.NEEDS_CONSENT) "Needs consent. Authorize first."
          else "Failed: ${failure.code}"
      } finally {
        busy = false
      }
    }
  }

  private fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

  private fun yesterday(): LocalDate = today().minus(DatePeriod(days = 1))

  private fun sampleReceipt(): ByteArray {
    val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 48f }
    Canvas(bitmap).apply {
      drawColor(Color.WHITE)
      drawText("Sample receipt", 60f, 120f, paint)
    }
    return ByteArrayOutputStream()
      .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
      .toByteArray()
  }
}
