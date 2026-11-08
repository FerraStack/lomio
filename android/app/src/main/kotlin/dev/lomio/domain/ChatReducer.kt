package dev.lomio.domain

import dev.lomio.data.db.MessageRole
import dev.lomio.data.db.MessageStatus
import dev.lomio.llama.GenerationStats
import dev.lomio.llama.StopReason

data class UiMessage(
  val id: String,
  val role: MessageRole,
  val content: String,
  val reasoning: String? = null,
  val status: MessageStatus = MessageStatus.COMPLETE,
  val stats: GenerationStats? = null,
  val createdAt: Long = 0,
  /** While the prompt is being processed: 0..1. Null otherwise. */
  val promptProgress: Float? = null,
  /** Position among this message's versions (regenerated replies, edited questions). */
  val versionIndex: Int = 0,
  val versionCount: Int = 1,
  /** Absolute paths of attached images (user messages). */
  val images: List<String> = emptyList(),
)

/** State of the (at most one) running generation. */
data class GenerationState(
  val conversationId: String? = null,
  /** The assistant message being generated; null when idle. */
  val streaming: UiMessage? = null,
  /** Last finished message, kept until the DB copy arrives so the UI doesn't flicker. */
  val lastFinished: UiMessage? = null,
  val error: String? = null,
) {
  val isGenerating: Boolean
    get() = streaming != null
}

sealed interface GenEvent {
  /** [role]: ASSISTANT for a reply, SYSTEM for a summary note being written. */
  data class Started(
    val conversationId: String,
    val assistantId: String,
    val createdAt: Long,
    val role: MessageRole = MessageRole.ASSISTANT,
  ) : GenEvent

  data class Update(val assistantId: String, val content: String, val reasoning: String) : GenEvent

  data class PromptProgress(val assistantId: String, val done: Int, val total: Int) : GenEvent

  data class Finished(
    val assistantId: String,
    val content: String,
    val reasoning: String,
    val stats: GenerationStats,
  ) : GenEvent

  /** [assistantId] is null when generation failed before an assistant message existed. */
  data class Failed(val assistantId: String?, val message: String) : GenEvent

  data object ErrorShown : GenEvent
}

/** How a finished generation is stored. */
fun statusFor(reason: StopReason): MessageStatus =
  when (reason) {
    StopReason.CANCELLED -> MessageStatus.STOPPED
    StopReason.ERROR -> MessageStatus.ERROR
    else -> MessageStatus.COMPLETE
  }

/**
 * Pure state machine for streaming generation; ChatService only feeds it events. Events for an
 * assistant message other than the one currently streaming are ignored, so a stray second
 * generation can never mix its tokens into the current reply.
 */
object ChatReducer {
  fun reduce(state: GenerationState, event: GenEvent): GenerationState =
    when (event) {
      is GenEvent.Started ->
        GenerationState(
          conversationId = event.conversationId,
          streaming =
            UiMessage(
              id = event.assistantId,
              role = event.role,
              content = "",
              status = MessageStatus.STREAMING,
              createdAt = event.createdAt,
            ),
        )
      is GenEvent.Update ->
        state.streaming?.takeIf { it.id == event.assistantId }?.let {
          state.copy(
            streaming =
              it.copy(content = event.content, reasoning = event.reasoning.ifEmpty { null }, promptProgress = null)
          )
        } ?: state
      is GenEvent.PromptProgress ->
        state.streaming
          ?.takeIf { it.id == event.assistantId && it.content.isEmpty() && it.reasoning == null }
          ?.let {
            val p = if (event.total <= 0) 1f else (event.done.toFloat() / event.total).coerceIn(0f, 1f)
            state.copy(streaming = it.copy(promptProgress = p))
          } ?: state
      is GenEvent.Finished -> {
        val streaming = state.streaming?.takeIf { it.id == event.assistantId } ?: return state
        val status = statusFor(event.stats.stopReason)
        state.copy(
          streaming = null,
          lastFinished =
            streaming.copy(
              content = event.content,
              reasoning = event.reasoning.ifEmpty { null },
              status = status,
              stats = event.stats,
              promptProgress = null,
            ),
        )
      }
      is GenEvent.Failed ->
        if (event.assistantId != null && state.streaming != null && state.streaming.id != event.assistantId) {
          state
        } else {
          state.copy(
            streaming = null,
            lastFinished = state.streaming?.copy(status = MessageStatus.ERROR, promptProgress = null) ?: state.lastFinished,
            error = event.message,
          )
        }
      GenEvent.ErrorShown -> state.copy(error = null)
    }

  /**
   * Messages to render for [conversationId]: the DB list with the in-flight (or just finished)
   * assistant message overlaid, because the DB copy lags behind the stream.
   */
  fun visibleMessages(
    conversationId: String?,
    db: List<UiMessage>,
    state: GenerationState,
  ): List<UiMessage> {
    if (conversationId == null || state.conversationId != conversationId) return db
    val overlay = state.streaming ?: state.lastFinished ?: return db
    val index = db.indexOfFirst { it.id == overlay.id }
    return when {
      index < 0 -> db + overlay
      state.streaming != null -> db.toMutableList().also { it[index] = overlay }
      // Finished: trust the DB once it has the final status.
      db[index].status == MessageStatus.STREAMING -> db.toMutableList().also { it[index] = overlay }
      else -> db
    }
  }
}
