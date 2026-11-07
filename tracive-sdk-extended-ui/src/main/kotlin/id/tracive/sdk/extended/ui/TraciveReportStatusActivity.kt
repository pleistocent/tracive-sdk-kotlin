package id.tracive.sdk.extended.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

internal class TraciveReportStatusActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    val backend = TraciveReportScreens.backend
    val reportId = intent.statusReportId()
    if (backend == null || reportId == null) {
      finish()
      return
    }

    val theme = intent.reportTheme()
    val model by
      viewModels<ReportStatusViewModel> {
        viewModelFactory { initializer { ReportStatusViewModel(backend, reportId) } }
      }

    setContent {
      LaunchedEffect(model.needsConsent) {
        if (model.needsConsent) {
          setResult(Activity.RESULT_OK)
          finish()
        }
      }
      ReportTheme(theme) { ReportStatusScreen(model, onClose = ::finish) }
    }
  }
}
