package id.tracive.sdk.sample

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
 * Background for the navigation bar on API 24-25, which cannot darken that bar's icons — they are
 * always white, so a transparent bar there would put white buttons on a white Surface. This is the
 * platform's own scrim. API 26 and above darken the icons and ignore this.
 */
private val NAVIGATION_BAR_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)

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
    // At targetSdk 35 and above the platform draws this window behind the system bars and ignores
    // the theme's statusBarColor; at 36 the opt-out flag is gone too. So the only choice left is
    // between declaring edge-to-edge and getting it undeclared, and undeclared is what leaves the
    // header under the status bar with white icons over a white Surface. `light` rather than the
    // default `auto` because the MaterialTheme below is the light scheme in every configuration —
    // `auto` follows the system into dark mode and would put those white icons back. The dark
    // navigation scrim is only reached on API 24-25, which cannot darken that bar's icons.
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, NAVIGATION_BAR_SCRIM),
    )
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
    // Outside the scroll, so the viewport itself is inset rather than the content scrolling under
    // a transparent bar. `safeDrawing` is systemBars + displayCutout + ime, which is also what
    // keeps the keyboard off the phone field: an edge-to-edge window no longer resizes for it.
    modifier =
      Modifier.fillMaxSize()
        .safeDrawingPadding()
        .verticalScroll(rememberScrollState())
        .padding(20.dp),
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
