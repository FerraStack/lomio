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
import androidx.compose.material.icons.outlined.Image
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.lomio.R

/** @param vision the repo has a vision projector, which downloads together with this file. */
data class HfFileRow(val file: HfFile, val fit: Fit?, val alreadyAdded: Boolean, val vision: Boolean = false)

data class HfUiState(
  val query: String = "",
  val sort: HfSort = HfSort.TRENDING,
  val loading: Boolean = false,
  val results: List<HfModel> = emptyList(),
  val error: String? = null,
  /** Non-null when a repo is open. */
  val repo: String? = null,
  val files: List<HfFileRow> = emptyList(),
  /** The open repo's vision projector (mmproj), downloaded with whichever quant is picked. */
  val projector: HfFile? = null,
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
        title = { Text(state.repo ?: stringResource(R.string.hf_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
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
    placeholder = { Text(stringResource(R.string.hf_search_hint)) },
    leadingIcon = { Icon(Icons.Outlined.Search, null) },
    singleLine = true,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
  )
  Row(
    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    HfSort.entries.forEach { s ->
      FilterChip(selected = s == state.sort, onClick = { actions.onSort(s) }, label = { Text(stringResource(s.labelRes())) })
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
              if (m.gated) Icon(Icons.Outlined.Lock, stringResource(R.string.hf_gated), Modifier.size(16.dp))
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
        stringResource(R.string.hf_files_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(16.dp),
      )
    }
    if (state.files.isEmpty()) item { Text(stringResource(R.string.hf_no_files), Modifier.padding(16.dp)) }
    state.projector?.let { p ->
      item {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Outlined.Image, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
          Spacer(Modifier.size(8.dp))
          Text(
            stringResource(R.string.hf_vision_note, Format.bytes(p.sizeBytes)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    // Projector files aren't models on their own; they come along with a model file.
    items(state.files.filter { !it.file.isProjector }, key = { it.file.path }) { row ->
      val f = row.file
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              f.fileName,
              style = MaterialTheme.typography.bodyMedium,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false),
            )
            if (row.vision) {
              Spacer(Modifier.size(8.dp))
              VisionBadge()
            }
          }
          val notes =
            listOfNotNull(
              Format.bytes(f.sizeBytes),
              // A split model is one row: all parts download together, it loads via the first.
              if (f.isSplit) pluralStringResource(R.plurals.model_parts, f.parts.size, f.parts.size) else null,
              when {
                f.isIncompleteSplit -> stringResource(R.string.hf_split)
                row.fit == Fit.FITS -> stringResource(R.string.hf_fits)
                row.fit == Fit.TIGHT -> stringResource(R.string.hf_tight)
                row.fit == Fit.WONT_FIT -> stringResource(R.string.hf_wont_fit)
                else -> null
              },
            )
          Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val supported = !f.isIncompleteSplit
        FilledTonalButton(onClick = { actions.onDownload(f) }, enabled = supported && !row.alreadyAdded) {
          Text(stringResource(if (row.alreadyAdded) R.string.state_added else R.string.action_download))
        }
      }
      HorizontalDivider()
    }
  }
}

private fun HfSort.labelRes(): Int =
  when (this) {
    HfSort.TRENDING -> R.string.hf_sort_trending
    HfSort.DOWNLOADS -> R.string.hf_sort_downloads
    HfSort.LIKES -> R.string.hf_sort_likes
    HfSort.UPDATED -> R.string.hf_sort_updated
  }

@Composable
private fun Centered(content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}
