package dev.lomio.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lomio.data.db.MessageRole
import dev.lomio.domain.UiMessage
import dev.lomio.ui.components.Format
import kotlinx.coroutines.launch

enum class ModelStatus { NONE, LOADING, LOADED, FAILED }

data class PickerModel(
  val id: String,
  val name: String,
  val quant: String?,
  val sizeBytes: Long,
  val loaded: Boolean,
)

data class ChatUiState(
  val conversationId: String? = null,
  val title: String = "New chat",
  val messages: List<UiMessage> = emptyList(),
  val isGenerating: Boolean = false,
  val modelName: String? = null,
  val modelStatus: ModelStatus = ModelStatus.NONE,
  val downloadedModels: List<PickerModel> = emptyList(),
  val lastModel: PickerModel? = null,
  val showStats: Boolean = true,
  val supportsThinking: Boolean = false,
  val thinkingEnabled: Boolean = true,
  val systemPrompt: String? = null,
  val crashedModelName: String? = null,
  /** Why the last model load failed (shown as a banner until dismissed). */
  val loadError: String? = null,
  val error: String? = null,
)

class ChatActions(
  val onOpenDrawer: () -> Unit = {},
  val onSend: (String) -> Unit = {},
  val onStop: () -> Unit = {},
  val onLoadModel: (String) -> Unit = {},
  val onManageModels: () -> Unit = {},
  val onRegenerate: () -> Unit = {},
  val onCopy: (String) -> Unit = {},
  val onEdit: (messageId: String, text: String) -> Unit = { _, _ -> },
  val onToggleThinking: () -> Unit = {},
  val onRename: (String) -> Unit = {},
  val onSetSystemPrompt: (String) -> Unit = {},
  val onNewChat: () -> Unit = {},
  val onDelete: () -> Unit = {},
  val onDismissCrash: () -> Unit = {},
  val onDismissLoadError: () -> Unit = {},
  val onErrorShown: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: ChatUiState, actions: ChatActions, modifier: Modifier = Modifier) {
  var showPicker by rememberSaveable { mutableStateOf(false) }
  var showMenu by remember { mutableStateOf(false) }
  var dialog by rememberSaveable { mutableStateOf<String?>(null) } // "rename" | "system"
  var input by rememberSaveable { mutableStateOf("") }
  var editingId by rememberSaveable { mutableStateOf<String?>(null) }
  val snackbar = remember { SnackbarHostState() }

  LaunchedEffect(state.error) {
    state.error?.let {
      snackbar.showSnackbar(it)
      actions.onErrorShown()
    }
  }

  Scaffold(
    modifier = modifier,
    snackbarHost = { SnackbarHost(snackbar) },
    topBar = {
      CenterAlignedTopAppBar(
        navigationIcon = {
          IconButton(onClick = actions.onOpenDrawer) { Icon(Icons.Outlined.Menu, "Open menu") }
        },
        title = {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { showPicker = true },
          ) {
            Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
              StatusDot(state.modelStatus)
              Spacer(Modifier.size(6.dp))
              Text(
                state.modelName ?: "No model loaded",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Icon(Icons.Outlined.ExpandMore, "Choose model", Modifier.size(16.dp))
            }
          }
        },
        actions = {
          Box {
            IconButton(onClick = { showMenu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
              DropdownMenuItem(text = { Text("System prompt (this chat)") }, onClick = { showMenu = false; dialog = "system" })
              DropdownMenuItem(text = { Text("Rename") }, onClick = { showMenu = false; dialog = "rename" })
              DropdownMenuItem(text = { Text("New chat") }, onClick = { showMenu = false; actions.onNewChat() })
              DropdownMenuItem(text = { Text("Delete") }, onClick = { showMenu = false; actions.onDelete() })
            }
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
      state.crashedModelName?.let {
        WarningBanner(
          "Loading $it crashed last time, so it wasn't loaded automatically. Try a smaller context size or model.",
          actions.onDismissCrash,
        )
      }
      state.loadError?.let {
        WarningBanner(it, actions.onDismissLoadError, actionLabel = "Choose model", onAction = { showPicker = true })
      }
      Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
          state.messages.isEmpty() && state.modelStatus == ModelStatus.LOADING ->
            EmptyState(title = "Loading ${state.modelName ?: "model"}…") { CircularProgressIndicator() }
          state.messages.isEmpty() && state.downloadedModels.isEmpty() ->
            EmptyState(title = "Download a model to start") {
              Button(onClick = actions.onManageModels) {
                Icon(Icons.Outlined.Download, null)
                Spacer(Modifier.size(8.dp))
                Text("Browse models")
              }
            }
          state.messages.isEmpty() && state.modelStatus != ModelStatus.LOADED ->
            EmptyState(title = if (state.modelStatus == ModelStatus.FAILED) "Model failed to load" else "Load a model to chat") {
              state.lastModel?.let { m ->
                Button(onClick = { actions.onLoadModel(m.id) }) { Text("Load ${m.name}") }
              }
              OutlinedButton(onClick = { showPicker = true }) { Text("Choose another") }
            }
          state.messages.isEmpty() -> EmptyState(title = "How can I help?") {}
          else ->
            MessageList(
              messages = state.messages,
              showStats = state.showStats,
              isGenerating = state.isGenerating,
              actions = actions,
              onEdit = { m ->
                editingId = m.id
                input = m.content
              },
            )
        }
      }
      Composer(
        text = input,
        onTextChange = { input = it },
        isGenerating = state.isGenerating,
        canSend = state.modelStatus == ModelStatus.LOADED,
        supportsThinking = state.supportsThinking,
        thinkingEnabled = state.thinkingEnabled,
        onToggleThinking = actions.onToggleThinking,
        onSend = {
          val id = editingId
          if (id != null) actions.onEdit(id, input) else actions.onSend(input)
          input = ""
          editingId = null
        },
        onStop = actions.onStop,
      )
    }
  }

  if (showPicker) {
    ModelPickerSheet(
      models = state.downloadedModels,
      onPick = {
        showPicker = false
        actions.onLoadModel(it)
      },
      onManage = {
        showPicker = false
        actions.onManageModels()
      },
      onDismiss = { showPicker = false },
    )
  }
  when (dialog) {
    "rename" ->
      TextInputDialog("Rename chat", state.title, singleLine = true, onDismiss = { dialog = null }) {
        actions.onRename(it)
        dialog = null
      }
    "system" ->
      TextInputDialog(
        "System prompt (this chat)",
        state.systemPrompt.orEmpty(),
        singleLine = false,
        hint = "Empty = use the model or global default",
        onDismiss = { dialog = null },
      ) {
        actions.onSetSystemPrompt(it)
        dialog = null
      }
  }
}

@Composable
private fun MessageList(
  messages: List<UiMessage>,
  showStats: Boolean,
  isGenerating: Boolean,
  actions: ChatActions,
  onEdit: (UiMessage) -> Unit,
) {
  // Reverse layout keeps the list pinned to the newest message while streaming.
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val reversed = remember(messages) { messages.asReversed() }
  val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
  val lastAssistantId = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
  Box(Modifier.fillMaxSize()) {
    LazyColumn(
      state = listState,
      reverseLayout = true,
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
      modifier = Modifier.fillMaxSize(),
    ) {
      items(reversed, key = { it.id }, contentType = { it.role }) { m ->
        when (m.role) {
          MessageRole.USER ->
            UserMessage(m, onCopy = { actions.onCopy(m.content) }, onEdit = { onEdit(m) })
          else ->
            AssistantMessage(
              m,
              showStats = showStats,
              onCopy = { actions.onCopy(m.content) },
              onRegenerate = if (m.id == lastAssistantId && !isGenerating) actions.onRegenerate else null,
            )
        }
      }
    }
    if (!atBottom) {
      SmallFloatingActionButton(
        onClick = { scope.launch { listState.animateScrollToItem(0) } },
        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
      ) {
        Icon(Icons.Outlined.ArrowDownward, "Jump to latest")
      }
    }
  }
}

@Composable
private fun Composer(
  text: String,
  onTextChange: (String) -> Unit,
  isGenerating: Boolean,
  canSend: Boolean,
  supportsThinking: Boolean,
  thinkingEnabled: Boolean,
  onToggleThinking: () -> Unit,
  onSend: () -> Unit,
  onStop: () -> Unit,
) {
  Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
      // The model is picked from the top bar only; the composer row holds per-message toggles.
      if (supportsThinking) {
        FilterChip(
          selected = thinkingEnabled,
          onClick = onToggleThinking,
          label = { Text("Think") },
          leadingIcon = { Icon(Icons.Outlined.Lightbulb, null, Modifier.size(16.dp)) },
        )
      }
      Row(verticalAlignment = Alignment.Bottom) {
        TextField(
          value = text,
          onValueChange = onTextChange,
          placeholder = { Text("Message") },
          minLines = 1,
          maxLines = 6, // grows to ~6 lines, then scrolls
          shape = RoundedCornerShape(24.dp),
          colors =
            TextFieldDefaults.colors(
              focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              focusedIndicatorColor = Color.Transparent,
              unfocusedIndicatorColor = Color.Transparent,
              disabledIndicatorColor = Color.Transparent,
            ),
          modifier = Modifier.weight(1f).heightIn(min = 52.dp),
        )
        Spacer(Modifier.size(8.dp))
        if (isGenerating) {
          FilledIconButton(onClick = onStop, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Filled.Stop, "Stop")
          }
        } else {
          FilledIconButton(
            onClick = onSend,
            enabled = canSend && text.isNotBlank(),
            modifier = Modifier.size(52.dp),
          ) {
            Icon(Icons.AutoMirrored.Filled.Send, "Send")
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
  models: List<PickerModel>,
  onPick: (String) -> Unit,
  onManage: () -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(Modifier.padding(bottom = 24.dp)) {
      Text(
        "Choose a model",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
      )
      if (models.isEmpty()) {
        Text(
          "No models downloaded yet.",
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      models.forEach { m ->
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().clickable { onPick(m.id) }.padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
          Column(Modifier.weight(1f)) {
            Text(m.name, style = MaterialTheme.typography.bodyLarge)
            Text(
              listOfNotNull(m.quant, Format.bytes(m.sizeBytes)).joinToString(" · "),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          if (m.loaded) {
            Text("Loaded", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
          }
        }
      }
      TextButton(onClick = onManage, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Manage models") }
    }
  }
}

@Composable
private fun StatusDot(status: ModelStatus) {
  val color =
    when (status) {
      ModelStatus.LOADED -> Color(0xFF2E7D32)
      ModelStatus.LOADING -> Color(0xFFF9A825)
      ModelStatus.FAILED -> MaterialTheme.colorScheme.error
      ModelStatus.NONE -> MaterialTheme.colorScheme.outline
    }
  Box(Modifier.size(8.dp).background(color, CircleShape))
}

@Composable
private fun WarningBanner(
  text: String,
  onDismiss: () -> Unit,
  actionLabel: String? = null,
  onAction: () -> Unit = {},
) {
  Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
      Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
        Spacer(Modifier.size(12.dp))
        Text(
          text,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onErrorContainer,
          modifier = Modifier.weight(1f),
        )
      }
      // Buttons use the banner's own content color: primary (teal) on red fails contrast.
      val buttonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
      Row(Modifier.align(Alignment.End)) {
        if (actionLabel != null) TextButton(onClick = onAction, colors = buttonColors) { Text(actionLabel) }
        TextButton(onClick = onDismiss, colors = buttonColors) { Text("Dismiss") }
      }
    }
  }
}

@Composable
private fun EmptyState(title: String, content: @Composable () -> Unit) {
  Column(
    Modifier.fillMaxSize().padding(32.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    content()
  }
}

@Composable
fun TextInputDialog(
  title: String,
  initial: String,
  singleLine: Boolean,
  hint: String? = null,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit,
) {
  var value by rememberSaveable { mutableStateOf(initial) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 4,
        supportingText = hint?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Save") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
