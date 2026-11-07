package dev.lomio.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lomio.AppContainer
import dev.lomio.data.db.ModelState
import dev.lomio.domain.ChatReducer
import dev.lomio.engine.LoadOutcome
import dev.lomio.llama.EngineState
import dev.lomio.ui.components.Format
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

  private val conversation =
    conversationId.flatMapLatest { id -> if (id == null) flowOf(null) else c.chats.observeConversation(id) }
  private val dbMessages =
    conversationId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.chats.observeMessages(id) }

  private val modelBits =
    combine(c.models.observeModels(), c.modelManager.activeModelId, c.modelManager.engineState, c.modelManager.crashedModelId) {
      models, activeId, engine, crashedId ->
      ModelBits(models, activeId, engine, crashedId)
    }

  val state: StateFlow<ChatUiState> =
    combine(
        combine(conversationId, conversation, dbMessages, ::Triple),
        c.chatService.state,
        modelBits,
        c.settings.settings,
        combine(thinking, localError, ::Pair),
      ) { (id, conv, msgs), gen, bits, settings, (think, err) ->
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
          ready.map { PickerModel(it.id, it.displayName, it.quant, it.sizeBytes, it.id == bits.activeId) }
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
          thinkingEnabled = think,
          systemPrompt = conv?.systemPrompt,
          crashedModelName = bits.crashedId?.let { cid -> bits.models.firstOrNull { it.id == cid }?.displayName ?: "the last model" },
          error = err ?: gen.error,
        )
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

  fun open(id: String?) {
    conversationId.value = id
  }

  /** Returns the conversation to send into, creating one on first message. */
  private suspend fun ensureConversation(): String =
    conversationId.value
      ?: c.chats.create(c.modelManager.activeModelId.value).id.also { conversationId.value = it }

  fun send(text: String) =
    viewModelScope.launch { c.chatService.send(ensureConversation(), text, thinking.value) }

  fun edit(messageId: String, text: String) {
    val id = conversationId.value ?: return
    c.chatService.edit(id, messageId, text, thinking.value)
  }

  fun regenerate() {
    val id = conversationId.value ?: return
    c.chatService.regenerate(id, thinking.value)
  }

  fun stop() = c.chatService.stop()

  fun toggleThinking() {
    thinking.value = !thinking.value
  }

  fun loadModel(id: String) =
    viewModelScope.launch {
      if (c.chatService.state.value.isGenerating) c.chatService.stop()
      when (val r = c.modelManager.load(id)) {
        is LoadOutcome.Loaded -> Unit
        is LoadOutcome.Failed -> localError.value = r.message
        is LoadOutcome.WontFit ->
          localError.value =
            "Not enough memory: needs ~${Format.bytes(r.estimate.totalBytes)}, " +
              "${Format.bytes(r.memory.availableBytes)} available. Try a smaller context or model."
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

  fun dismissCrash() = c.modelManager.dismissCrashNotice()

  fun errorShown() {
    localError.value = null
    c.chatService.errorShown()
  }

  private data class ModelBits(
    val models: List<dev.lomio.data.db.ModelEntity>,
    val activeId: String?,
    val engine: EngineState,
    val crashedId: String?,
  )
}
