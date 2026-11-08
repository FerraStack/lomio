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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lomio.data.db.MessageRole
import dev.lomio.domain.ContextUsage
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
import androidx.compose.ui.res.stringResource
import dev.lomio.R
import dev.lomio.ui.components.chatTitle
import dev.lomio.ui.settings.InheritedFrom

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
  /** Context fill after the latest reply; the top bar shows it once the chat nears the limit. */
  val contextUsage: ContextUsage? = null,
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
  val onSummarize: () -> Unit = {},
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
fun ChatScreen(
  state: ChatUiState,
  actions: ChatActions,
  modifier: Modifier = Modifier,
  /** Bump to focus the message field and show the keyboard (the "Ask Lomio" tile). */
  focusComposer: Int = 0,
) {
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
          IconButton(onClick = actions.onOpenDrawer) { Icon(Icons.Outlined.Menu, stringResource(R.string.action_open_menu)) }
        },
        title = {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { showPicker = true },
          ) {
            Text(chatTitle(state.title), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
              StatusDot(state.modelStatus)
              Spacer(Modifier.size(6.dp))
              Text(
                state.modelName ?: stringResource(R.string.chat_no_model),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Icon(Icons.Outlined.ExpandMore, stringResource(R.string.chat_choose_model), Modifier.size(16.dp))
            }
          }
        },
        actions = {
          state.contextUsage?.takeIf { it.nearlyFull }?.let { usage -> ContextMeter(usage) { dialog = "context" } }
          Box {
            IconButton(onClick = { showMenu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
              DropdownMenuItem(text = { Text(stringResource(R.string.chat_menu_gen)) }, onClick = { showMenu = false; dialog = "gen" })
              val canSummarize =
                !state.isGenerating && state.modelStatus == ModelStatus.LOADED && state.messages.any { it.role == MessageRole.ASSISTANT }
              DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_menu_summarize)) },
                enabled = canSummarize,
                onClick = { showMenu = false; dialog = "context" },
              )
              DropdownMenuItem(text = { Text(stringResource(R.string.chat_menu_system_prompt)) }, onClick = { showMenu = false; dialog = "system" })
              if (state.modelStatus == ModelStatus.LOADED) {
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_menu_model_settings)) }, onClick = { showMenu = false; actions.onOpenModelSettings() })
              }
              DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { showMenu = false; dialog = "rename" })
              if (state.conversationId != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_menu_export)) }, onClick = { showMenu = false; dialog = "export" })
              }
              DropdownMenuItem(text = { Text(stringResource(R.string.chat_new)) }, onClick = { showMenu = false; actions.onNewChat() })
              DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { showMenu = false; actions.onDelete() })
            }
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
      state.crashedModelName?.let {
        WarningBanner(
          stringResource(R.string.chat_crashed, it),
          actions.onDismissCrash,
        )
      }
      state.loadError?.let {
        WarningBanner(it, actions.onDismissLoadError, actionLabel = stringResource(R.string.chat_choose_model), onAction = { showPicker = true })
      }
      state.gpuNotice?.let { WarningBanner(it, actions.onDismissGpuNotice) }
      Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
          state.messages.isEmpty() && state.modelStatus == ModelStatus.LOADING ->
            EmptyState(title = state.modelName?.let { stringResource(R.string.chat_loading_model, it) } ?: stringResource(R.string.chat_loading_model_generic)) { CircularProgressIndicator() }
          state.messages.isEmpty() && state.downloadedModels.isEmpty() ->
            EmptyState(title = stringResource(R.string.chat_empty_no_models)) {
              Button(onClick = actions.onManageModels) {
                Icon(Icons.Outlined.Download, null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.chat_browse_models))
              }
            }
          state.messages.isEmpty() && state.modelStatus != ModelStatus.LOADED ->
            EmptyState(title = stringResource(if (state.modelStatus == ModelStatus.FAILED) R.string.chat_model_failed else R.string.chat_load_to_chat)) {
              state.lastModel?.let { m ->
                Button(onClick = { actions.onLoadModel(m.id) }) { Text(stringResource(R.string.chat_load_named, m.name)) }
              }
              OutlinedButton(onClick = { showPicker = true }) { Text(stringResource(R.string.chat_choose_another)) }
            }
          state.messages.isEmpty() -> EmptyState(title = stringResource(R.string.chat_empty_prompt)) {}
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
        focusRequest = focusComposer,
      )
    }
  }

  deleting?.let { m ->
    val user = m.role == MessageRole.USER
    AlertDialog(
      onDismissRequest = { deleting = null },
      title = { Text(stringResource(if (user) R.string.chat_delete_message_title else R.string.chat_delete_reply_title)) },
      text = {
        Text(
          when {
            user -> stringResource(R.string.chat_delete_message_body)
            m.versionCount > 1 -> stringResource(R.string.chat_delete_version_body)
            else -> stringResource(R.string.chat_delete_reply_body)
          }
        )
      },
      confirmButton = {
        TextButton(onClick = { deleting = null; actions.onDeleteMessage(m.id) }) {
          Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
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
        stringResource(R.string.chat_menu_export),
        onPick = {
          dialog = null
          actions.onExport(it)
        },
        onDismiss = { dialog = null },
      )
    "context" ->
      SummarizeDialog(
        usage = state.contextUsage,
        onConfirm = {
          dialog = null
          actions.onSummarize()
        },
        onDismiss = { dialog = null },
      )
    "rename" ->
      TextInputDialog(stringResource(R.string.chat_rename_title), chatTitle(state.title), singleLine = true, onDismiss = { dialog = null }) {
        actions.onRename(it)
        dialog = null
      }
    "system" ->
      TextInputDialog(
        stringResource(R.string.chat_menu_system_prompt),
        state.systemPrompt.orEmpty(),
        singleLine = false,
        hint = stringResource(R.string.chat_system_prompt_hint),
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
          MessageRole.SYSTEM -> SummaryNote(m, itemModifier)
          else -> AssistantMessage(m, showStats = showStats, actions = messageActions, modifier = itemModifier)
        }
      }
    }
    if (!atBottom) {
      SmallFloatingActionButton(
        onClick = { scope.launch { listState.animateScrollToItem(0) } },
        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
      ) {
        Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.chat_jump_latest))
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
  focusRequest: Int = 0,
) {
  val focus = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current
  LaunchedEffect(focusRequest) {
    if (focusRequest > 0) {
      focus.requestFocus()
      keyboard?.show()
    }
  }
  Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
      if (editing) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 4.dp),
        ) {
          Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
          Text(
            stringResource(R.string.chat_editing),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
          )
          TextButton(onClick = onCancelEdit) { Text(stringResource(R.string.action_cancel)) }
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
          label = { Text(stringResource(R.string.chat_think)) },
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
            Icon(Icons.Outlined.AddPhotoAlternate, stringResource(R.string.chat_attach_image))
          }
          Spacer(Modifier.size(4.dp))
        }
        TextField(
          value = text,
          onValueChange = onTextChange,
          placeholder = { Text(stringResource(R.string.chat_message_hint)) },
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
          modifier = Modifier.weight(1f).heightIn(min = 52.dp).focusRequester(focus),
        )
        Spacer(Modifier.size(8.dp))
        if (isGenerating) {
          FilledIconButton(onClick = onStop, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Filled.Stop, stringResource(R.string.chat_stop))
          }
        } else {
          FilledIconButton(
            onClick = onSend,
            enabled = canSend && (text.isNotBlank() || attachments.isNotEmpty()),
            modifier = Modifier.size(52.dp),
          ) {
            Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.chat_send))
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
  val inherited = if (state.modelName != null) InheritedFrom.MODEL else InheritedFrom.GLOBAL
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
      Text(stringResource(R.string.chat_gen_title), style = MaterialTheme.typography.titleMedium)
      Text(
        state.modelName?.let { stringResource(R.string.chat_gen_body_model, it) } ?: stringResource(R.string.chat_gen_body_global),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
      )
      OverridableGenerationControls(state.genBase, state.gen, inheritedFrom = inherited, onChange = onChange)
      val customized = state.gen != GenerationSettings()
      Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TextButton(onClick = { onChange(GenerationSettings()) }, enabled = customized) { Text(stringResource(if (state.modelName != null) R.string.chat_gen_reset_model else R.string.chat_gen_reset_global)) }
        if (state.modelName != null) {
          FilledTonalButton(onClick = onSaveAsModelDefault, enabled = customized) { Text(stringResource(R.string.chat_gen_save_default)) }
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
        stringResource(R.string.chat_picker_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
      )
      if (models.isEmpty()) {
        Text(
          stringResource(R.string.chat_picker_empty),
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
            Text(stringResource(R.string.chat_picker_loaded), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
          }
        }
      }
      TextButton(onClick = onManage, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.chat_manage_models)) }
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
      contentDescription = stringResource(R.string.message_attached_image, number),
      modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
      crop = true,
    )
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
      contentColor = MaterialTheme.colorScheme.inverseOnSurface,
      modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(24.dp).clip(CircleShape).clickable(onClick = onRemove),
    ) {
      Icon(Icons.Outlined.Close, stringResource(R.string.chat_remove_image, number), Modifier.padding(4.dp))
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
        TextButton(onClick = onDismiss, colors = buttonColors) { Text(stringResource(R.string.action_dismiss)) }
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
    confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(stringResource(R.string.action_save)) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}

/** Small ring + percentage in the top bar once the chat nears the context limit. */
@Composable
private fun ContextMeter(usage: ContextUsage, onClick: () -> Unit) {
  val color =
    when {
      usage.fraction >= 0.9f -> MaterialTheme.colorScheme.error
      usage.fraction >= 0.75f -> MaterialTheme.colorScheme.tertiary
      else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
  val description = stringResource(R.string.context_full, usage.percent)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.clip(RoundedCornerShape(50))
        .clickable(onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 6.dp)
        .semantics(mergeDescendants = true) { contentDescription = description },
  ) {
    CircularProgressIndicator(
      progress = { usage.fraction },
      color = color,
      trackColor = MaterialTheme.colorScheme.surfaceVariant,
      strokeWidth = 2.5.dp,
      modifier = Modifier.size(16.dp),
    )
    Spacer(Modifier.size(6.dp))
    Text("${usage.percent}%", style = MaterialTheme.typography.labelMedium, color = color)
  }
}

@Composable
private fun SummarizeDialog(usage: ContextUsage?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(usage?.let { stringResource(R.string.context_full, it.percent) } ?: stringResource(R.string.chat_menu_summarize)) },
    text = {
      val numbers = java.text.NumberFormat.getIntegerInstance()
      Text(
        listOfNotNull(
            usage?.let { stringResource(R.string.context_usage, numbers.format(it.usedTokens), numbers.format(it.nCtx)) },
            stringResource(R.string.context_summarize_body),
          )
          .joinToString("\n\n")
      )
    },
    confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.chat_menu_summarize)) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_not_now)) } },
  )
}
