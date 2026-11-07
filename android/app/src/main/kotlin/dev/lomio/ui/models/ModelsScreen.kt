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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lomio.data.db.ModelState
import dev.lomio.domain.Fit
import dev.lomio.ui.components.Format

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
) {
  val progress: Float
    get() = if (sizeBytes > 0) (bytesDone.toFloat() / sizeBytes).coerceIn(0f, 1f) else 0f
}

data class RecommendedModel(val repo: String, val title: String, val note: String)

data class ModelsUiState(
  val models: List<ModelCardState> = emptyList(),
  val recommended: List<RecommendedModel> = emptyList(),
  val message: String? = null,
)

class ModelsActions(
  val onOpenDrawer: () -> Unit = {},
  val onSearchHf: () -> Unit = {},
  val onOpenRepo: (String) -> Unit = {},
  val onImport: () -> Unit = {},
  val onLoad: (String) -> Unit = {},
  val onUnload: () -> Unit = {},
  val onDelete: (String) -> Unit = {},
  val onPause: (String) -> Unit = {},
  val onResume: (String) -> Unit = {},
  val onMessageShown: () -> Unit = {},
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
        title = { Text("Models") },
        navigationIcon = { IconButton(onClick = actions.onOpenDrawer) { Icon(Icons.Outlined.Menu, "Open menu") } },
        actions = { IconButton(onClick = actions.onSearchHf) { Icon(Icons.Outlined.Search, "Search Hugging Face") } },
      )
    },
    floatingActionButton = {
      Column(horizontalAlignment = Alignment.End) {
        DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
          DropdownMenuItem(
            text = { Text("Search Hugging Face") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            onClick = { fabMenu = false; actions.onSearchHf() },
          )
          DropdownMenuItem(
            text = { Text("Import local file") },
            leadingIcon = { Icon(Icons.Outlined.FileOpen, null) },
            onClick = { fabMenu = false; actions.onImport() },
          )
        }
        ExtendedFloatingActionButton(
          onClick = { fabMenu = true },
          icon = { Icon(Icons.Outlined.Add, null) },
          text = { Text("Add model") },
        )
      }
    },
  ) { padding ->
    LazyColumn(
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxSize().padding(padding),
    ) {
      item { SectionTitle("On this device") }
      if (state.models.isEmpty()) {
        item {
          Text(
            "No models yet. Download one below, search Hugging Face, or import a .gguf file.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      items(state.models, key = { it.id }) { ModelCard(it, actions) }
      if (state.recommended.isNotEmpty()) {
        item { SectionTitle("Recommended to start") }
        items(state.recommended, key = { it.repo }) { r ->
          OutlinedCard(Modifier.fillMaxWidth().clickable { actions.onOpenRepo(r.repo) }) {
            Column(Modifier.padding(16.dp)) {
              Text(r.title, style = MaterialTheme.typography.titleMedium)
              Text(r.repo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(r.note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
          }
        }
      }
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
        Text(m.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(m.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOfNotNull(Format.params(m.params), m.quant, Format.bytes(m.sizeBytes), Format.context(m.contextLength)?.let { "$it ctx" })
          .forEach { AssistChip(onClick = {}, label = { Text(it, style = MaterialTheme.typography.labelSmall) }) }
      }
      if (m.fit == Fit.WONT_FIT || m.fit == Fit.TIGHT) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Outlined.WarningAmber, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
          Spacer(Modifier.size(6.dp))
          Text(
            if (m.fit == Fit.WONT_FIT) "May not fit in memory" else "Tight on memory",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
      when (m.state) {
        ModelState.DOWNLOADING, ModelState.PAUSED -> {
          LinearProgressIndicator(progress = { m.progress }, modifier = Modifier.fillMaxWidth())
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              "${(m.progress * 100).toInt()}% · ${Format.bytes(m.bytesDone)} of ${Format.bytes(m.sizeBytes)}" +
                if (m.state == ModelState.PAUSED) " · paused" else "",
              style = MaterialTheme.typography.labelMedium,
              modifier = Modifier.weight(1f),
            )
            if (m.state == ModelState.DOWNLOADING) TextButton(onClick = { actions.onPause(m.id) }) { Text("Pause") }
            else TextButton(onClick = { actions.onResume(m.id) }) { Text("Resume") }
            TextButton(onClick = { actions.onDelete(m.id) }) { Text("Cancel") }
          }
        }
        ModelState.ERROR -> {
          Text(m.error ?: "Download failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          Row {
            TextButton(onClick = { actions.onResume(m.id) }) { Text("Retry") }
            TextButton(onClick = { actions.onDelete(m.id) }) { Text("Remove") }
          }
        }
        ModelState.READY ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            when {
              m.loading -> OutlinedButton(onClick = {}, enabled = false) { Text("Loading…") }
              m.loaded -> OutlinedButton(onClick = actions.onUnload) { Text("Unload") }
              else -> Button(onClick = { actions.onLoad(m.id) }) { Text("Load") }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { actions.onDelete(m.id) }) { Icon(Icons.Outlined.Delete, "Delete ${m.name}") }
          }
        ModelState.REMOTE -> Unit
      }
      if (expanded) {
        Column {
          listOfNotNull(
              m.arch?.let { "Architecture: $it" },
              m.layers?.let { "Layers: $it" },
              m.contextLength?.let { "Trained context: $it tokens" },
              m.filePath?.let { "File: $it" },
            )
            .forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
      }
    }
  }
}
