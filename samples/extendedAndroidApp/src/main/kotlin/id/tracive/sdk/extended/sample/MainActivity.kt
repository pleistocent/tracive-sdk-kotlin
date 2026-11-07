package id.tracive.sdk.extended.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.tracive.sdk.extended.ui.TraciveReportForm
import id.tracive.sdk.extended.ui.TraciveReportStatus

/** `singleTask` in the manifest is what brings the OAuth redirect to [onNewIntent]. */
class MainActivity : ComponentActivity() {

  private val model: SampleModel by viewModels()

  private val form =
    registerForActivityResult(TraciveReportForm.Contract()) { model.onFormOutcome(it) }

  private val status =
    registerForActivityResult(TraciveReportStatus.Contract()) { model.onStatusOutcome(it) }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    setContent {
      val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
      MaterialTheme(colors) {
        SampleScreen(
          model,
          openForm = { form.launch(model.formRequest) },
          openStatus = { status.launch(model.statusRequest(it)) },
        )
      }
    }
    consumeRedirect(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    consumeRedirect(intent)
  }

  private fun consumeRedirect(intent: Intent?) {
    intent?.data?.let {
      intent.data = null
      model.handleRedirect(it.toString())
    }
  }
}

@Composable
private fun SampleScreen(model: SampleModel, openForm: () -> Unit, openStatus: (String) -> Unit) {
  val uris = LocalUriHandler.current
  val reportId = model.lastReportId
  Scaffold { padding ->
    Column(
      Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Tracive Reports", style = MaterialTheme.typography.headlineSmall)
      Text(model.message, style = MaterialTheme.typography.bodyMedium)
      Button(
        onClick = model::authorize,
        enabled = !model.busy,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Authorize")
      }

      Heading("Tracive's screens")
      Button(onClick = openForm, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Report with Tracive's form")
      }
      Button(
        onClick = { reportId?.let(openStatus) },
        enabled = reportId != null,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Open Tracive's status screen")
      }

      Heading("Your own UI")
      OutlinedButton(
        onClick = model::loadRequirements,
        enabled = !model.busy,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Read requirements")
      }
      OutlinedTextField(
        value = model.chronology,
        onValueChange = { model.chronology = it },
        label = { Text("What happened? The user's own words") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
      )
      if (model.terms.isNotEmpty()) {
        Text("By sending you agree to:", style = MaterialTheme.typography.bodySmall)
        model.terms.forEach { term ->
          OutlinedButton(onClick = { uris.openUri(term.url) }) { Text(term.id) }
        }
      }
      OutlinedButton(
        onClick = model::sendWithOwnUi,
        enabled = !model.busy,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Send with my own UI")
      }
      OutlinedButton(
        onClick = { reportId?.let(model::checkStatusWithOwnUi) },
        enabled = reportId != null && !model.busy,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Check status with my own UI")
      }
    }
  }
}

@Composable
private fun Heading(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(top = 8.dp),
  )
}
