package id.tracive.sdk.extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.tracive.sdk.extended.ReportErrorCode
import id.tracive.sdk.extended.ReportStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportStatusScreen(model: ReportStatusViewModel, onClose: () -> Unit) {
  Scaffold(
    containerColor = MaterialTheme.colorScheme.surface,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.tracive_report_status_title)) },
        navigationIcon = {
          IconButton(onClick = onClose) {
            Icon(
              painterResource(R.drawable.tracive_report_ic_close),
              stringResource(R.string.tracive_report_close),
            )
          }
        },
      )
    },
  ) { padding ->
    when (val load = model.load) {
      StatusLoad.Loading -> LoadingContent(padding)
      is StatusLoad.Loaded -> LoadedStatus(load.status, model, padding)
      is StatusLoad.Failed -> FailedStatus(load.code, padding, model::retry)
    }
  }
}

@Composable
private fun LoadedStatus(
  status: ReportStatus,
  model: ReportStatusViewModel,
  padding: PaddingValues,
) {
  MessageContent(
    title = stringResource(statusTitle(status)),
    line = stringResource(statusLine(status)),
    padding = padding,
    icon = statusIcon(status),
  ) {
    if (status == ReportStatus.NEED_REVISION) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Button(
          onClick = model::openInTracive,
          shape = MaterialTheme.shapes.medium,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.tracive_report_open_in_tracive))
        }
        if (model.traciveMissing) {
          Text(
            stringResource(R.string.tracive_report_tracive_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
          )
        }
      }
    }
  }
}

// One shape per stage, none of them a tick or a cross: REVIEWED must not read as a verdict.
private fun statusIcon(status: ReportStatus): Int =
  when (status) {
    ReportStatus.SUBMITTED -> R.drawable.tracive_report_ic_send
    ReportStatus.IN_REVIEW -> R.drawable.tracive_report_ic_schedule
    ReportStatus.REVIEWED -> R.drawable.tracive_report_ic_document
    ReportStatus.NEED_REVISION -> R.drawable.tracive_report_ic_edit
  }

@Composable
private fun FailedStatus(code: ReportErrorCode, padding: PaddingValues, onRetry: () -> Unit) {
  val gone = code == ReportErrorCode.REPORT_NOT_FOUND
  MessageContent(
    title =
      stringResource(
        if (gone) R.string.tracive_report_status_gone else R.string.tracive_report_load_failed
      ),
    line = stringResource(errorText(code)),
    padding = padding,
    icon = R.drawable.tracive_report_ic_alert,
  ) {
    if (!gone) {
      Button(onClick = onRetry, shape = MaterialTheme.shapes.medium) {
        Text(stringResource(R.string.tracive_report_retry))
      }
    }
  }
}
