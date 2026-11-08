package dev.lomio.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lomio.data.db.ModelState
import dev.lomio.domain.Fit
import dev.lomio.ui.components.Format
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.lomio.R
import dev.lomio.data.download.DownloadRunner

data class ModelCardState(
  val id: String,
  val name: String,
  val subtitle: String,
  val quant: String? = null,
  val sizeBytes: Long = 0,
  val params: Long? = null,
  val contextLength: Int? = null,
  val arch: String? = null,
  val layers: Int? = null,
  val filePath: String? = null,
  val state: ModelState = ModelState.READY,
  val bytesDone: Long = 0,
  val error: String? = null,
  val loaded: Boolean = false,
  val loading: Boolean = false,
  val fit: Fit? = null,
  /** Comes with a vision projector: can read images. */
  val vision: Boolean = false,
  /** Files the weights are split into (gguf-split); 1 for an ordinary model. */
  val parts: Int = 1,
) {
  val progress: Float
    get() = if (sizeBytes > 0) (bytesDone.toFloat() / sizeBytes).coerceIn(0f, 1f) else 0f
}

/** A curated starter model with one concrete file, so it downloads with one tap. */
data class RecommendedModel(
  val repo: String,
  val file: String,
  val title: String,
  val note: String,
  val quant: String,
  val sizeBytes: Long,
  val sha256: String,
)

data class RecommendedCardState(val model: RecommendedModel, val fit: Fit?, val added: Boolean)

data class ModelsUiState(
  val models: List<ModelCardState> = emptyList(),
  val recommended: List<RecommendedCardState> = emptyList(),
  val message: String? = null,
)

class ModelsActions(
  val onOpenDrawer: () -> Unit = {},
  val onSearchHf: () -> Unit = {},
  val onOpenRepo: (String) -> Unit = {},
  val onDownloadRecommended: (RecommendedModel) -> Unit = {},
  val onImport: () -> Unit = {},
  val onLoad: (String) -> Unit = {},
  val onUnload: () -> Unit = {},
  val onDelete: (String) -> Unit = {},
  val onPause: (String) -> Unit = {},
  val onResume: (String) -> Unit = {},
  val onSettings: (String) -> Unit = {},
  val onMessageShown: () -> Unit = {},
  val onStorage: () -> Unit = {},
)

/** Models screen per RESEARCH.md §9.2. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(state: ModelsUiState, actions: ModelsActions, modifier: Modifier = Modifier) {
  var fabMenu by remember { mutableStateOf(false) }
  val snackbar = remember { SnackbarHostState() }
  LaunchedEffect(state.message) {
    state.message?.let {
      snackbar.showSnackbar(it)
      actions.onMessageShown()
    }
  }
  Scaffold(
    modifier = modifier,
    snackbarHost = { SnackbarHost(snackbar) },
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.models_title)) },
        navigationIcon = { IconButton(onClick = actions.onOpenDrawer) { Icon(Icons.Outlined.Menu, stringResource(R.string.action_open_menu)) } },
        actions = {
          IconButton(onClick = actions.onSearchHf) { Icon(Icons.Outlined.Search, stringResource(R.string.models_search_hf)) }
          IconButton(onClick = actions.onStorage) { Icon(Icons.Outlined.Storage, stringResource(R.string.models_storage)) }
        },
      )
    },
    floatingActionButton = {
      Column(horizontalAlignment = Alignment.End) {
        DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
          DropdownMenuItem(
            text = { Text(stringResource(R.string.models_search_hf)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            onClick = { fabMenu = false; actions.onSearchHf() },
          )
          DropdownMenuItem(
            text = { Text(stringResource(R.string.models_import)) },
            leadingIcon = { Icon(Icons.Outlined.FileOpen, null) },
            onClick = { fabMenu = false; actions.onImport() },
          )
        }
        ExtendedFloatingActionButton(
          onClick = { fabMenu = true },
          icon = { Icon(Icons.Outlined.Add, null) },
          text = { Text(stringResource(R.string.models_add)) },
        )
      }
    },
  ) { padding ->
    LazyColumn(
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxSize().padding(padding),
    ) {
      item { SectionTitle(stringResource(R.string.models_on_device)) }
      if (state.models.isEmpty()) {
        item {
          Text(
            stringResource(R.string.models_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      items(state.models, key = { it.id }) { ModelCard(it, actions) }
      if (state.recommended.isNotEmpty()) {
        item { SectionTitle(stringResource(R.string.models_recommended)) }
        items(state.recommended, key = { it.model.repo }) { RecommendedCard(it, actions) }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecommendedCard(card: RecommendedCardState, actions: ModelsActions) {
  val r = card.model
  OutlinedCard(Modifier.fillMaxWidth().clickable { actions.onOpenRepo(r.repo) }) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Column {
        Text(r.title, style = MaterialTheme.typography.titleMedium)
        Text(r.repo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Text(recommendedNote(r), style = MaterialTheme.typography.bodyMedium)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(r.quant, Format.bytes(r.sizeBytes)).forEach {
          AssistChip(onClick = {}, label = { Text(it, style = MaterialTheme.typography.labelSmall) })
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        FitHint(card.fit, Modifier.weight(1f))
        if (card.added) {
          OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.state_added)) }
        } else {
          Button(onClick = { actions.onDownloadRecommended(r) }) {
            Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.action_download))
          }
        }
      }
    }
  }
}

/** The starter list's notes in the user's language (the catalogue keeps English as data). */
@Composable
fun recommendedNote(r: RecommendedModel): String =
  when (r.repo) {
    "ggml-org/gemma-3-1b-it-GGUF" -> stringResource(R.string.rec_gemma1b)
    "unsloth/Qwen3-1.7B-GGUF" -> stringResource(R.string.rec_qwen17)
    "bartowski/Llama-3.2-3B-Instruct-GGUF" -> stringResource(R.string.rec_llama3b)
    "unsloth/gemma-3-4b-it-GGUF" -> stringResource(R.string.rec_gemma4b)
    else -> r.note
  }

/** "Fits your device" / "Tight on memory" / "May not fit" from the memory estimate. */
@Composable
fun FitHint(fit: Fit?, modifier: Modifier = Modifier) {
  if (fit == null) {
    Spacer(modifier)
    return
  }
  val (text, color, icon) =
    when (fit) {
      Fit.FITS -> Triple(stringResource(R.string.fit_fits), MaterialTheme.colorScheme.primary, Icons.Outlined.CheckCircle)
      Fit.TIGHT -> Triple(stringResource(R.string.fit_tight), MaterialTheme.colorScheme.tertiary, Icons.Outlined.WarningAmber)
      Fit.WONT_FIT -> Triple(stringResource(R.string.fit_wont_fit), MaterialTheme.colorScheme.error, Icons.Outlined.WarningAmber)
    }
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, Modifier.size(16.dp), tint = color)
    Spacer(Modifier.size(6.dp))
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
  }
}

/** Small "Vision" pill for models that can read images. */
@Composable
fun VisionBadge(modifier: Modifier = Modifier) {
  val visionDescription = stringResource(R.string.vision_badge_a11y)
  Surface(
    color = MaterialTheme.colorScheme.tertiaryContainer,
    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    shape = RoundedCornerShape(50),
    modifier = modifier.semantics(mergeDescendants = true) { contentDescription = visionDescription },
  ) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Outlined.Image, null, Modifier.size(14.dp))
      Spacer(Modifier.size(4.dp))
      Text(stringResource(R.string.vision_badge), style = MaterialTheme.typography.labelSmall)
    }
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelCard(m: ModelCardState, actions: ModelsActions) {
  var expanded by remember { mutableStateOf(false) }
  OutlinedCard(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            m.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
          if (m.vision) {
            Spacer(Modifier.size(8.dp))
            VisionBadge()
          }
        }
        Text(m.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOfNotNull(
            Format.params(m.params),
            m.quant,
            Format.bytes(m.sizeBytes),
            if (m.parts > 1) pluralStringResource(R.plurals.model_parts, m.parts, m.parts) else null,
            Format.context(m.contextLength)?.let { stringResource(R.string.models_ctx_chip, it) },
          )
          .forEach { AssistChip(onClick = {}, label = { Text(it, style = MaterialTheme.typography.labelSmall) }) }
      }
      if (m.fit == Fit.WONT_FIT || m.fit == Fit.TIGHT) FitHint(m.fit)
      when (m.state) {
        ModelState.QUEUED, ModelState.DOWNLOADING, ModelState.PAUSED -> {
          if (m.state == ModelState.QUEUED && m.bytesDone == 0L) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          } else {
            LinearProgressIndicator(progress = { m.progress }, modifier = Modifier.fillMaxWidth())
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              when (m.state) {
                ModelState.QUEUED ->
                  if (m.error != null) stringResource(R.string.models_waiting, Format.bytes(m.bytesDone), Format.bytes(m.sizeBytes))
                  else stringResource(R.string.models_queued)
                else ->
                  stringResource(
                    if (m.state == ModelState.PAUSED) R.string.models_progress_paused else R.string.models_progress,
                    (m.progress * 100).toInt(),
                    Format.bytes(m.bytesDone),
                    Format.bytes(m.sizeBytes),
                  )
              },
              style = MaterialTheme.typography.labelMedium,
              modifier = Modifier.weight(1f),
            )
            if (m.state != ModelState.PAUSED) TextButton(onClick = { actions.onPause(m.id) }) { Text(stringResource(R.string.action_pause)) }
            else TextButton(onClick = { actions.onResume(m.id) }) { Text(stringResource(R.string.action_resume)) }
            TextButton(onClick = { actions.onDelete(m.id) }) { Text(stringResource(R.string.action_cancel)) }
          }
          m.error?.takeIf { m.state != ModelState.PAUSED }?.let { raw ->
            val note = if (raw == DownloadRunner.NO_NOTIFICATION) stringResource(R.string.dl_no_notification) else raw
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        ModelState.ERROR -> {
          Text(m.error ?: stringResource(R.string.models_download_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          Row {
            TextButton(onClick = { actions.onResume(m.id) }) { Text(stringResource(R.string.action_retry)) }
            TextButton(onClick = { actions.onDelete(m.id) }) { Text(stringResource(R.string.action_remove)) }
          }
        }
        ModelState.READY ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            when {
              m.loading -> OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.state_loading)) }
              m.loaded -> OutlinedButton(onClick = actions.onUnload) { Text(stringResource(R.string.action_unload)) }
              else -> Button(onClick = { actions.onLoad(m.id) }) { Text(stringResource(R.string.action_load)) }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { actions.onSettings(m.id) }) { Icon(Icons.Outlined.Tune, stringResource(R.string.models_settings_named, m.name)) }
            IconButton(onClick = { actions.onDelete(m.id) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.models_delete_named, m.name)) }
          }
        ModelState.REMOTE -> Unit
      }
      if (expanded) {
        Column {
          listOfNotNull(
              m.arch?.let { stringResource(R.string.models_arch, it) },
              m.layers?.let { stringResource(R.string.models_layers, it) },
              m.contextLength?.let { stringResource(R.string.models_trained_ctx, it) },
              m.filePath?.let { stringResource(R.string.models_file, it) },
            )
            .forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
      }
    }
  }
}
