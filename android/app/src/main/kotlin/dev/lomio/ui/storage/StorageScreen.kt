package dev.lomio.ui.storage

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lomio.domain.Storage
import dev.lomio.ui.components.Format

/** One model on the Storage screen. */
data class StorageModelRow(
  val id: String,
  val name: String,
  val quant: String? = null,
  val bytes: Long,
  val lastUsed: Storage.LastUsed = Storage.LastUsed.Never,
  val loaded: Boolean = false,
  /** Download not finished: [bytes] is the partial file. */
  val partial: Boolean = false,
  /** Marked downloaded, but the file is gone. */
  val missing: Boolean = false,
  val unused: Boolean = false,
)

data class StorageUiState(
  val loading: Boolean = true,
  val volumeBytes: Long = 0,
  val freeBytes: Long = 0,
  /** Biggest first. */
  val models: List<StorageModelRow> = emptyList(),
  val chatsBytes: Long = 0,
  val cacheBytes: Long = 0,
  val leftoverBytes: Long = 0,
  val leftoverCount: Int = 0,
  /** A delete or clean-up is running. */
  val busy: Boolean = false,
  val message: String? = null,
) {
  val modelsBytes: Long
    get() = models.sumOf { it.bytes }

  val otherBytes: Long
    get() = chatsBytes + cacheBytes + leftoverBytes

  val lomioBytes: Long
    get() = modelsBytes + otherBytes

  val unused: List<StorageModelRow>
    get() = models.filter { it.unused }

  /** Below this, a typical model download (1–3 GB) won't fit. */
  val lowSpace: Boolean
    get() = volumeBytes > 0 && freeBytes < LOW_SPACE_BYTES

  companion object {
    const val LOW_SPACE_BYTES = 2_000_000_000L
  }
}

class StorageActions(
  val onBack: () -> Unit = {},
  val onDelete: (List<String>) -> Unit = {},
  val onClearCache: () -> Unit = {},
  val onRemoveLeftovers: () -> Unit = {},
  val onMessageShown: () -> Unit = {},
)

/** Lets tests find the screen's list among other scrollables (the drawer). */
const val STORAGE_LIST_TAG = "storage-list"

/** Tabular figures so sizes line up. */
private const val TABULAR = "tnum"

/** Disk usage per model, free space, and clean-up (RESEARCH.md §9.2 "Storage usage"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(state: StorageUiState, actions: StorageActions, modifier: Modifier = Modifier) {
  val snackbar = remember { SnackbarHostState() }
  LaunchedEffect(state.message) {
    state.message?.let {
      snackbar.showSnackbar(it)
      actions.onMessageShown()
    }
  }
  var confirmId by rememberSaveable { mutableStateOf<String?>(null) }
  var reviewUnused by rememberSaveable { mutableStateOf(false) }

  state.models.firstOrNull { it.id == confirmId }?.let { m ->
    DeleteDialog(m, onConfirm = { confirmId = null; actions.onDelete(listOf(m.id)) }, onDismiss = { confirmId = null })
  }
  if (reviewUnused && state.unused.isNotEmpty()) {
    UnusedDialog(state.unused, onConfirm = { ids -> reviewUnused = false; actions.onDelete(ids) }, onDismiss = { reviewUnused = false })
  }

  Scaffold(
    modifier = modifier,
    snackbarHost = { SnackbarHost(snackbar) },
    topBar = {
      Column {
        TopAppBar(
          title = { Text("Storage") },
          navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
      }
    },
  ) { padding ->
    if (state.loading) {
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = "Measuring storage" })
      }
    } else Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().widthIn(max = 720.dp).testTag(STORAGE_LIST_TAG),
      ) {
        item(key = "summary") { Summary(state) }
        if (state.unused.isNotEmpty()) {
          item(key = "unused") { UnusedCard(state.unused, enabled = !state.busy) { reviewUnused = true } }
        }
        item(key = "models-title") { SectionTitle("Models") }
        if (state.models.isEmpty()) {
          item(key = "models-empty") { EmptyModels() }
        } else {
          item(key = "models") {
            val largest = state.models.maxOf { it.bytes }.coerceAtLeast(1)
            Group {
              state.models.forEachIndexed { i, m ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ModelRow(m, share = m.bytes.toFloat() / largest, enabled = !state.busy) { confirmId = m.id }
              }
            }
          }
        }
        item(key = "other-title") { SectionTitle("Other") }
        item(key = "other") { OtherGroup(state, actions) }
        item(key = "footer") {
          Text(
            "Models are kept in Lomio's private storage and are never included in backups. " +
              "Uninstalling Lomio removes them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun Summary(state: StorageUiState) {
  val others = (state.volumeBytes - state.freeBytes - state.lomioBytes).coerceAtLeast(0)
  val segments =
    listOf(
      Segment("Models", state.modelsBytes, MaterialTheme.colorScheme.primary),
      Segment("Chats and other files", state.otherBytes, MaterialTheme.colorScheme.tertiary),
      Segment("Other apps and system", others, MaterialTheme.colorScheme.outline),
      Segment("Free", state.freeBytes, MaterialTheme.colorScheme.surfaceContainerHighest),
    )
  val description =
    "Lomio uses ${Format.bytes(state.lomioBytes)}, of which models ${Format.bytes(state.modelsBytes)}. " +
      "${Format.bytes(state.freeBytes)} free of ${Format.bytes(state.volumeBytes)}."
  Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(20.dp)) {
      Column(Modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        Text("Lomio uses", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
          Format.bytes(state.lomioBytes),
          style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = TABULAR),
          modifier = Modifier.padding(top = 2.dp),
        )
        Text(
          "${Format.bytes(state.freeBytes)} free of ${Format.bytes(state.volumeBytes)}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        UsageBar(segments, Modifier.padding(top = 16.dp, bottom = 12.dp))
        segments.forEach { Legend(it) }
      }
      if (state.lowSpace) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 12.dp)) {
          Icon(Icons.Outlined.WarningAmber, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
          Text(
            "Storage is almost full. A download needs room for the whole model file.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 8.dp),
          )
        }
      }
    }
  }
}

private data class Segment(val label: String, val bytes: Long, val color: Color)

/** Proportional bar; a non-zero slice is always at least a sliver so it stays visible. */
@Composable
private fun UsageBar(segments: List<Segment>, modifier: Modifier = Modifier) {
  val total = segments.sumOf { it.bytes }.coerceAtLeast(1)
  Row(modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    segments.filter { it.bytes > 0 }.forEach { s ->
      Box(Modifier.weight((s.bytes.toFloat() / total).coerceAtLeast(0.012f)).fillMaxSize().background(s.color))
    }
  }
}

@Composable
private fun Legend(s: Segment) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(s.color))
    Text(s.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 10.dp))
    Text(
      Format.bytes(s.bytes),
      style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun UnusedCard(unused: List<StorageModelRow>, enabled: Boolean, onReview: () -> Unit) {
  val bytes = unused.sumOf { it.bytes }
  Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Outlined.CleaningServices, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
      Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
        Text("Free up ${Format.bytes(bytes)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
        Text(
          if (unused.size == 1) "1 model hasn't been used in ${Storage.UNUSED_AFTER_DAYS} days."
          else "${unused.size} models haven't been used in ${Storage.UNUSED_AFTER_DAYS} days.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
      }
      Button(
        onClick = onReview,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary),
      ) {
        Text("Review")
      }
    }
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() },
  )
}

@Composable
private fun Group(content: @Composable () -> Unit) {
  Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.animateContentSize()) { content() }
  }
}

@Composable
private fun ModelRow(m: StorageModelRow, share: Float, enabled: Boolean, onDelete: () -> Unit) {
  val detail =
    when {
      m.missing -> "File missing"
      m.partial -> "Unfinished download"
      else -> listOfNotNull(m.quant, Storage.lastUsedLabel(m.lastUsed)).joinToString(" · ")
    }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
  ) {
    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(m.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (m.loaded) Badge("In use")
      }
      Text(
        detail,
        style = MaterialTheme.typography.bodyMedium,
        color = if (m.missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
          Box(Modifier.fillMaxWidth(share.coerceIn(0.02f, 1f)).fillMaxHeight().clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
        Text(
          Format.bytes(m.bytes),
          style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR),
          textAlign = TextAlign.End,
          modifier = Modifier.width(84.dp),
        )
      }
    }
    IconButton(onClick = onDelete, enabled = enabled) { Icon(Icons.Outlined.Delete, "Delete ${m.name}") }
  }
}

@Composable
private fun Badge(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onPrimaryContainer,
    maxLines = 1,
    modifier =
      Modifier.padding(start = 8.dp)
        .clip(RoundedCornerShape(50))
        .background(MaterialTheme.colorScheme.primaryContainer)
        .padding(horizontal = 8.dp, vertical = 2.dp),
  )
}

@Composable
private fun EmptyModels() {
  Group {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(Icons.Outlined.Inventory2, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
      Text("No models yet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
      Text(
        "Models you download or import show up here with their size.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}

@Composable
private fun OtherGroup(state: StorageUiState, actions: StorageActions) {
  Group {
    OtherRow(Icons.AutoMirrored.Outlined.Chat, "Chat history", "Your conversations and their search index", state.chatsBytes)
    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
    OtherRow(Icons.Outlined.Cached, "Temporary files", "Safe to clear at any time", state.cacheBytes) {
      TextButton(onClick = actions.onClearCache, enabled = state.cacheBytes > 0 && !state.busy) { Text("Clear") }
    }
    if (state.leftoverCount > 0) {
      HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
      OtherRow(
        Icons.Outlined.FolderOff,
        "Leftover files",
        if (state.leftoverCount == 1) "1 file from an unfinished download or import"
        else "${state.leftoverCount} files from unfinished downloads or imports",
        state.leftoverBytes,
      ) {
        TextButton(onClick = actions.onRemoveLeftovers, enabled = !state.busy) { Text("Remove") }
      }
    }
  }
}

@Composable
private fun OtherRow(icon: ImageVector, title: String, subtitle: String, bytes: Long, action: (@Composable () -> Unit)? = null) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = if (action == null) 16.dp else 8.dp, top = 12.dp, bottom = 12.dp),
  ) {
    Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.weight(1f).padding(start = 16.dp).semantics(mergeDescendants = true) {}) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(
      Format.bytes(bytes),
      style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR),
      modifier = Modifier.padding(start = 12.dp, end = if (action == null) 0.dp else 4.dp),
    )
    action?.invoke()
  }
}

@Composable
private fun DeleteDialog(m: StorageModelRow, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(Icons.Outlined.Delete, null) },
    title = { Text(if (m.partial) "Cancel and delete ${m.name}?" else "Delete ${m.name}?") },
    text = {
      Text(
        buildString {
          append("This frees ${Format.bytes(m.bytes)}.")
          if (m.loaded) append(" The model will be unloaded first.")
          append(" Your chats stay; you can download the model again from Models.")
        }
      )
    },
    confirmButton = {
      TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
  )
}

@Composable
private fun UnusedDialog(unused: List<StorageModelRow>, onConfirm: (List<String>) -> Unit, onDismiss: () -> Unit) {
  var picked by remember { mutableStateOf(unused.map { it.id }.toSet()) }
  val bytes = unused.filter { it.id in picked }.sumOf { it.bytes }
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(Icons.Outlined.CleaningServices, null) },
    title = { Text("Delete unused models") },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Not loaded in the last ${Storage.UNUSED_AFTER_DAYS} days. Your chats stay.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        unused.forEach { m ->
          val checked = m.id in picked
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .toggleable(checked, role = Role.Checkbox) { picked = if (it) picked + m.id else picked - m.id }
                .padding(vertical = 4.dp),
          ) {
            Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(horizontal = 8.dp))
            Column(Modifier.weight(1f)) {
              Text(m.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(Storage.lastUsedLabel(m.lastUsed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
              Format.bytes(m.bytes),
              style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR),
              modifier = Modifier.padding(start = 8.dp),
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onConfirm(unused.map { it.id }.filter { it in picked }) },
        enabled = picked.isNotEmpty(),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
      ) {
        Text(if (picked.isEmpty()) "Delete" else "Delete · ${Format.bytes(bytes)}")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
