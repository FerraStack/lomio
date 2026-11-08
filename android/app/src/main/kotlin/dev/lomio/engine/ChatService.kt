package dev.lomio.engine

import dev.lomio.data.Attachments
import dev.lomio.data.ChatRepository
import dev.lomio.data.ModelRepository
import dev.lomio.data.db.MessageRole
import dev.lomio.data.db.MessageStatus
import dev.lomio.data.settings.SettingsRepository
import dev.lomio.domain.ChatReducer
import dev.lomio.domain.ContextUsage
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
import dev.lomio.llama.SamplingParams
import java.io.File
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
  private val messages: EngineText = EngineText(),
) {
  constructor(
    engine: InferenceEngine,
    manager: ModelManager,
    chats: ChatRepository,
    models: ModelRepository,
    settings: SettingsRepository,
    scope: CoroutineScope,
    messages: EngineText = EngineText(),
  ) : this(engine, { manager.activeModelId.value }, chats, models, settings, scope, messages)

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

  /** @param attachments image file names from the attachment store, sent before the text. */
  fun send(
    conversationId: String,
    text: String,
    enableThinking: Boolean = true,
    attachments: List<String> = emptyList(),
  ): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty() && attachments.isEmpty()) return false
    return launchTurn {
      val modelId = activeModelId() ?: return@launchTurn dispatch(GenEvent.Failed(null, messages.loadModelFirst))
      val conversation = chats.conversation(conversationId) ?: return@launchTurn
      val isFirst = chats.messages(conversationId).none { it.role == MessageRole.USER }
      chats.addMessage(conversationId, MessageRole.USER, trimmed, attachments = attachments)
      if (isFirst && conversation.title == "New chat") {
        chats.rename(conversationId, Titles.fromFirstMessage(trimmed.ifEmpty { "Image" }))
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
      val modelId = activeModelId() ?: return@launchTurn dispatch(GenEvent.Failed(null, messages.loadModelFirst))
      val branch = chats.messages(conversationId)
      val target = messageId?.let { id -> branch.firstOrNull { it.id == id } } ?: branch.lastOrNull() ?: return@launchTurn
      // Reply to the question above the target (or to the target itself if it is a question).
      val anchor = if (target.role == MessageRole.ASSISTANT) target.parentId else target.id
      generate(conversationId, modelId, enableThinking, anchor ?: return@launchTurn)
    }

  /** Edit: adds the new text (keeping the question's images) as another version and answers it. */
  fun edit(conversationId: String, messageId: String, newText: String, enableThinking: Boolean = true): Boolean {
    return launchTurn {
      // Check the model before touching history: without one, the edit would be left unanswered.
      val modelId = activeModelId() ?: return@launchTurn dispatch(GenEvent.Failed(null, messages.loadModelFirst))
      val original = chats.message(messageId) ?: return@launchTurn
      val images = Attachments.decode(original.attachments)
      if (newText.isBlank() && images.isEmpty()) return@launchTurn
      val edited =
        chats.addMessage(
          conversationId,
          MessageRole.USER,
          newText.trim(),
          parent = ChatRepository.Parent.Of(original.parentId),
          attachments = images,
        )
      generate(conversationId, modelId, enableThinking, edited.id)
    }
  }

  fun stop() {
    engine.stop()
  }

  /**
   * "Summarize & continue": asks the model to summarize the visible branch so far and stores
   * the result as a system note (a SYSTEM message at the end of the branch). From then on the
   * model sees the note (with the system prompt) instead of the messages before it; those stay
   * visible in the chat. The request is the chat plus one instruction, so it reuses the chat's
   * KV cache.
   */
  fun summarize(conversationId: String): Boolean =
    launchTurn {
      val modelId = activeModelId() ?: return@launchTurn dispatch(GenEvent.Failed(null, "Load a model first"))
      val ctx = context(conversationId, modelId, parentId = null) ?: return@launchTurn
      if (ctx.history.none { it.role == "user" }) return@launchTurn
      val request =
        ContextWindow.trim(ctx.history + ChatMessage("user", ContextUsage.SUMMARY_INSTRUCTION), ctx.nCtx, SUMMARY_MAX_TOKENS)
      val sampling = ctx.sampling.copy(temperature = 0.3f, maxTokens = SUMMARY_MAX_TOKENS, stop = emptyList())
      val note = chats.addMessage(conversationId, MessageRole.SYSTEM, "", MessageStatus.STREAMING, modelId)
      dispatch(GenEvent.Started(conversationId, note.id, note.createdAt, MessageRole.SYSTEM))
      stream(conversationId, note.id, request, sampling, enableThinking = false, summary = true)
    }

  /** What a turn needs: sampling settings and the history as the model should see it. */
  private class TurnContext(val history: List<ChatMessage>, val sampling: SamplingParams, val nCtx: Int)

  /** History up to [parentId] (default: the end of the visible branch) as the model sees it. */
  private suspend fun context(conversationId: String, modelId: String, parentId: String?): TurnContext? {
    val conversation = chats.conversation(conversationId) ?: return null
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

    val info = (engine.state.value as? EngineState.Loaded)?.info
    // Images only reach a model loaded with its vision projector; otherwise the text goes alone.
    val vision = info?.supportsVision == true
    val branch = if (parentId != null) chats.pathTo(conversationId, parentId) else chats.messages(conversationId)
    // The latest finished summary on this branch replaces everything before it (summarize()).
    val summaryAt = branch.indexOfLast { ContextUsage.isSummary(it.role, it.status, it.content) }
    val summary = branch.getOrNull(summaryAt)?.content
    val system =
      listOfNotNull(systemPrompt.takeIf { it.isNotBlank() }, summary?.let { ContextUsage.SUMMARY_PREFIX + it })
        .joinToString("\n\n")
    val history = buildList {
      if (system.isNotBlank()) add(ChatMessage("system", system))
      branch
        .drop(summaryAt + 1)
        .filter { it.role != MessageRole.SYSTEM && it.status != MessageStatus.ERROR }
        .forEach { m ->
          val images =
            if (vision) Attachments.decode(m.attachments).map(chats.attachmentPath).filter { File(it).exists() }
            else emptyList()
          if (m.content.isNotEmpty() || images.isNotEmpty()) add(ChatMessage(m.role.wire, m.content, m.reasoning, images))
        }
    }
    return TurnContext(history, gen.toSamplingParams(), info?.nCtx ?: 4096)
  }

  /** Generates a reply under [parentId] (default: the end of the visible branch). */
  private suspend fun generate(
    conversationId: String,
    modelId: String,
    enableThinking: Boolean,
    parentId: String? = null,
  ) {
    val ctx = context(conversationId, modelId, parentId) ?: return
    val history = ContextWindow.trim(ctx.history, ctx.nCtx, ctx.sampling.maxTokens)
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
    stream(conversationId, assistant.id, history, ctx.sampling, enableThinking, summary = false)
  }

  /** Runs one generation into message [messageId] and settles it, whatever happens. */
  private suspend fun stream(
    conversationId: String,
    messageId: String,
    history: List<ChatMessage>,
    sampling: SamplingParams,
    enableThinking: Boolean,
    summary: Boolean,
  ) {
    var content = ""
    var reasoning = ""
    var settled = false
    try {
      engine.chat(history, sampling, enableThinking, cacheKey = conversationId).collect { ev ->
        when (ev) {
          is GenerationEvent.PromptProgress ->
            dispatch(GenEvent.PromptProgress(messageId, ev.done, ev.total))
          is GenerationEvent.Update -> {
            content = ev.content
            reasoning = ev.reasoning
            dispatch(GenEvent.Update(messageId, ev.content, ev.reasoning))
          }
          is GenerationEvent.Done -> {
            content = if (summary) ev.content.trim() else ev.content
            reasoning = ev.reasoning
            // An empty summary must not count as one (the model would lose the history).
            val status = if (summary && content.isEmpty()) MessageStatus.ERROR else statusFor(ev.stats.stopReason)
            chats.finishMessage(messageId, content, ev.reasoning, status, ev.stats)
            dispatch(GenEvent.Finished(messageId, content, ev.reasoning, ev.stats))
            settled = true
          }
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      withContext(NonCancellable) {
        chats.finishMessage(messageId, content, reasoning, MessageStatus.ERROR, null)
      }
      dispatch(GenEvent.Failed(messageId, e.message ?: messages.generationFailed))
      settled = true
    } finally {
      // Whatever happened (scope cancelled, engine closed the flow without Done), never leave
      // the message or the UI stuck in "generating".
      if (!settled) {
        withContext(NonCancellable) {
          chats.finishMessage(messageId, content, reasoning, MessageStatus.STOPPED, null)
        }
        dispatch(GenEvent.Failed(messageId, messages.generationStopped))
      }
    }
  }

  private companion object {
    const val SUMMARY_MAX_TOKENS = 400
  }
}
