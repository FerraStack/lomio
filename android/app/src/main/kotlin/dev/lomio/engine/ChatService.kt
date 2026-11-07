package dev.lomio.engine

import dev.lomio.data.ChatRepository
import dev.lomio.data.ModelRepository
import dev.lomio.data.db.MessageRole
import dev.lomio.data.db.MessageStatus
import dev.lomio.data.settings.SettingsRepository
import dev.lomio.domain.ChatReducer
import dev.lomio.domain.ContextWindow
import dev.lomio.domain.GenEvent
import dev.lomio.domain.GenerationState
import dev.lomio.domain.SettingsJson
import dev.lomio.domain.SettingsResolver
import dev.lomio.domain.Titles
import dev.lomio.domain.statusFor
import dev.lomio.llama.ChatMessage
import dev.lomio.llama.EngineState
import dev.lomio.llama.GenerationEvent
import dev.lomio.llama.InferenceEngine
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs chat turns. Lives in the app container (not a ViewModel) so a generation survives
 * configuration changes and keeps going while the user browses other screens.
 *
 * At most one turn runs at a time: [busy] is claimed synchronously before anything is launched,
 * so a double-tapped Send can't start two generations.
 */
class ChatService(
  private val engine: InferenceEngine,
  private val activeModelId: () -> String?,
  private val chats: ChatRepository,
  private val models: ModelRepository,
  private val settings: SettingsRepository,
  private val scope: CoroutineScope,
) {
  constructor(
    engine: InferenceEngine,
    manager: ModelManager,
    chats: ChatRepository,
    models: ModelRepository,
    settings: SettingsRepository,
    scope: CoroutineScope,
  ) : this(engine, { manager.activeModelId.value }, chats, models, settings, scope)

  private val _state = MutableStateFlow(GenerationState())
  val state: StateFlow<GenerationState> = _state.asStateFlow()
  private val busy = AtomicBoolean(false)

  val isBusy: Boolean
    get() = busy.get()

  private fun dispatch(event: GenEvent) = _state.update { ChatReducer.reduce(it, event) }

  fun errorShown() = dispatch(GenEvent.ErrorShown)

  /** Claims the single generation slot or returns false if a turn is already running. */
  private fun launchTurn(block: suspend () -> Unit): Boolean {
    if (!busy.compareAndSet(false, true)) return false
    scope.launch {
      try {
        block()
      } finally {
        busy.set(false)
      }
    }
    return true
  }

  fun send(conversationId: String, text: String, enableThinking: Boolean = true): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    return launchTurn {
      val modelId = activeModelId() ?: return@launchTurn dispatch(GenEvent.Failed(null, "Load a model first"))
      val conversation = chats.conversation(conversationId) ?: return@launchTurn
      val isFirst = chats.messages(conversationId).none { it.role == MessageRole.USER }
      chats.addMessage(conversationId, MessageRole.USER, trimmed)
      if (isFirst && conversation.title == "New chat") {
        chats.rename(conversationId, Titles.fromFirstMessage(trimmed))
      }
      generate(conversationId, modelId, enableThinking)
    }
  }

  /**
   * A new version of an assistant reply: generates again from the same question and keeps the
   * old reply as another version. With no [messageId], the last reply of the visible branch.
   */
  fun regenerate(conversationId: String, enableThinking: Boolean = true, messageId: String? = null): Boolean =
    launchTurn {
      val modelId = activeModelId() ?: return@launchTurn dispatch(GenEvent.Failed(null, "Load a model first"))
      val branch = chats.messages(conversationId)
      val target = messageId?.let { id -> branch.firstOrNull { it.id == id } } ?: branch.lastOrNull() ?: return@launchTurn
      // Reply to the question above the target (or to the target itself if it is a question).
      val anchor = if (target.role == MessageRole.ASSISTANT) target.parentId else target.id
      generate(conversationId, modelId, enableThinking, anchor ?: return@launchTurn)
    }

  /** Edit: adds the new text as another version of the question and answers it. */
  fun edit(conversationId: String, messageId: String, newText: String, enableThinking: Boolean = true): Boolean {
    if (newText.isBlank()) return false
    return launchTurn {
      // Check the model before touching history: without one, the edit would be left unanswered.
      val modelId = activeModelId() ?: return@launchTurn dispatch(GenEvent.Failed(null, "Load a model first"))
      val original = chats.message(messageId) ?: return@launchTurn
      val edited =
        chats.addMessage(conversationId, MessageRole.USER, newText.trim(), parent = ChatRepository.Parent.Of(original.parentId))
      generate(conversationId, modelId, enableThinking, edited.id)
    }
  }

  fun stop() {
    engine.stop()
  }

  /** Generates a reply under [parentId] (default: the end of the visible branch). */
  private suspend fun generate(
    conversationId: String,
    modelId: String,
    enableThinking: Boolean,
    parentId: String? = null,
  ) {
    val conversation = chats.conversation(conversationId) ?: return
    val app = settings.current()
    val modelSettings = models.settings(modelId)
    val gen =
      SettingsResolver.generation(
        global = app.generation,
        model = SettingsJson.decodeGen(modelSettings?.genJson),
        chat = SettingsJson.decodeGen(conversation.genOverrideJson),
      )
    val systemPrompt =
      SettingsResolver.systemPrompt(app.systemPrompt, modelSettings?.systemPrompt, conversation.systemPrompt)

    val fullHistory = buildList {
      if (systemPrompt.isNotBlank()) add(ChatMessage("system", systemPrompt))
      (if (parentId != null) chats.pathTo(conversationId, parentId) else chats.messages(conversationId))
        .filter { it.role != MessageRole.SYSTEM && it.content.isNotEmpty() && it.status != MessageStatus.ERROR }
        .forEach { add(ChatMessage(it.role.wire, it.content, it.reasoning)) }
    }
    val sampling = gen.toSamplingParams()
    val nCtx = (engine.state.value as? EngineState.Loaded)?.info?.nCtx ?: 4096
    val history = ContextWindow.trim(fullHistory, nCtx, sampling.maxTokens)

    val assistant =
      chats.addMessage(
        conversationId,
        MessageRole.ASSISTANT,
        "",
        MessageStatus.STREAMING,
        modelId,
        parent = if (parentId != null) ChatRepository.Parent.Of(parentId) else ChatRepository.Parent.Leaf,
      )
    dispatch(GenEvent.Started(conversationId, assistant.id, assistant.createdAt))

    var content = ""
    var reasoning = ""
    var settled = false
    try {
      engine.chat(history, sampling, enableThinking).collect { ev ->
        when (ev) {
          is GenerationEvent.PromptProgress ->
            dispatch(GenEvent.PromptProgress(assistant.id, ev.done, ev.total))
          is GenerationEvent.Update -> {
            content = ev.content
            reasoning = ev.reasoning
            dispatch(GenEvent.Update(assistant.id, ev.content, ev.reasoning))
          }
          is GenerationEvent.Done -> {
            content = ev.content
            reasoning = ev.reasoning
            chats.finishMessage(assistant.id, ev.content, ev.reasoning, statusFor(ev.stats.stopReason), ev.stats)
            dispatch(GenEvent.Finished(assistant.id, ev.content, ev.reasoning, ev.stats))
            settled = true
          }
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      withContext(NonCancellable) {
        chats.finishMessage(assistant.id, content, reasoning, MessageStatus.ERROR, null)
      }
      dispatch(GenEvent.Failed(assistant.id, e.message ?: "Generation failed"))
      settled = true
    } finally {
      // Whatever happened (scope cancelled, engine closed the flow without Done), never leave
      // the message or the UI stuck in "generating".
      if (!settled) {
        withContext(NonCancellable) {
          chats.finishMessage(assistant.id, content, reasoning, MessageStatus.STOPPED, null)
        }
        dispatch(GenEvent.Failed(assistant.id, "Generation stopped"))
      }
    }
  }
}
