package dev.lomio.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lomio.data.hf.HfFile
import dev.lomio.data.hf.HfModel
import dev.lomio.data.hf.HfSort
import dev.lomio.domain.Fit
import dev.lomio.ui.components.Format

data class HfFileRow(val file: HfFile, val fit: Fit?, val alreadyAdded: Boolean)

data class HfUiState(
  val query: String = "",
  val sort: HfSort = HfSort.TRENDING,
  val loading: Boolean = false,
  val results: List<HfModel> = emptyList(),
  val error: String? = null,
  /** Non-null when a repo is open. */
  val repo: String? = null,
  val files: List<HfFileRow> = emptyList(),
  val filesLoading: Boolean = false,
)

class HfActions(
  val onBack: () -> Unit = {},
  val onQuery: (String) -> Unit = {},
  val onSort: (HfSort) -> Unit = {},
  val onOpenRepo: (String) -> Unit = {},
  val onDownload: (HfFile) -> Unit = {},
)

/** Hugging Face search + repo detail (RESEARCH.md §9.2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HfSearchScreen(state: HfUiState, actions: HfActions, modifier: Modifier = Modifier) {
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(state.repo ?: "Hugging Face", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      if (state.repo == null) SearchPane(state, actions) else RepoPane(state, actions)
    }
  }
}

@Composable
private fun SearchPane(state: HfUiState, actions: HfActions) {
  OutlinedTextField(
    value = state.query,
    onValueChange = actions.onQuery,
    placeholder = { Text("Search GGUF models") },
    leadingIcon = { Icon(Icons.Outlined.Search, null) },
    singleLine = true,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
  )
  Row(
    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    HfSort.entries.forEach { s ->
      FilterChip(selected = s == state.sort, onClick = { actions.onSort(s) }, label = { Text(s.label) })
    }
  }
  when {
    state.loading -> Centered { CircularProgressIndicator() }
    state.error != null -> Centered { Text(state.error, color = MaterialTheme.colorScheme.error) }
    else ->
      LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(state.results, key = { it.id }) { m ->
          Column(Modifier.fillMaxWidth().clickable { actions.onOpenRepo(m.id) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(m.id.substringAfter('/'), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
              if (m.gated) Icon(Icons.Outlined.Lock, "Gated", Modifier.size(16.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(m.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Spacer(Modifier.size(12.dp))
              Icon(Icons.Outlined.Download, null, Modifier.size(14.dp))
              Text(Format.count(m.downloads), style = MaterialTheme.typography.bodySmall)
              Spacer(Modifier.size(8.dp))
              Icon(Icons.Outlined.FavoriteBorder, null, Modifier.size(14.dp))
              Text(Format.count(m.likes), style = MaterialTheme.typography.bodySmall)
            }
          }
          HorizontalDivider()
        }
      }
  }
}

@Composable
private fun RepoPane(state: HfUiState, actions: HfActions) {
  if (state.filesLoading) return Centered { CircularProgressIndicator() }
  if (state.error != null) return Centered { Text(state.error, color = MaterialTheme.colorScheme.error) }
  LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
    item {
      Text(
        "Available GGUF files",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(16.dp),
      )
    }
    if (state.files.isEmpty()) item { Text("No GGUF files in this repo.", Modifier.padding(16.dp)) }
    items(state.files, key = { it.file.path }) { row ->
      val f = row.file
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(f.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
          val notes =
            listOfNotNull(
              Format.bytes(f.sizeBytes),
              when {
                f.isProjector -> "Vision projector (not supported yet)"
                f.splitPart != null -> "Split file (not supported yet)"
                row.fit == Fit.FITS -> "Fits"
                row.fit == Fit.TIGHT -> "Tight"
                row.fit == Fit.WONT_FIT -> "Won't fit"
                else -> null
              },
            )
          Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val supported = !f.isProjector && f.splitPart == null
        FilledTonalButton(onClick = { actions.onDownload(f) }, enabled = supported && !row.alreadyAdded) {
          Text(if (row.alreadyAdded) "Added" else "Download")
        }
      }
      HorizontalDivider()
    }
  }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}
