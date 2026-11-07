package dev.lomio.ui.drawer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lomio.data.db.ConversationSummary
import dev.lomio.domain.HistoryBucket
import dev.lomio.ui.chat.TextInputDialog

enum class Destination(val label: String, val icon: ImageVector) {
  CHAT("Chat", Icons.Outlined.ChatBubbleOutline),
  MODELS("Models", Icons.Outlined.Inventory2),
  BENCHMARK("Benchmark", Icons.Outlined.Speed),
  SETTINGS("Settings", Icons.Outlined.Settings),
  ABOUT("About", Icons.Outlined.Info),
}

class DrawerActions(
  val onNewChat: () -> Unit = {},
  val onNavigate: (Destination) -> Unit = {},
  val onOpenChat: (String) -> Unit = {},
  val onSearch: (String) -> Unit = {},
  val onPin: (String, Boolean) -> Unit = { _, _ -> },
  val onRename: (String, String) -> Unit = { _, _ -> },
  val onDelete: (String) -> Unit = {},
  /** Opens the message search results for the query. */
  val onOpenSearch: (String) -> Unit = {},
)

/** Drawer per RESEARCH.md §9.0: new chat, destinations, searchable date-grouped history. */
@Composable
fun DrawerContent(
  current: Destination,
  currentChatId: String?,
  query: String,
  history: List<Pair<HistoryBucket, List<ConversationSummary>>>,
  actions: DrawerActions,
) {
  var renaming by remember { mutableStateOf<ConversationSummary?>(null) }
  var deleting by remember { mutableStateOf<ConversationSummary?>(null) }
  ModalDrawerSheet {
    Column(Modifier.padding(horizontal = 12.dp)) {
      FilledTonalButton(
        onClick = actions.onNewChat,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
      ) {
        Icon(Icons.Outlined.Add, null)
        Spacer(Modifier.size(8.dp))
        Text("New chat")
      }
      Destination.entries.forEach { d ->
        NavigationDrawerItem(
          label = { Text(d.label) },
          icon = { Icon(d.icon, null) },
          selected = d == current,
          onClick = { actions.onNavigate(d) },
        )
      }
      HorizontalDivider(Modifier.padding(vertical = 8.dp))
      OutlinedTextField(
        value = query,
        onValueChange = actions.onSearch,
        placeholder = { Text("Search chats") },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) actions.onOpenSearch(query) }),
        modifier = Modifier.fillMaxWidth(),
      )
    }
    LazyColumn(Modifier.padding(horizontal = 12.dp)) {
      if (query.isNotBlank()) {
        item(key = "search-messages") {
          NavigationDrawerItem(
            label = { Text("Search messages for “${query.trim()}”", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            icon = { Icon(Icons.AutoMirrored.Outlined.ManageSearch, null) },
            selected = false,
            onClick = { actions.onOpenSearch(query) },
            modifier = Modifier.padding(top = 8.dp),
          )
        }
      }
      if (history.isEmpty()) {
        item {
          Text(
            if (query.isBlank()) "No chats yet" else "No chats match \"$query\"",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
          )
        }
      }
      history.forEach { (bucket, items) ->
        item(key = "h-${bucket.name}") {
          Text(
            bucket.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
          )
        }
        items(items, key = { it.id }) { c ->
          HistoryRow(
            c,
            selected = c.id == currentChatId && current == Destination.CHAT,
            actions = actions,
            onRename = { renaming = c },
            onDelete = { deleting = c },
          )
        }
      }
    }
  }
  deleting?.let { c ->
    AlertDialog(
      onDismissRequest = { deleting = null },
      title = { Text("Delete chat?") },
      text = { Text("“${c.title}” and all its messages will be deleted from this device.") },
      confirmButton = {
        TextButton(onClick = { deleting = null; actions.onDelete(c.id) }) {
          Text("Delete", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
    )
  }
  renaming?.let { c ->
    TextInputDialog("Rename chat", c.title, singleLine = true, onDismiss = { renaming = null }) {
      actions.onRename(c.id, it)
      renaming = null
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
  c: ConversationSummary,
  selected: Boolean,
  actions: DrawerActions,
  onRename: () -> Unit,
  onDelete: () -> Unit,
) {
  var menu by remember { mutableStateOf(false) }
  Box {
    Surface(
      color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0f),
      shape = RoundedCornerShape(28.dp),
      modifier =
        Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(28.dp))
          .combinedClickable(
            onClick = { actions.onOpenChat(c.id) },
            onLongClick = { menu = true },
            onLongClickLabel = "Chat options",
          ),
    ) {
      Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (c.pinned) {
          Icon(Icons.Outlined.PushPin, "Pinned", Modifier.size(14.dp))
          Spacer(Modifier.size(8.dp))
        }
        Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
      }
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
      DropdownMenuItem(
        text = { Text(if (c.pinned) "Unpin" else "Pin") },
        leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
        onClick = { menu = false; actions.onPin(c.id, !c.pinned) },
      )
      DropdownMenuItem(
        text = { Text("Rename") },
        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
        onClick = { menu = false; onRename() },
      )
      DropdownMenuItem(
        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
        onClick = { menu = false; onDelete() },
      )
    }
  }
}
