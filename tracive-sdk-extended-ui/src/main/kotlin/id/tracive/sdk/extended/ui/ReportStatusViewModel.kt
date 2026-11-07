package id.tracive.sdk.extended.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.tracive.sdk.extended.ReportErrorCode
import id.tracive.sdk.extended.ReportStatus
import id.tracive.sdk.extended.TraciveReportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal sealed interface StatusLoad {
  data object Loading : StatusLoad

  data class Loaded(val status: ReportStatus) : StatusLoad

  data class Failed(val code: ReportErrorCode) : StatusLoad
}

internal class ReportStatusViewModel(
  private val backend: ReportBackend,
  private val reportId: String,
) : ViewModel() {

  var load: StatusLoad by mutableStateOf(StatusLoad.Loading)
    private set

  var needsConsent: Boolean by mutableStateOf(false)
    private set

  var traciveMissing: Boolean by mutableStateOf(false)
    private set

  init {
    fetch()
  }

  fun retry() {
    load = StatusLoad.Loading
    fetch()
  }

  fun openInTracive() {
    traciveMissing = !backend.openInTracive(reportId)
  }

  private fun fetch() {
    viewModelScope.launch {
      try {
        load = StatusLoad.Loaded(backend.status(reportId))
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: TraciveReportException) {
        if (error.code == ReportErrorCode.NEEDS_CONSENT) needsConsent = true
        else load = StatusLoad.Failed(error.code)
      } catch (_: Exception) {
        load = StatusLoad.Failed(ReportErrorCode.SERVER)
      }
    }
  }
}
