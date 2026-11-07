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
  data class Started(val conversationId: String, val assistantId: String, val createdAt: Long) :
    GenEvent

  data class Update(val content: String, val reasoning: String) : GenEvent

  data class Finished(val content: String, val reasoning: String, val stats: GenerationStats) :
    GenEvent

  data class Failed(val message: String) : GenEvent

  data object ErrorShown : GenEvent
}

/** Pure state machine for streaming generation; ChatService only feeds it events. */
object ChatReducer {
  fun reduce(state: GenerationState, event: GenEvent): GenerationState =
    when (event) {
      is GenEvent.Started ->
        GenerationState(
          conversationId = event.conversationId,
          streaming =
            UiMessage(
              id = event.assistantId,
              role = MessageRole.ASSISTANT,
              content = "",
              status = MessageStatus.STREAMING,
              createdAt = event.createdAt,
            ),
        )
      is GenEvent.Update ->
        state.streaming?.let {
          state.copy(
            streaming = it.copy(content = event.content, reasoning = event.reasoning.ifEmpty { null })
          )
        } ?: state
      is GenEvent.Finished -> {
        val streaming = state.streaming ?: return state
        val status =
          if (event.stats.stopReason == StopReason.CANCELLED) MessageStatus.STOPPED
          else MessageStatus.COMPLETE
        state.copy(
          streaming = null,
          lastFinished =
            streaming.copy(
              content = event.content,
              reasoning = event.reasoning.ifEmpty { null },
              status = status,
              stats = event.stats,
            ),
        )
      }
      is GenEvent.Failed ->
        state.copy(
          streaming = null,
          lastFinished = state.streaming?.copy(status = MessageStatus.ERROR),
          error = event.message,
        )
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
