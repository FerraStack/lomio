package dev.lomio.ui.chat

import dev.lomio.ui.assertMainThread
import kotlinx.coroutines.flow.map

import dev.lomio.domain.SettingsResolver

import dev.lomio.domain.SettingsJson

import dev.lomio.domain.GenerationSettings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lomio.AppContainer
import dev.lomio.data.db.ModelState
import dev.lomio.data.db.downloadBytes
import dev.lomio.data.db.hasVision
import dev.lomio.domain.ChatReducer
import dev.lomio.engine.LoadOutcome
import dev.lomio.llama.EngineState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(private val c: AppContainer) : ViewModel() {
  private val conversationId = MutableStateFlow<String?>(null)
  private val thinking = MutableStateFlow(true)
  private val localError = MutableStateFlow<String?>(null)
  private val scrollTarget = MutableStateFlow<String?>(null)
  private val loadError = MutableStateFlow<String?>(null)
  private val dismissedFailure = MutableStateFlow<EngineState.Failed?>(null)
  /** Images picked for the next message (attachment file names). */
  private val pending = MutableStateFlow<List<String>>(emptyList())
  private val local =
    combine(thinking, localError, loadError, dismissedFailure, pending) { think, err, loadErr, dismissed, images ->
      Local(think, err, loadErr, dismissed, images)
    }

  private val conversation =
    conversationId.flatMapLatest { id -> if (id == null) flowOf(null) else c.chats.observeConversation(id) }
  private val dbMessages =
    conversationId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.chats.observeMessages(id) }

  private val activeModelGen =
    c.modelManager.activeModelId.flatMapLatest { id ->
      if (id == null) flowOf(null) else c.models.observeSettings(id).map { SettingsJson.decodeGen(it?.genJson) }
    }

  private val modelBits =
    combine(
      c.models.observeModels(),
      c.modelManager.activeModelId,
      c.modelManager.engineState,
      c.modelManager.crashedModelId,
      activeModelGen,
    ) { models, activeId, engine, crashedId, modelGen ->
      ModelBits(models, activeId, engine, crashedId, modelGen)
    }

  val state: StateFlow<ChatUiState> =
    combine(
        combine(conversationId, conversation, dbMessages, ::Triple),
        c.chatService.state,
        modelBits,
        c.settings.settings,
        local,
      ) { (id, conv, msgs), gen, bits, settings, (think, err, loadErr, dismissed, pendingImages) ->
        assertMainThread("Chat UI state")
        val ready = bits.models.filter { it.state == ModelState.READY }
        val active = ready.firstOrNull { it.id == bits.activeId }
        val status =
          when (bits.engine) {
            is EngineState.Loading -> ModelStatus.LOADING
            is EngineState.Loaded, is EngineState.Generating -> ModelStatus.LOADED
            is EngineState.Failed -> ModelStatus.FAILED
            EngineState.Idle -> ModelStatus.NONE
          }
        val loadingModel =
          (bits.engine as? EngineState.Loading)?.let { l -> ready.firstOrNull { it.filePath == l.path } }
        val info =
          (bits.engine as? EngineState.Loaded)?.info ?: (bits.engine as? EngineState.Generating)?.info
        val pickers =
          ready.map {
            PickerModel(it.id, it.displayName, it.quant, it.downloadBytes, it.id == bits.activeId, vision = it.hasVision)
          }
        ChatUiState(
          conversationId = id,
          title = conv?.title ?: "New chat",
          messages = ChatReducer.visibleMessages(id, msgs, gen),
          isGenerating = gen.isGenerating && gen.conversationId == id,
          modelName = (active ?: loadingModel)?.displayName,
          modelStatus = if (active == null && status == ModelStatus.LOADED) ModelStatus.NONE else status,
          downloadedModels = pickers,
          lastModel = pickers.firstOrNull { it.id == settings.lastModelId } ?: pickers.firstOrNull(),
          showStats = settings.showStats,
          supportsThinking = info?.supportsThinking == true,
          supportsVision = info?.supportsVision == true,
          attachments = pendingImages.map(c.chats.attachmentPath),
          thinkingEnabled = think,
          systemPrompt = conv?.systemPrompt,
          crashedModelName = bits.crashedId?.let { cid -> bits.models.firstOrNull { it.id == cid }?.displayName ?: "the last model" },
          loadError =
            loadErr
              ?: (bits.engine as? EngineState.Failed)?.takeIf { it != dismissed }?.let { f ->
                val name = bits.models.firstOrNull { it.filePath == f.path }?.displayName ?: "the model"
                "Couldn't load $name: ${f.message}"
              },
          error = err ?: gen.error,
          genBase = SettingsResolver.chatBase(settings.generation, if (active != null) bits.modelGen else null),
          gen = SettingsJson.decodeGen(conv?.genOverrideJson) ?: GenerationSettings(),
        )
      }
      .combine(scrollTarget) { s, target -> s.copy(scrollToMessageId = target) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

  fun open(id: String?) {
    conversationId.value = id
    scrollTarget.value = null
  }

  /** Opens a chat on the branch that contains [messageId] and scrolls to it. */
  fun openAt(conversationId: String, messageId: String) =
    viewModelScope.launch {
      c.chats.selectBranch(conversationId, messageId)
      open(conversationId)
      scrollTarget.value = messageId
    }

  fun scrolledToMessage() {
    scrollTarget.value = null
  }

  /** Returns the conversation to send into, creating one on first message. */
  private suspend fun ensureConversation(): String =
    conversationId.value
      ?: c.chats.create(c.modelManager.activeModelId.value).id.also { conversationId.value = it }

  fun send(text: String) {
    // Cheap synchronous checks first so a double tap can't create a second, empty chat.
    val images = pending.value
    if ((text.isBlank() && images.isEmpty()) || c.chatService.isBusy) return
    pending.value = emptyList()
    viewModelScope.launch { c.chatService.send(ensureConversation(), text, thinking.value, images) }
  }

  /** Copies picked images into app storage and adds them to the next message. */
  fun attach(uris: List<Uri>) =
    viewModelScope.launch {
      for (uri in uris.take((MAX_ATTACHMENTS - pending.value.size).coerceAtLeast(0))) {
        runCatching { c.attachments.import(c.appContext.contentResolver, uri) }
          .onSuccess { name -> pending.value = (pending.value + name).take(MAX_ATTACHMENTS) }
          .onFailure { localError.value = it.message ?: "Couldn't attach the image" }
      }
    }

  /** @param path absolute path as shown in [ChatUiState.attachments]. */
  fun removeAttachment(path: String) {
    pending.value = pending.value.filter { c.chats.attachmentPath(it) != path }
  }

  fun edit(messageId: String, text: String) {
    val id = conversationId.value ?: return
    c.chatService.edit(id, messageId, text, thinking.value)
  }

  fun regenerate(messageId: String? = null) {
    val id = conversationId.value ?: return
    c.chatService.regenerate(id, thinking.value, messageId)
  }

  fun stepVersion(messageId: String, step: Int) {
    if (c.chatService.isBusy) return
    val id = conversationId.value ?: return
    viewModelScope.launch { c.chats.stepVersion(id, messageId, step) }
  }

  fun deleteMessage(messageId: String) {
    if (c.chatService.isBusy) return
    val id = conversationId.value ?: return
    viewModelScope.launch { c.chats.deleteMessage(id, messageId) }
  }

  fun stop() = c.chatService.stop()

  fun toggleThinking() {
    thinking.value = !thinking.value
  }

  fun loadModel(id: String) =
    viewModelScope.launch {
      if (c.chatService.state.value.isGenerating) c.chatService.stop()
      loadError.value = null
      when (val r = c.modelManager.load(id)) {
        is LoadOutcome.Loaded -> Unit
        // Memory warning: the app-wide confirmation dialog takes over.
        is LoadOutcome.NeedsConfirmation -> Unit
        is LoadOutcome.Failed -> loadError.value = "Couldn't load the model: ${r.message}"
      }
    }

  fun rename(title: String) = viewModelScope.launch { conversationId.value?.let { c.chats.rename(it, title) } }

  fun setSystemPrompt(prompt: String) =
    viewModelScope.launch { conversationId.value?.let { c.chats.setSystemPrompt(it, prompt) } }

  fun delete(onDeleted: () -> Unit) =
    viewModelScope.launch {
      conversationId.value?.let { c.chats.delete(it) }
      conversationId.value = null
      onDeleted()
    }

  /** Per-chat sampler overrides; an empty set clears them. Creates the chat if needed. */
  fun setGenerationOverride(g: GenerationSettings) =
    viewModelScope.launch {
      val id = ensureConversation()
      c.chats.setGenerationOverride(id, g.takeIf { it != GenerationSettings() })
    }

  /** Moves this chat's overrides into the loaded model's defaults. */
  fun saveGenerationAsModelDefault() =
    viewModelScope.launch {
      val modelId = c.modelManager.activeModelId.value ?: return@launch
      val chatId = conversationId.value ?: return@launch
      val own = SettingsJson.decodeGen(c.chats.conversation(chatId)?.genOverrideJson) ?: return@launch
      val s = c.models.settings(modelId)
      val merged = (SettingsJson.decodeGen(s?.genJson) ?: GenerationSettings()).overlay(own)
      c.models.saveSettings(modelId, merged, SettingsJson.decodeLoad(s?.loadJson), s?.systemPrompt)
      c.chats.setGenerationOverride(chatId, null)
    }

  fun dismissCrash() = c.modelManager.dismissCrashNotice()

  fun dismissLoadError() {
    loadError.value = null
    dismissedFailure.value = c.modelManager.engineState.value as? EngineState.Failed
  }

  fun errorShown() {
    localError.value = null
    c.chatService.errorShown()
  }

  private data class Local(
    val thinking: Boolean,
    val error: String?,
    val loadError: String?,
    val dismissed: EngineState.Failed?,
    val pending: List<String>,
  )

  companion object {
    const val MAX_ATTACHMENTS = 4
  }

  private data class ModelBits(
    val models: List<dev.lomio.data.db.ModelEntity>,
    val activeId: String?,
    val engine: EngineState,
    val crashedId: String?,
    val modelGen: GenerationSettings?,
  )
}
