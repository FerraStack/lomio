package dev.lomio.engine

import dev.lomio.data.ChatRepository
import dev.lomio.data.ModelRepository
import dev.lomio.data.db.MessageRole
import dev.lomio.data.db.MessageStatus
import dev.lomio.data.settings.SettingsRepository
import dev.lomio.domain.ChatReducer
import dev.lomio.domain.GenEvent
import dev.lomio.domain.GenerationState
import dev.lomio.domain.SettingsJson
import dev.lomio.domain.SettingsResolver
import dev.lomio.domain.Titles
import dev.lomio.llama.ChatMessage
import dev.lomio.llama.GenerationEvent
import dev.lomio.llama.InferenceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 */
class ChatService(
  private val engine: InferenceEngine,
  private val manager: ModelManager,
  private val chats: ChatRepository,
  private val models: ModelRepository,
  private val settings: SettingsRepository,
  private val scope: CoroutineScope,
) {
  private val _state = MutableStateFlow(GenerationState())
  val state: StateFlow<GenerationState> = _state.asStateFlow()
  private var job: Job? = null

  private fun dispatch(event: GenEvent) = _state.update { ChatReducer.reduce(it, event) }

  fun errorShown() = dispatch(GenEvent.ErrorShown)

  fun send(conversationId: String, text: String, enableThinking: Boolean = true) {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || _state.value.isGenerating) return
    job =
      scope.launch {
        val modelId = manager.activeModelId.value
        if (modelId == null) {
          dispatch(GenEvent.Failed("Load a model first"))
          return@launch
        }
        val conversation = chats.conversation(conversationId) ?: return@launch
        val isFirst = chats.messages(conversationId).none { it.role == MessageRole.USER }
        chats.addMessage(conversationId, MessageRole.USER, trimmed, modelId = null)
        if (isFirst && conversation.title == "New chat") {
          chats.rename(conversationId, Titles.fromFirstMessage(trimmed))
        }
        generate(conversationId, modelId, enableThinking)
      }
  }

  /** Re-runs the reply to the last user message (removes the last assistant reply). */
  fun regenerate(conversationId: String, enableThinking: Boolean = true) {
    if (_state.value.isGenerating) return
    job =
      scope.launch {
        val modelId = manager.activeModelId.value ?: return@launch dispatch(GenEvent.Failed("Load a model first"))
        val last = chats.messages(conversationId).lastOrNull() ?: return@launch
        if (last.role == MessageRole.ASSISTANT) chats.truncateFrom(conversationId, last.id)
        generate(conversationId, modelId, enableThinking)
      }
  }

  /** Edit-and-resend: replaces a user message and drops everything after it. */
  fun edit(conversationId: String, messageId: String, newText: String, enableThinking: Boolean = true) {
    if (_state.value.isGenerating || newText.isBlank()) return
    job =
      scope.launch {
        chats.truncateFrom(conversationId, messageId)
        val modelId = manager.activeModelId.value ?: return@launch dispatch(GenEvent.Failed("Load a model first"))
        chats.addMessage(conversationId, MessageRole.USER, newText.trim())
        generate(conversationId, modelId, enableThinking)
      }
  }

  fun stop() {
    engine.stop()
  }

  private suspend fun generate(conversationId: String, modelId: String, enableThinking: Boolean) {
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

    val history = buildList {
      if (systemPrompt.isNotBlank()) add(ChatMessage("system", systemPrompt))
      chats
        .messages(conversationId)
        .filter { it.role != MessageRole.SYSTEM && it.content.isNotEmpty() }
        .forEach { add(ChatMessage(it.role.wire, it.content, it.reasoning)) }
    }
    val assistant =
      chats.addMessage(conversationId, MessageRole.ASSISTANT, "", MessageStatus.STREAMING, modelId)
    dispatch(GenEvent.Started(conversationId, assistant.id, assistant.createdAt))

    var content = ""
    var reasoning = ""
    try {
      engine.chat(history, gen.toSamplingParams(), enableThinking).collect { ev ->
        when (ev) {
          is GenerationEvent.Update -> {
            content = ev.content
            reasoning = ev.reasoning
            dispatch(GenEvent.Update(ev.content, ev.reasoning))
          }
          is GenerationEvent.Done -> {
            content = ev.content
            reasoning = ev.reasoning
            val status =
              if (ev.stats.stopReason == dev.lomio.llama.StopReason.CANCELLED) MessageStatus.STOPPED
              else MessageStatus.COMPLETE
            chats.finishMessage(assistant.id, ev.content, ev.reasoning, status, ev.stats)
            dispatch(GenEvent.Finished(ev.content, ev.reasoning, ev.stats))
          }
        }
      }
    } catch (e: CancellationException) {
      withContext(NonCancellable) {
        chats.finishMessage(assistant.id, content, reasoning, MessageStatus.STOPPED, null)
      }
      throw e
    } catch (e: Exception) {
      chats.finishMessage(assistant.id, content, reasoning, MessageStatus.ERROR, null)
      dispatch(GenEvent.Failed(e.message ?: "Generation failed"))
    }
  }
}
