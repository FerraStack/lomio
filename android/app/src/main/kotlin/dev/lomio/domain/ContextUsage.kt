package dev.lomio.domain

import dev.lomio.data.db.MessageRole
import dev.lomio.data.db.MessageStatus

/** How full the context window was after the chat's latest reply. */
data class ContextUsage(val usedTokens: Int, val nCtx: Int) {
  val fraction: Float
    get() = if (nCtx <= 0) 0f else (usedTokens.toFloat() / nCtx).coerceIn(0f, 1f)

  val percent: Int
    get() = (fraction * 100).toInt()

  /** Worth showing: the chat is getting close to the limit. */
  val nearlyFull: Boolean
    get() = fraction >= SHOW_FROM

  companion object {
    /** The indicator appears from this fill level on. */
    const val SHOW_FROM = 0.6f

    /** Prefix of the summary note inside the system message the model sees. */
    const val SUMMARY_PREFIX = "Summary of the earlier conversation:\n"

    const val SUMMARY_INSTRUCTION =
      "Summarize our conversation so far in one short paragraph for your own reference: the user's goal, " +
        "the facts, names, numbers and decisions that matter, and any open questions. Write only the summary."

    fun isSummary(role: MessageRole, status: MessageStatus, content: String) =
      role == MessageRole.SYSTEM && status == MessageStatus.COMPLETE && content.isNotBlank()

    /**
     * From the latest reply's stats (prompt + generated tokens = what was in the KV cache).
     * Unknown (null) if there is no reply yet, or a summary came after it (the next prompt is
     * much shorter then).
     */
    fun of(messages: List<UiMessage>, nCtx: Int?): ContextUsage? {
      if (nCtx == null || nCtx <= 0) return null
      val lastSummary = messages.indexOfLast { it.role == MessageRole.SYSTEM }
      val lastReply = messages.indexOfLast { it.role == MessageRole.ASSISTANT && it.stats != null }
      if (lastReply < 0 || lastReply < lastSummary) return null
      val s = messages[lastReply].stats!!
      return ContextUsage(s.promptTokens + s.generatedTokens, nCtx)
    }
  }
}
