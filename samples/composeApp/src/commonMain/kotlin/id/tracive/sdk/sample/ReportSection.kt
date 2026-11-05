package id.tracive.sdk.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A mock transaction detail with the Report button, and a picker for which Tracive build gets it.
 */
@Composable
internal fun ReportSection(model: ReportViewModel) {
  val state by model.state.collectAsState()

  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        "Transaction detail (mock)",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Text("Transfer of ${MockTransaction.AMOUNT}")
      Text("Date: ${model.transactionDate}")
      Text("To: ${MockTransaction.BANK} ${MockTransaction.ACCOUNT}")
      Text("Reference: ${MockTransaction.REFERENCE}")

      Text("Report to", style = MaterialTheme.typography.labelLarge)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReportTargetChoice.entries.forEach { choice ->
          FilterChip(
            selected = state.choice == choice,
            onClick = { model.onTargetChosen(choice) },
            label = { Text(choice.label) },
            enabled = !state.busy,
          )
        }
      }

      Button(onClick = model::report, enabled = !state.busy) { Text("Report") }

      state.outcome?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
  }
}
