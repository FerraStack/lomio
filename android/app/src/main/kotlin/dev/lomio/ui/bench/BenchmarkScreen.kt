package dev.lomio.ui.bench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lomio.llama.BenchResult
import java.util.Locale

enum class BenchProfile(val label: String, val pp: Int, val tg: Int, val reps: Int) {
  FAST("Fast (pp128/tg32)", 128, 32, 1),
  DEFAULT("Default (pp512/tg128)", 512, 128, 3),
}

data class BenchUiState(
  val modelName: String? = null,
  val profile: BenchProfile = BenchProfile.FAST,
  val running: Boolean = false,
  val result: BenchResult? = null,
  val error: String? = null,
  val systemInfo: String? = null,
  /** Where the loaded model runs (ModelInfo.backend), e.g. "CPU". */
  val backend: String? = null,
  /** Why the model is on the CPU although GPU acceleration is on. */
  val gpuNote: String? = null,
)

/** Local llama-bench style benchmark (RESEARCH.md §9.4); no leaderboard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(
  state: BenchUiState,
  onOpenDrawer: () -> Unit,
  onProfile: (BenchProfile) -> Unit,
  onRun: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text("Benchmark") },
        navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, "Open menu") } },
      )
    },
  ) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        state.modelName?.let { "Model: $it" } ?: "Load a model first (Models screen).",
        style = MaterialTheme.typography.bodyLarge,
      )
      state.backend?.let { Text("Runs on: $it", style = MaterialTheme.typography.bodyMedium) }
      state.gpuNote?.let {
        Text("GPU not used: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BenchProfile.entries.forEach { p ->
          FilterChip(selected = p == state.profile, onClick = { onProfile(p) }, label = { Text(p.label) })
        }
      }
      Button(onClick = onRun, enabled = state.modelName != null && !state.running) { Text("Run") }
      if (state.running) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Keep the screen open; this can take a few minutes.", style = MaterialTheme.typography.bodySmall)
      }
      state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      state.result?.let { r ->
        ElevatedCard(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r.modelDescription, style = MaterialTheme.typography.titleSmall)
            Text(fmt("Prompt processing (pp%d): %.1f ± %.1f t/s", r.pp, r.ppTokensPerSec, r.ppStdDev))
            Text(fmt("Text generation (tg%d): %.1f ± %.1f t/s", r.tg, r.tgTokensPerSec, r.tgStdDev))
            Text("Backend: ${r.backend} · ${r.repetitions} run(s)", style = MaterialTheme.typography.bodySmall)
          }
        }
      }
      state.systemInfo?.let {
        Text("System info", style = MaterialTheme.typography.titleSmall)
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

private fun fmt(f: String, vararg a: Any) = String.format(Locale.US, f, *a)
