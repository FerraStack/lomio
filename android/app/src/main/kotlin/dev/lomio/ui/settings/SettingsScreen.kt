package dev.lomio.ui.settings

import androidx.compose.foundation.layout.size

import androidx.compose.foundation.layout.Spacer

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.liveRegion

import androidx.compose.ui.semantics.LiveRegionMode

import androidx.compose.material.icons.outlined.CloudOff

import androidx.compose.material.icons.outlined.ErrorOutline

import androidx.compose.material.icons.outlined.CheckCircle

import androidx.compose.material3.CircularProgressIndicator

import androidx.compose.material3.OutlinedButton

import dev.lomio.data.hf.TokenCheck

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.lomio.data.settings.AppSettings
import dev.lomio.data.settings.ThemeMode
import dev.lomio.domain.GenerationSettings
import dev.lomio.domain.GpuSupport
import dev.lomio.domain.LoadSettings
import java.util.Locale
import kotlin.math.roundToInt

/** Global settings (RESEARCH.md §9.3). Every change is applied immediately via [onChange]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  settings: AppSettings,
  onChange: ((AppSettings) -> AppSettings) -> Unit,
  onOpenDrawer: () -> Unit,
  modifier: Modifier = Modifier,
  tokenCheck: TokenCheckState? = null,
  onTestToken: () -> Unit = {},
  gpu: GpuSupport = GpuSupport.UNKNOWN,
) {
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text("Settings") },
        navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, "Open menu") } },
      )
    },
  ) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Section("Appearance")
      Text("Theme", style = MaterialTheme.typography.bodyLarge)
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        ThemeMode.entries.forEachIndexed { i, mode ->
          SegmentedButton(
            selected = settings.theme == mode,
            onClick = { onChange { it.copy(theme = mode) } },
            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
          ) {
            Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
          }
        }
      }
      SwitchRow("Dynamic color", "Use colors from your wallpaper (Android 12+)", settings.dynamicColor) { v ->
        onChange { it.copy(dynamicColor = v) }
      }

      Section("Chat")
      LocalTextField(
        initial = settings.systemPrompt,
        onValueChange = { v -> onChange { it.copy(systemPrompt = v) } },
        label = { Text("Default system prompt") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
      )
      SwitchRow("Show generation stats", "Tokens, speed and time to first token under replies", settings.showStats) { v ->
        onChange { it.copy(showStats = v) }
      }
      SwitchRow("Keep screen on while generating", null, settings.keepScreenOn) { v ->
        onChange { it.copy(keepScreenOn = v) }
      }

      Section("Generation defaults")
      GenerationControls(settings.generation) { g -> onChange { it.copy(generation = g) } }

      Section("Models & memory")
      SwitchRow("Load last model on launch", "Skipped automatically if the last load crashed", settings.autoLoadLastModel) { v ->
        onChange { it.copy(autoLoadLastModel = v) }
      }
      SliderRow("Unload after minutes in background (0 = never)", settings.unloadAfterMinutes.toFloat(), 0f..60f, 11, "%.0f") { v ->
        onChange { it.copy(unloadAfterMinutes = (v / 5).roundToInt() * 5) }
      }
      LoadControls(settings.load) { l -> onChange { it.copy(load = l) } }
      GpuControls(settings, gpu, onChange)

      Section("Downloads")
      LocalTextField(
        initial = settings.hfToken,
        onValueChange = { v -> onChange { it.copy(hfToken = v.trim()) } },
        label = { Text("Hugging Face token (optional)") },
        supportingText = { Text("Needed for gated models. Stored encrypted on this device; only sent to huggingface.co.") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
      )
      TokenTestRow(enabled = settings.hfToken.isNotBlank(), state = tokenCheck, onTest = onTestToken)
      Spacer(Modifier.size(24.dp))
    }
  }
}

@Composable
fun GenerationControls(g: GenerationSettings, onChange: (GenerationSettings) -> Unit) {
  val d = GenerationSettings.DEFAULTS
  SliderRow("Temperature", g.temperature ?: d.temperature!!, 0f..2f, 40, "%.2f") { onChange(g.copy(temperature = it)) }
  SliderRow("Top-p", g.topP ?: d.topP!!, 0f..1f, 20, "%.2f") { onChange(g.copy(topP = it)) }
  SliderRow("Min-p", g.minP ?: d.minP!!, 0f..0.5f, 50, "%.2f") { onChange(g.copy(minP = it)) }
  SliderRow("Top-k", (g.topK ?: d.topK!!).toFloat(), 0f..100f, 99, "%.0f") { onChange(g.copy(topK = it.roundToInt())) }
  SliderRow("Repeat penalty", g.repeatPenalty ?: d.repeatPenalty!!, 1f..1.5f, 50, "%.2f") { onChange(g.copy(repeatPenalty = it)) }
  SliderRow(
    "Max tokens (0 = unlimited)",
    (g.maxTokens ?: d.maxTokens!!).coerceAtLeast(0).toFloat(),
    0f..4096f,
    31,
    "%.0f",
  ) {
    val v = it.roundToInt()
    onChange(g.copy(maxTokens = if (v == 0) -1 else v))
  }
}

@Composable
private fun LoadControls(l: LoadSettings, onChange: (LoadSettings) -> Unit) {
  SliderRow("Context size (tokens)", (l.nCtx ?: 4096).toFloat(), 512f..32768f, 62, "%.0f") {
    onChange(l.copy(nCtx = (it / 512).roundToInt().coerceAtLeast(1) * 512))
  }
  SliderRow("CPU threads (0 = auto)", (l.nThreads ?: 0).toFloat(), 0f..8f, 7, "%.0f") {
    onChange(l.copy(nThreads = it.roundToInt()))
  }
  SwitchRow("Memory-map model file", "Faster loads; turn off on low-RAM phones if loads fail", l.useMmap ?: true) {
    onChange(l.copy(useMmap = it))
  }
  Text(
    "Load settings apply the next time a model is loaded.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/** "GPU acceleration (experimental)": off by default, only offered on Adreno + OpenCL phones. */
@Composable
fun GpuControls(settings: AppSettings, gpu: GpuSupport, onChange: ((AppSettings) -> AppSettings) -> Unit) {
  // Turning it off always works, even if the phone no longer qualifies.
  val canToggle = gpu.available || settings.gpuEnabled
  Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text("GPU acceleration (experimental)", style = MaterialTheme.typography.bodyLarge)
      Text(
        if (gpu.available) {
          "Runs layers on the Adreno GPU with OpenCL. Often faster at reading long prompts, not always " +
            "at writing; if the GPU fails, the model loads on the CPU."
        } else {
          "Not available: ${gpu.reason}."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(
      checked = settings.gpuEnabled,
      onCheckedChange = { v -> onChange { it.copy(gpuEnabled = v) } },
      enabled = canToggle,
      modifier = Modifier.semantics { contentDescription = "GPU acceleration (experimental)" },
    )
  }
  if (settings.gpuEnabled) {
    SliderRow("Layers on the GPU (99 = all)", settings.gpuLayers.coerceIn(1, 99).toFloat(), 1f..99f, 97, "%.0f") { v ->
      onChange { it.copy(gpuLayers = v.roundToInt()) }
    }
  }
}

/**
 * Text field that keeps its own state: the persisted value round-trips through DataStore
 * asynchronously, and feeding it back as `value` would make the cursor jump.
 */
@Composable
private fun LocalTextField(
  initial: String,
  onValueChange: (String) -> Unit,
  label: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  supportingText: (@Composable () -> Unit)? = null,
  minLines: Int = 1,
  singleLine: Boolean = false,
  visualTransformation: VisualTransformation = VisualTransformation.None,
) {
  var text by rememberSaveable { mutableStateOf(initial) }
  OutlinedTextField(
    value = text,
    onValueChange = {
      text = it
      onValueChange(it)
    },
    label = label,
    supportingText = supportingText,
    minLines = minLines,
    singleLine = singleLine,
    visualTransformation = visualTransformation,
    modifier = modifier,
  )
}

@Composable
private fun Section(title: String) {
  HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 8.dp))
  Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Switch(checked = checked, onCheckedChange = onChange)
  }
}

@Composable
fun SliderRow(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  steps: Int,
  format: String,
  onChange: (Float) -> Unit,
) {
  Column(Modifier.padding(vertical = 4.dp)) {
    Row {
      Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Text(String.format(Locale.US, format, value), style = MaterialTheme.typography.bodyMedium)
    }
    Slider(
      value = value.coerceIn(range),
      onValueChange = onChange,
      valueRange = range,
      steps = steps,
      colors =
        if (steps > 10) SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
        else SliderDefaults.colors(),
    )
  }
}

sealed interface TokenCheckState {
  data object Checking : TokenCheckState

  data class Done(val result: TokenCheck) : TokenCheckState
}

@Composable
private fun TokenTestRow(enabled: Boolean, state: TokenCheckState?, onTest: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
    OutlinedButton(onClick = onTest, enabled = enabled && state != TokenCheckState.Checking) { Text("Test token") }
    Spacer(Modifier.size(12.dp))
    val (text, color, icon) =
      when (val s = state) {
        null -> Triple(null, MaterialTheme.colorScheme.onSurfaceVariant, null)
        TokenCheckState.Checking -> Triple("Checking…", MaterialTheme.colorScheme.onSurfaceVariant, null)
        is TokenCheckState.Done ->
          when (val r = s.result) {
            is TokenCheck.Valid -> Triple("Signed in as ${r.user}", MaterialTheme.colorScheme.primary, Icons.Outlined.CheckCircle)
            is TokenCheck.Rejected -> Triple("Hugging Face rejected this token", MaterialTheme.colorScheme.error, Icons.Outlined.ErrorOutline)
            is TokenCheck.Unreachable -> Triple("Couldn't reach Hugging Face (${r.message})", MaterialTheme.colorScheme.error, Icons.Outlined.CloudOff)
          }
      }
    if (state == TokenCheckState.Checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    icon?.let { Icon(it, null, Modifier.size(18.dp), tint = color) }
    text?.let {
      Text(
        it,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 2,
        modifier = Modifier.padding(start = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
      )
    }
  }
}
