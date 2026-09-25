package id.tracive.sdk.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.tracive.sdk.model.CheckResult
import id.tracive.sdk.model.Origin
import id.tracive.sdk.model.RiskTier

/**
 * The sample's single screen.
 *
 * Deliberately the same layout as the Android sample's, so the diff between the two is the platform
 * wiring and nothing else. `collectAsState` rather than `collectAsStateWithLifecycle`: the latter
 * is AndroidX and has no Compose Multiplatform equivalent, and this screen is the whole app.
 */
@Composable
internal fun SampleScreen(model: CheckViewModel) {
  val state by model.state.collectAsState()

  MaterialTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      Column(
        // `safeDrawing` is the notch, the status bar and the home indicator. Outside the scroll, so
        // the viewport itself is inset rather than the content scrolling under them.
        modifier =
          Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text("Tracive SDK sample", style = MaterialTheme.typography.headlineSmall)
        Text(
          "${model.environment} · ${model.environment.baseUrl}",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          "phase 1: OAuth mode only. The build picks the environment — the sandbox or production " +
            "flavor on Android, the Sandbox or Production scheme on iOS.",
          style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = model::authorize, enabled = !state.busy && !state.authorized) {
            Text(if (state.authorized) "Authorized" else "Authorize")
          }
          OutlinedButton(onClick = model::signOut, enabled = !state.busy && state.authorized) {
            Text("Sign out")
          }
        }

        OutlinedTextField(
          value = state.phone,
          onValueChange = model::onPhoneChanged,
          label = { Text("Phone number (E.164)") },
          placeholder = { Text("+6281234567891") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
          modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = model::check, enabled = !state.busy) { Text("Check") }
          OutlinedButton(onClick = model::clearCache, enabled = !state.busy) {
            Text("Clear cache (${state.cachedEntries})")
          }
        }

        if (state.busy) {
          CircularProgressIndicator()
        }

        state.message?.let {
          Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
          )
        }

        state.result?.let { ResultCard(it) }

        Spacer(Modifier.height(24.dp))
      }
    }
  }
}

@Composable
private fun ResultCard(result: CheckResult) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        result.riskTier.headline(),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Text("Reports: ${result.reportTotal} (${result.credibleReports} credible)")
      Text("Linked identifiers: ${result.relatedScammerCount}")
      if (result.tags.isNotEmpty()) Text("Tags: ${result.tags.joinToString()}")
      result.fraudAmount.forEach { (currency, amount) -> Text("Reported loss: $amount $currency") }
      result.lastReportOn?.let { Text("Most recent report: ${monthYear(it)}") }

      // Where the answer came from, and how old it is. A cache hit is displayed exactly like a live
      // one, so without this the user cannot tell that they are looking at yesterday's answer.
      Text(
        when (result.origin) {
          Origin.CACHE -> "From the local cache, fetched ${result.fetchedAt}. No credit was spent."
          Origin.SERVER -> "Fetched from Tracive just now."
        },
        style = MaterialTheme.typography.bodySmall,
      )

      // Required. These results are user-submitted allegations about real people and accounts, not
      // adjudicated findings, and the disclaimer is what makes that clear to whoever acts on them.
      result.disclaimer?.let {
        Text(
          it,
          style = MaterialTheme.typography.labelSmall,
          textAlign = TextAlign.Start,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

/**
 * Wording matters here. `NONE` means "no evidence found" and must never be phrased as "safe" — the
 * absence of a report is not a clean bill of health, and a UI that says otherwise is worse than no
 * UI at all.
 */
private fun RiskTier.headline(): String =
  when (this) {
    RiskTier.HIGH -> "High risk — strong evidence of fraud"
    RiskTier.ELEVATED -> "Elevated risk — several credible reports"
    RiskTier.CAUTION -> "Caution — some reports, not corroborated"
    RiskTier.NONE -> "No reports found — this is not proof of safety"
  }
