package id.tracive.sdk.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.tracive.sdk.model.CheckResult
import id.tracive.sdk.model.Origin
import id.tracive.sdk.model.RiskTier
import androidx.compose.foundation.text.KeyboardOptions

/**
 * The sample's single screen.
 *
 * `launchMode="singleTask"` in the manifest is what makes [onNewIntent] the delivery point for the
 * OAuth redirect: the browser resumes this existing task instead of stacking a second copy of the
 * app behind it.
 */
class MainActivity : ComponentActivity() {

  /**
   * Obtained here rather than inside `setContent`, because a cold start caused by the redirect has
   * to reach the ViewModel before the first composition has necessarily run. Reading it from the
   * composition left a window in which the redirect that started the app was delivered to nothing —
   * and that window is exactly the process-death case the SDK now recovers from.
   */
  private val viewModel: CheckViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme { Surface(modifier = Modifier.fillMaxSize()) { SampleScreen(viewModel) } }
    }
    // A cold start launched *by* the redirect arrives here rather than in onNewIntent. Consuming
    // the URI keeps a configuration change from replaying an already-spent redirect, which the SDK
    // would answer as "no authorization is in progress".
    intent?.data?.let {
      intent.data = null
      viewModel.handleRedirect(it.toString())
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    intent.data?.let {
      intent.data = null
      viewModel.handleRedirect(it.toString())
    }
  }
}

@Composable
private fun SampleScreen(model: CheckViewModel) {
  val state by model.state.collectAsStateWithLifecycle()

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Tracive SDK sample", style = MaterialTheme.typography.headlineSmall)
    Text(
      "phase 1: OAuth mode only. The Tracive Open API is not live yet, so any call that reaches " +
        "the network will fail — the query folding, the encrypted cache and the OAuth flow up to " +
        "the redirect are what you can exercise today.",
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
      Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }

    state.result?.let { ResultCard(it) }

    Spacer(Modifier.height(24.dp))
  }
}

@Composable
private fun ResultCard(result: CheckResult) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        result.riskTier.headline(),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Text("Reports: ${result.reportTotal} (${result.credibleReports} credible)")
      Text("Linked identifiers: ${result.relatedScammerCount}")
      if (result.tags.isNotEmpty()) Text("Tags: ${result.tags.joinToString()}")
      result.fraudAmount.forEach { (currency, amount) -> Text("Reported loss: $amount $currency") }
      result.lastReportOn?.let { Text("Most recent report: $it") }

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
