package dev.lomio.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lomio.data.db.MessageRole
import dev.lomio.domain.UiMessage
import dev.lomio.ui.ExportFormat
import dev.lomio.ui.components.ExportFormatDialog
import kotlinx.coroutines.delay
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SheetState
import dev.lomio.ui.settings.OverridableGenerationControls
import dev.lomio.domain.GenerationSettings
import dev.lomio.ui.components.Format
import kotlinx.coroutines.launch

enum class ModelStatus { NONE, LOADING, LOADED, FAILED }

data class PickerModel(
  val id: String,
  val name: String,
  val quant: String?,
  val sizeBytes: Long,
  val loaded: Boolean,
  val vision: Boolean = false,
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
  /** The loaded model has a vision projector: show the attach-image button. */
  val supportsVision: Boolean = false,
  /** Images picked for the next message (absolute paths). */
  val attachments: List<String> = emptyList(),
  val thinkingEnabled: Boolean = true,
  val systemPrompt: String? = null,
  val crashedModelName: String? = null,
  /** GPU acceleration fell back to the CPU, or was turned off after a crash. */
  val gpuNotice: String? = null,
  /** Why the last model load failed (shown as a banner until dismissed). */
  val loadError: String? = null,
  val error: String? = null,
  /** A message to scroll to and briefly highlight (opened from search). */
  val scrollToMessageId: String? = null,
  /** What this chat inherits (global, then the loaded model's settings). */
  val genBase: GenerationSettings = GenerationSettings.DEFAULTS,
  /** This chat's own overrides. */
  val gen: GenerationSettings = GenerationSettings(),
)

class ChatActions(
  val onOpenDrawer: () -> Unit = {},
  val onSend: (String) -> Unit = {},
  val onAttach: (List<Uri>) -> Unit = {},
  val onRemoveAttachment: (String) -> Unit = {},
  val onStop: () -> Unit = {},
  val onLoadModel: (String) -> Unit = {},
  val onManageModels: () -> Unit = {},
  /** Regenerate the given assistant reply (adds a version). */
  val onRegenerate: (messageId: String) -> Unit = {},
  val onCopy: (String) -> Unit = {},
  val onShare: (String) -> Unit = {},
  val onDeleteMessage: (messageId: String) -> Unit = {},
  /** Show the previous (-1) or next (+1) version of a message. */
  val onStepVersion: (messageId: String, step: Int) -> Unit = { _, _ -> },
  val onEdit: (messageId: String, text: String) -> Unit = { _, _ -> },
  val onToggleThinking: () -> Unit = {},
  val onRename: (String) -> Unit = {},
  val onSetSystemPrompt: (String) -> Unit = {},
  val onSetGeneration: (GenerationSettings) -> Unit = {},
  val onSaveGenerationAsModelDefault: () -> Unit = {},
  val onOpenModelSettings: () -> Unit = {},
  val onExport: (ExportFormat) -> Unit = {},
  val onNewChat: () -> Unit = {},
  val onDelete: () -> Unit = {},
  val onDismissCrash: () -> Unit = {},
  val onDismissGpuNotice: () -> Unit = {},
  val onDismissLoadError: () -> Unit = {},
  val onErrorShown: () -> Unit = {},
  val onScrolledToMessage: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: ChatUiState, actions: ChatActions, modifier: Modifier = Modifier) {
  var showPicker by rememberSaveable { mutableStateOf(false) }
  var showMenu by remember { mutableStateOf(false) }
  var dialog by rememberSaveable { mutableStateOf<String?>(null) } // "rename" | "system" | "gen"
  var input by rememberSaveable { mutableStateOf("") }
  var editingId by rememberSaveable { mutableStateOf<String?>(null) }
  var deleting by remember { mutableStateOf<UiMessage?>(null) }
  val snackbar = remember { SnackbarHostState() }
  // System Photo Picker: no storage permission; we copy what the user picks.
  val pickImages =
    rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
      if (uris.isNotEmpty()) actions.onAttach(uris)
    }

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
              DropdownMenuItem(text = { Text("Generation settings (this chat)") }, onClick = { showMenu = false; dialog = "gen" })
              DropdownMenuItem(text = { Text("System prompt (this chat)") }, onClick = { showMenu = false; dialog = "system" })
              if (state.modelStatus == ModelStatus.LOADED) {
                DropdownMenuItem(text = { Text("Model settings") }, onClick = { showMenu = false; actions.onOpenModelSettings() })
              }
              DropdownMenuItem(text = { Text("Rename") }, onClick = { showMenu = false; dialog = "rename" })
              if (state.conversationId != null) {
                DropdownMenuItem(text = { Text("Export chat") }, onClick = { showMenu = false; dialog = "export" })
              }
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
      state.gpuNotice?.let { WarningBanner(it, actions.onDismissGpuNotice) }
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
              onDelete = { deleting = it },
              scrollToId = state.scrollToMessageId,
            )
        }
      }
      Composer(
        editing = editingId != null,
        onCancelEdit = {
          editingId = null
          input = ""
        },
        text = input,
        onTextChange = { input = it },
        isGenerating = state.isGenerating,
        canSend = state.modelStatus == ModelStatus.LOADED,
        supportsThinking = state.supportsThinking,
        thinkingEnabled = state.thinkingEnabled,
        onToggleThinking = actions.onToggleThinking,
        supportsVision = state.supportsVision,
        attachments = state.attachments,
        onAttach = {
          pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onRemoveAttachment = actions.onRemoveAttachment,
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

  deleting?.let { m ->
    val user = m.role == MessageRole.USER
    AlertDialog(
      onDismissRequest = { deleting = null },
      title = { Text(if (user) "Delete message?" else "Delete reply?") },
      text = {
        Text(
          when {
            user -> "This message and the replies under it will be deleted."
            m.versionCount > 1 -> "This version of the reply will be deleted. The other versions stay."
            else -> "This reply will be deleted."
          }
        )
      },
      confirmButton = {
        TextButton(onClick = { deleting = null; actions.onDeleteMessage(m.id) }) {
          Text("Delete", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
    )
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
    "gen" ->
      ChatGenerationSheet(
        state = state,
        onChange = actions.onSetGeneration,
        onSaveAsModelDefault = {
          actions.onSaveGenerationAsModelDefault()
          dialog = null
        },
        onDismiss = { dialog = null },
      )
    "export" ->
      ExportFormatDialog(
        "Export chat",
        onPick = {
          dialog = null
          actions.onExport(it)
        },
        onDismiss = { dialog = null },
      )
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
  onDelete: (UiMessage) -> Unit,
  scrollToId: String?,
) {
  // Reverse layout keeps the list pinned to the newest message while streaming.
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val reversed = remember(messages) { messages.asReversed() }
  val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
  var flashId by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(scrollToId, reversed) {
    val index = reversed.indexOfFirst { it.id == scrollToId }
    if (scrollToId == null || index < 0) return@LaunchedEffect
    listState.scrollToItem(index)
    actions.onScrolledToMessage()
    flashId = scrollToId
    delay(1600)
    flashId = null
  }
  Box(Modifier.fillMaxSize()) {
    LazyColumn(
      state = listState,
      reverseLayout = true,
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
      modifier = Modifier.fillMaxSize(),
    ) {
      items(reversed, key = { it.id }, contentType = { it.role }) { m ->
        val messageActions =
          MessageActions(
            onCopy = { actions.onCopy(m.content) },
            onShare = { actions.onShare(m.content) },
            onDelete = { onDelete(m) },
            onEdit = { onEdit(m) },
            onRegenerate = if (!isGenerating) ({ actions.onRegenerate(m.id) }) else null,
            onStepVersion = if (!isGenerating) ({ step -> actions.onStepVersion(m.id, step) }) else null,
          )
        val flash by animateColorAsState(
          if (m.id == flashId) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent,
          animationSpec = tween(400),
          label = "search-highlight",
        )
        val itemModifier = Modifier.background(flash, RoundedCornerShape(12.dp))
        when (m.role) {
          MessageRole.USER -> UserMessage(m, messageActions, itemModifier)
          else -> AssistantMessage(m, showStats = showStats, actions = messageActions, modifier = itemModifier)
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
  editing: Boolean,
  onCancelEdit: () -> Unit,
  text: String,
  onTextChange: (String) -> Unit,
  isGenerating: Boolean,
  canSend: Boolean,
  supportsThinking: Boolean,
  thinkingEnabled: Boolean,
  onToggleThinking: () -> Unit,
  supportsVision: Boolean,
  attachments: List<String>,
  onAttach: () -> Unit,
  onRemoveAttachment: (String) -> Unit,
  onSend: () -> Unit,
  onStop: () -> Unit,
) {
  Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
      if (editing) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 4.dp),
        ) {
          Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
          Text(
            "Editing message · sends as a new version",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
          )
          TextButton(onClick = onCancelEdit) { Text("Cancel") }
        }
      }
      if (attachments.isNotEmpty()) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
        ) {
          attachments.forEachIndexed { i, path -> AttachmentChip(path, i + 1, onRemove = { onRemoveAttachment(path) }) }
        }
      }
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
        if (supportsVision) {
          IconButton(
            onClick = onAttach,
            enabled = !isGenerating && attachments.size < MAX_PICK,
            modifier = Modifier.size(52.dp),
          ) {
            Icon(Icons.Outlined.AddPhotoAlternate, "Attach image")
          }
          Spacer(Modifier.size(4.dp))
        }
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
            enabled = canSend && (text.isNotBlank() || attachments.isNotEmpty()),
            modifier = Modifier.size(52.dp),
          ) {
            Icon(Icons.AutoMirrored.Filled.Send, "Send")
          }
        }
      }
    }
  }
}

/**
 * Generation settings for this chat (RESEARCH.md §9.1): each value inherits the model's (or
 * global) setting until changed; "Save as model default" moves the overrides to the model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatGenerationSheet(
  state: ChatUiState,
  onChange: (GenerationSettings) -> Unit,
  onSaveAsModelDefault: () -> Unit,
  onDismiss: () -> Unit,
  sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
  val inherited = if (state.modelName != null) "model" else "global"
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
      Text("Generation settings", style = MaterialTheme.typography.titleMedium)
      Text(
        "For this chat only. Unchanged values follow the ${if (state.modelName != null) "settings of ${state.modelName}" else "global defaults"}.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
      )
      OverridableGenerationControls(state.genBase, state.gen, inheritedFrom = inherited, onChange = onChange)
      val customized = state.gen != GenerationSettings()
      Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TextButton(onClick = { onChange(GenerationSettings()) }, enabled = customized) { Text("Reset to $inherited") }
        if (state.modelName != null) {
          FilledTonalButton(onClick = onSaveAsModelDefault, enabled = customized) { Text("Save as model default") }
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
              listOfNotNull(m.quant, Format.bytes(m.sizeBytes), "Vision".takeIf { m.vision }).joinToString(" · "),
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

/** Most images per message (the Photo Picker enforces it too). */
private const val MAX_PICK = 4

/** Thumbnail of an image waiting to be sent, with a remove button. */
@Composable
private fun AttachmentChip(path: String, number: Int, onRemove: () -> Unit) {
  Box(Modifier.size(64.dp)) {
    AttachmentImage(
      path,
      contentDescription = "Attached image $number",
      modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
      crop = true,
    )
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
      contentColor = MaterialTheme.colorScheme.inverseOnSurface,
      modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(24.dp).clip(CircleShape).clickable(onClick = onRemove),
    ) {
      Icon(Icons.Outlined.Close, "Remove image $number", Modifier.padding(4.dp))
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
