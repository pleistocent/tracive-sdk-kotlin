package id.tracive.sdk.extended.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

private const val STATE_SUBMITTED = "submitted"

internal class TraciveReportFormActivity : ComponentActivity() {

  private var submitted: ReportFormOutcome.Submitted? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    // A report that went through stays Submitted whatever happens next, the back button included.
    savedInstanceState?.getBundle(STATE_SUBMITTED)?.toSubmitted()?.let(::onSubmitted)

    val backend = TraciveReportScreens.backend
    if (backend == null) {
      finish()
      return
    }

    val extras = intent.formExtras()
    val theme = intent.reportTheme()
    val model by viewModels<ReportFormViewModel> { formModelFactory(backend, extras, submitted) }

    setContent {
      val sent = model.sent
      LaunchedEffect(sent) {
        sent?.let { onSubmitted(ReportFormOutcome.Submitted(it.reportId, it.status)) }
      }
      LaunchedEffect(model.needsConsent) {
        if (model.needsConsent) {
          setResult(Activity.RESULT_OK, Intent())
          finish()
        }
      }
      ReportTheme(theme) {
        ReportFormScreen(
          model,
          onClose = ::finish,
          onDone = ::finish,
          onViewStatus = { reportId ->
            startActivity(
              TraciveReportStatus.Contract()
                .createIntent(this, ReportStatusRequest(reportId, theme))
            )
          },
        )
      }
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    submitted?.let { outState.putBundle(STATE_SUBMITTED, it.toBundle()) }
  }

  private fun onSubmitted(outcome: ReportFormOutcome.Submitted) {
    submitted = outcome
    setResult(Activity.RESULT_OK, Intent().putExtras(outcome.toBundle()))
  }
}

private fun formModelFactory(
  backend: ReportBackend,
  extras: FormExtras,
  submitted: ReportFormOutcome.Submitted?,
): ViewModelProvider.Factory = viewModelFactory {
  initializer {
    ReportFormViewModel(
      createSavedStateHandle(),
      backend,
      identifiers = extras.identifiers,
      initialTitle = extras.title,
      initialDate = extras.incidentDate,
      prefill = PendingAttachments.take(extras.attachmentsKey).map(::PrefillEvidence),
      alreadySent = submitted?.let { SentReport(it.reportId, it.status) },
    )
  }
}
