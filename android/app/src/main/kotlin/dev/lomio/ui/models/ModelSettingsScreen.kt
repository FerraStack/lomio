package dev.lomio.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lomio.domain.Fit
import dev.lomio.domain.GenerationSettings
import dev.lomio.domain.LoadSettings
import dev.lomio.llama.ChatTemplates
import dev.lomio.ui.settings.OverridableGenerationControls
import dev.lomio.ui.settings.OverridableSlider
import dev.lomio.ui.settings.OverridableSwitch
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import dev.lomio.R
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalContext
import dev.lomio.ui.settings.InheritedFrom

enum class TemplateMode(@StringRes val label: Int) { GGUF(R.string.ms_tmpl_gguf), PRESET(R.string.ms_tmpl_preset), CUSTOM(R.string.ms_tmpl_custom) }

sealed interface TemplatePreview {
  data object Loading : TemplatePreview

  data class Rendered(val text: String) : TemplatePreview

  data class Failed(val message: String) : TemplatePreview
}

data class ModelSettingsUiState(
  val modelId: String = "",
  val name: String = "",
  val subtitle: String = "",
  /** Bumped when stored values change from outside the text fields (reset), to refresh them. */
  val version: Int = 0,
  val needsReload: Boolean = false,
  val systemPrompt: String = "",
  val globalSystemPrompt: String = "",
  /** Generation values this model inherits (app defaults + global). */
  val genBase: GenerationSettings = GenerationSettings.DEFAULTS,
  /** This model's own generation overrides. */
  val gen: GenerationSettings = GenerationSettings(),
  val loadBase: LoadSettings = LoadSettings(nCtx = 4096, nThreads = 0, nBatch = 512, useMmap = true),
  val load: LoadSettings = LoadSettings(),
  val trainedContext: Int? = null,
  val contextFit: Fit? = null,
  val templateMode: TemplateMode = TemplateMode.GGUF,
  val presetId: String = ChatTemplates.CHATML.id,
  val customTemplate: String = "",
  val ggufHasTemplate: Boolean = true,
  val preview: TemplatePreview = TemplatePreview.Loading,
)

class ModelSettingsActions(
  val onBack: () -> Unit = {},
  val onRename: (String) -> Unit = {},
  val onSystemPrompt: (String) -> Unit = {},
  val onGen: (GenerationSettings) -> Unit = {},
  val onLoad: (LoadSettings) -> Unit = {},
  val onTemplateMode: (TemplateMode) -> Unit = {},
  val onPreset: (String) -> Unit = {},
  val onCustomTemplate: (String) -> Unit = {},
  val onReload: () -> Unit = {},
  val onReset: () -> Unit = {},
)

/** Batch sizes offered on the slider (llama.cpp n_batch). */
val BATCH_SIZES = listOf(32, 64, 128, 256, 512, 1024, 2048)

/** Per-model settings (RESEARCH.md §9.2): every value inherits the global one until changed here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsScreen(state: ModelSettingsUiState, actions: ModelSettingsActions, modifier: Modifier = Modifier) {
  var confirmReset by remember { mutableStateOf(false) }
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        navigationIcon = {
          IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) }
        },
        title = {
          Column {
            Text(stringResource(R.string.ms_title), maxLines = 1)
            Text(
              state.name,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        },
      )
    },
  ) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      if (state.needsReload) ReloadBanner(state.name, actions.onReload)

      Section(stringResource(R.string.ms_general), first = true)
      KeyedTextField(
        key = "name-${state.version}",
        initial = state.name,
        onValueChange = actions.onRename,
        label = stringResource(R.string.ms_name),
        singleLine = true,
        supporting = state.subtitle,
      )
      KeyedTextField(
        key = "prompt-${state.version}",
        initial = state.systemPrompt,
        onValueChange = actions.onSystemPrompt,
        label = stringResource(R.string.ms_system_prompt),
        minLines = 3,
        supporting =
          if (state.globalSystemPrompt.isBlank()) stringResource(R.string.ms_prompt_empty_none)
          else stringResource(R.string.ms_prompt_empty_global, state.globalSystemPrompt.lineSequence().first().take(60)),
      )

      Section(stringResource(R.string.ms_generation), stringResource(R.string.ms_generation_sub))
      OverridableGenerationControls(state.genBase, state.gen, inheritedFrom = InheritedFrom.GLOBAL, onChange = actions.onGen)

      Section(stringResource(R.string.ms_load), stringResource(R.string.ms_load_sub))
      LoadControls(state, actions.onLoad)

      Section(stringResource(R.string.ms_template), stringResource(R.string.ms_template_sub))
      TemplateControls(state, actions)

      HorizontalDivider(Modifier.padding(top = 16.dp))
      TextButton(
        onClick = { confirmReset = true },
        modifier = Modifier.padding(vertical = 8.dp),
      ) {
        Text(stringResource(R.string.ms_reset_defaults), color = MaterialTheme.colorScheme.error)
      }
      Spacer(Modifier.size(16.dp))
    }
  }

  if (confirmReset) {
    AlertDialog(
      onDismissRequest = { confirmReset = false },
      title = { Text(stringResource(R.string.ms_reset_title)) },
      text = { Text(stringResource(R.string.ms_reset_body, state.name)) },
      confirmButton = {
        TextButton(onClick = { confirmReset = false; actions.onReset() }) { Text(stringResource(R.string.ms_reset)) }
      },
      dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
  }
}

@Composable
private fun ReloadBanner(name: String, onReload: () -> Unit) {
  Surface(
    color = MaterialTheme.colorScheme.secondaryContainer,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
  ) {
    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Outlined.Info, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
      Text(
        stringResource(R.string.ms_reload_note, name),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
      )
      TextButton(onClick = onReload) { Text(stringResource(R.string.ms_reload)) }
    }
  }
}

@Composable
private fun LoadControls(state: ModelSettingsUiState, onLoad: (LoadSettings) -> Unit) {
  val base = state.loadBase
  val own = state.load
  val maxCtx = ((state.trainedContext ?: 32768).coerceIn(512, 32768) / 512) * 512
  val ctxSteps = (maxCtx / 512 - 2).coerceAtLeast(0)
  val res = LocalContext.current.resources
  val auto = stringResource(R.string.ms_auto)
  OverridableSlider(
    stringResource(R.string.ms_ctx),
    (base.nCtx ?: 4096).coerceAtMost(maxCtx).toFloat(),
    own.nCtx?.toFloat(),
    512f..maxCtx.toFloat(),
    ctxSteps,
    InheritedFrom.GLOBAL,
    format = { it.roundToInt().let { n -> res.getQuantityString(R.plurals.tokens_count, n, n) } },
  ) {
    onLoad(own.copy(nCtx = it?.let { v -> ((v / 512).roundToInt().coerceAtLeast(1)) * 512 }))
  }
  state.contextFit?.let { FitLine(it, state.trainedContext) }
  OverridableSlider(
    stringResource(R.string.ms_threads),
    (base.nThreads ?: 0).toFloat(),
    own.nThreads?.toFloat(),
    0f..8f,
    7,
    InheritedFrom.GLOBAL,
    format = { if (it.roundToInt() == 0) auto else it.roundToInt().toString() },
  ) {
    onLoad(own.copy(nThreads = it?.roundToInt()))
  }
  val batchIndex = { n: Int -> BATCH_SIZES.indexOfFirst { it >= n }.let { i -> if (i < 0) BATCH_SIZES.lastIndex else i } }
  OverridableSlider(
    stringResource(R.string.ms_batch),
    batchIndex(base.nBatch ?: 512).toFloat(),
    own.nBatch?.let { batchIndex(it).toFloat() },
    0f..BATCH_SIZES.lastIndex.toFloat(),
    BATCH_SIZES.size - 2,
    InheritedFrom.GLOBAL,
    format = { BATCH_SIZES[it.roundToInt().coerceIn(0, BATCH_SIZES.lastIndex)].toString() },
  ) {
    onLoad(own.copy(nBatch = it?.let { v -> BATCH_SIZES[v.roundToInt().coerceIn(0, BATCH_SIZES.lastIndex)] }))
  }
  OverridableSwitch(
    stringResource(R.string.ms_mmap),
    stringResource(R.string.ms_mmap_sub),
    base.useMmap ?: true,
    own.useMmap,
    InheritedFrom.GLOBAL,
  ) {
    onLoad(own.copy(useMmap = it))
  }
}

@Composable
private fun FitLine(fit: Fit, trained: Int?) {
  val (text, color, icon) =
    when (fit) {
      Fit.FITS -> Triple(stringResource(R.string.ms_fit_fits), MaterialTheme.colorScheme.primary, Icons.Outlined.CheckCircle)
      Fit.TIGHT -> Triple(stringResource(R.string.ms_fit_tight), MaterialTheme.colorScheme.tertiary, Icons.Outlined.WarningAmber)
      Fit.WONT_FIT -> Triple(stringResource(R.string.ms_fit_wont), MaterialTheme.colorScheme.error, Icons.Outlined.WarningAmber)
    }
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
    Icon(icon, null, Modifier.size(16.dp), tint = color)
    Text(
      listOfNotNull(text, trained?.let { stringResource(R.string.ms_trained_on, it.toString()) }).joinToString(" · "),
      style = MaterialTheme.typography.labelMedium,
      color = color,
      modifier = Modifier.padding(start = 6.dp),
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateControls(state: ModelSettingsUiState, actions: ModelSettingsActions) {
  SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
    TemplateMode.entries.forEachIndexed { i, mode ->
      SegmentedButton(
        selected = state.templateMode == mode,
        onClick = { actions.onTemplateMode(mode) },
        shape = SegmentedButtonDefaults.itemShape(i, TemplateMode.entries.size),
      ) {
        Text(stringResource(mode.label), maxLines = 1)
      }
    }
  }
  when (state.templateMode) {
    TemplateMode.GGUF ->
      Text(
        if (state.ggufHasTemplate) stringResource(R.string.ms_tmpl_uses_gguf)
        else stringResource(R.string.ms_tmpl_chatml),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    TemplateMode.PRESET -> PresetPicker(state.presetId, actions.onPreset)
    TemplateMode.CUSTOM ->
      KeyedTextField(
        key = "tmpl-${state.version}",
        initial = state.customTemplate,
        onValueChange = actions.onCustomTemplate,
        label = stringResource(R.string.ms_tmpl_jinja),
        minLines = 6,
        monospace = true,
        supporting = stringResource(R.string.ms_tmpl_jinja_sub),
      )
  }
  TemplatePreviewCard(state.preview)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetPicker(presetId: String, onPreset: (String) -> Unit) {
  var open by remember { mutableStateOf(false) }
  val current = ChatTemplates.preset(presetId) ?: ChatTemplates.CHATML
  ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
    OutlinedTextField(
      value = current.label,
      onValueChange = {},
      readOnly = true,
      label = { Text(stringResource(R.string.ms_preset)) },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
      modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
    )
    ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      ChatTemplates.PRESETS.forEach { p ->
        DropdownMenuItem(text = { Text(p.label) }, onClick = { open = false; onPreset(p.id) })
      }
    }
  }
}

@Composable
private fun TemplatePreviewCard(preview: TemplatePreview) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainer,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
  ) {
    Column(Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
      Text(stringResource(R.string.ms_preview), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 6.dp))
      when (preview) {
        TemplatePreview.Loading ->
          Text(stringResource(R.string.ms_rendering), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is TemplatePreview.Rendered ->
          SelectionContainer {
            Text(
              preview.text,
              style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        is TemplatePreview.Failed ->
          Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            Text(
              stringResource(R.string.ms_tmpl_error, preview.message),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.padding(start = 8.dp),
            )
          }
      }
    }
  }
}

@Composable
private fun Section(title: String, subtitle: String? = null, first: Boolean = false) {
  if (!first) HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 8.dp)) else Spacer(Modifier.size(8.dp))
  Text(
    title,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.semantics { heading() },
  )
  subtitle?.let {
    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/**
 * A text field that owns its text (persisted values round-trip asynchronously and would make
 * the cursor jump); [key] changes when the stored value is replaced from outside, e.g. a reset.
 */
@Composable
private fun KeyedTextField(
  key: String,
  initial: String,
  onValueChange: (String) -> Unit,
  label: String,
  singleLine: Boolean = false,
  minLines: Int = 1,
  monospace: Boolean = false,
  supporting: String? = null,
) {
  var text by rememberSaveable(key) { mutableStateOf(initial) }
  OutlinedTextField(
    value = text,
    onValueChange = {
      text = it
      onValueChange(it)
    },
    label = { Text(label) },
    singleLine = singleLine,
    minLines = minLines,
    textStyle =
      if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
      else MaterialTheme.typography.bodyLarge,
    supportingText = supporting?.let { { Text(it) } },
    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
  )
}

