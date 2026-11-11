package dev.lomio.ui.settings

import androidx.compose.runtime.remember

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
import androidx.compose.material.icons.outlined.FileUpload

import androidx.compose.material.icons.outlined.FileDownload


import dev.lomio.ui.components.ExportFormatDialog

import dev.lomio.ui.ExportFormat
import dev.lomio.ui.components.DrawerMenuButton
import dev.lomio.ui.components.readableWidth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import dev.lomio.R
import dev.lomio.ui.components.systemPromptText

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
  onExportAll: (ExportFormat) -> Unit = {},
  onImport: () -> Unit = {},
  apiServer: ApiServerUiState? = null,
  apiServerActions: ApiServerActions = ApiServerActions(),
) {
  var exportDialog by remember { mutableStateOf(false) }
  if (exportDialog) {
    ExportFormatDialog(stringResource(R.string.export_all_title), onPick = { exportDialog = false; onExportAll(it) }, onDismiss = { exportDialog = false })
  }
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_title)) },
        navigationIcon = { DrawerMenuButton(onOpenDrawer) },
      )
    },
  ) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).readableWidth(720.dp).padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Section(stringResource(R.string.settings_appearance))
      Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        ThemeMode.entries.forEachIndexed { i, mode ->
          SegmentedButton(
            selected = settings.theme == mode,
            onClick = { onChange { it.copy(theme = mode) } },
            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
          ) {
            Text(
              stringResource(
                when (mode) {
                  ThemeMode.SYSTEM -> R.string.theme_system
                  ThemeMode.LIGHT -> R.string.theme_light
                  ThemeMode.DARK -> R.string.theme_dark
                }
              )
            )
          }
        }
      }
      SwitchRow(stringResource(R.string.settings_dynamic_color), stringResource(R.string.settings_dynamic_color_sub), settings.dynamicColor) { v ->
        onChange { it.copy(dynamicColor = v) }
      }

      Section(stringResource(R.string.settings_chat))
      LocalTextField(
        initial = systemPromptText(settings.systemPrompt),
        onValueChange = { v -> onChange { it.copy(systemPrompt = v) } },
        label = { Text(stringResource(R.string.settings_default_system_prompt)) },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
      )
      SwitchRow(stringResource(R.string.settings_show_stats), stringResource(R.string.settings_show_stats_sub), settings.showStats) { v ->
        onChange { it.copy(showStats = v) }
      }
      SwitchRow(stringResource(R.string.settings_keep_screen_on), null, settings.keepScreenOn) { v ->
        onChange { it.copy(keepScreenOn = v) }
      }

      Section(stringResource(R.string.settings_generation_defaults))
      GenerationControls(settings.generation) { g -> onChange { it.copy(generation = g) } }

      Section(stringResource(R.string.settings_models_memory))
      SwitchRow(stringResource(R.string.settings_autoload), stringResource(R.string.settings_autoload_sub), settings.autoLoadLastModel) { v ->
        onChange { it.copy(autoLoadLastModel = v) }
      }
      SliderRow(stringResource(R.string.settings_unload_after), settings.unloadAfterMinutes.toFloat(), 0f..60f, 11, "%.0f") { v ->
        onChange { it.copy(unloadAfterMinutes = (v / 5).roundToInt() * 5) }
      }
      LoadControls(settings.load) { l -> onChange { it.copy(load = l) } }
      GpuControls(settings, gpu, onChange)

      Section("Data")
      Text(
        stringResource(R.string.settings_data_sub),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { exportDialog = true }) {
          Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp))
          Spacer(Modifier.size(8.dp))
          Text(stringResource(R.string.settings_export_chats))
        }
        OutlinedButton(onClick = onImport) {
          Icon(Icons.Outlined.FileUpload, null, Modifier.size(18.dp))
          Spacer(Modifier.size(8.dp))
          Text(stringResource(R.string.settings_import))
        }
      }

      Section(stringResource(R.string.settings_downloads))
      LocalTextField(
        initial = settings.hfToken,
        onValueChange = { v -> onChange { it.copy(hfToken = v.trim()) } },
        label = { Text(stringResource(R.string.settings_hf_token)) },
        supportingText = { Text(stringResource(R.string.settings_hf_token_sub)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
      )
      TokenTestRow(enabled = settings.hfToken.isNotBlank(), state = tokenCheck, onTest = onTestToken)
      Spacer(Modifier.size(24.dp))

      apiServer?.let { ApiServerSection(it, apiServerActions) }
    }
  }
}

@Composable
fun GenerationControls(g: GenerationSettings, onChange: (GenerationSettings) -> Unit) {
  val d = GenerationSettings.DEFAULTS
  SliderRow(stringResource(R.string.gen_temperature), g.temperature ?: d.temperature!!, 0f..2f, 40, "%.2f") { onChange(g.copy(temperature = it)) }
  SliderRow(stringResource(R.string.gen_top_p), g.topP ?: d.topP!!, 0f..1f, 20, "%.2f") { onChange(g.copy(topP = it)) }
  SliderRow(stringResource(R.string.gen_min_p), g.minP ?: d.minP!!, 0f..0.5f, 50, "%.2f") { onChange(g.copy(minP = it)) }
  SliderRow(stringResource(R.string.gen_top_k), (g.topK ?: d.topK!!).toFloat(), 0f..100f, 99, "%.0f") { onChange(g.copy(topK = it.roundToInt())) }
  SliderRow(stringResource(R.string.gen_repeat_penalty), g.repeatPenalty ?: d.repeatPenalty!!, 1f..1.5f, 50, "%.2f") { onChange(g.copy(repeatPenalty = it)) }
  SliderRow(
    stringResource(R.string.gen_max_tokens),
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
  SliderRow(stringResource(R.string.load_ctx), (l.nCtx ?: 4096).toFloat(), 512f..32768f, 62, "%.0f") {
    onChange(l.copy(nCtx = (it / 512).roundToInt().coerceAtLeast(1) * 512))
  }
  SliderRow(stringResource(R.string.load_threads), (l.nThreads ?: 0).toFloat(), 0f..8f, 7, "%.0f") {
    onChange(l.copy(nThreads = it.roundToInt()))
  }
  SwitchRow(stringResource(R.string.load_mmap), stringResource(R.string.load_mmap_sub), l.useMmap ?: true) {
    onChange(l.copy(useMmap = it))
  }
  Text(
    stringResource(R.string.load_apply_note),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/** "GPU acceleration (experimental)": off by default, only offered on Adreno + OpenCL phones. */
/** GpuSupport's reasons are English data from the domain layer; shown in the user's language. */
@Composable
private fun gpuReason(reason: String): String =
  when (reason) {
    "Not checked" -> stringResource(R.string.gpu_reason_unknown)
    "The GPU backend is only built for 64-bit ARM phones" -> stringResource(R.string.gpu_reason_abi)
    "Needs a Snapdragon (Adreno) GPU" -> stringResource(R.string.gpu_reason_qualcomm)
    "This phone has no OpenCL driver" -> stringResource(R.string.gpu_reason_opencl)
    else -> reason
  }

@Composable
fun GpuControls(settings: AppSettings, gpu: GpuSupport, onChange: ((AppSettings) -> AppSettings) -> Unit) {
  // Turning it off always works, even if the phone no longer qualifies.
  val canToggle = gpu.available || settings.gpuEnabled
  val gpuTitle = stringResource(R.string.gpu_title)
  Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(stringResource(R.string.gpu_title), style = MaterialTheme.typography.bodyLarge)
      Text(
        if (gpu.available) {
          stringResource(R.string.gpu_on_sub)
        } else {
          stringResource(R.string.gpu_unavailable, gpuReason(gpu.reason))
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(
      checked = settings.gpuEnabled,
      onCheckedChange = { v -> onChange { it.copy(gpuEnabled = v) } },
      enabled = canToggle,
      modifier = Modifier.semantics { contentDescription = gpuTitle },
    )
  }
  if (settings.gpuEnabled) {
    // 99 covers every layer of any phone-sized model, so the top of the slider reads "All" (#28).
    val all = stringResource(R.string.gpu_layers_all)
    SliderRow(
      stringResource(R.string.gpu_layers),
      settings.gpuLayers.coerceIn(1, 99).toFloat(),
      1f..99f,
      97,
      "%.0f",
      valueText = { if (it.roundToInt() >= 99) all else it.roundToInt().toString() },
    ) { v ->
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
  /** Overrides [format] for the value shown next to the label. */
  valueText: ((Float) -> String)? = null,
  onChange: (Float) -> Unit,
) {
  Column(Modifier.padding(vertical = 4.dp)) {
    Row {
      Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Text(valueText?.invoke(value) ?: String.format(Locale.getDefault(), format, value), style = MaterialTheme.typography.bodyMedium)
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
    OutlinedButton(onClick = onTest, enabled = enabled && state != TokenCheckState.Checking) { Text(stringResource(R.string.token_test)) }
    Spacer(Modifier.size(12.dp))
    val (text, color, icon) =
      when (val s = state) {
        null -> Triple(null, MaterialTheme.colorScheme.onSurfaceVariant, null)
        TokenCheckState.Checking -> Triple(stringResource(R.string.token_checking), MaterialTheme.colorScheme.onSurfaceVariant, null)
        is TokenCheckState.Done ->
          when (val r = s.result) {
            is TokenCheck.Valid -> Triple(stringResource(R.string.token_valid, r.user), MaterialTheme.colorScheme.primary, Icons.Outlined.CheckCircle)
            is TokenCheck.Rejected -> Triple(stringResource(R.string.token_rejected), MaterialTheme.colorScheme.error, Icons.Outlined.ErrorOutline)
            is TokenCheck.Unreachable -> Triple(stringResource(R.string.token_unreachable, r.message), MaterialTheme.colorScheme.error, Icons.Outlined.CloudOff)
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
