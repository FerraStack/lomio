package dev.lomio.domain

import dev.lomio.llama.ChatMessage

/**
 * Keeps a chat inside the model's context window: drops the oldest turns (never the system
 * prompt) until the estimated prompt plus room for the reply fits. The native layer does the
 * same with exact token counts as a safety net; this estimate keeps us from sending megabytes of
 * history and from getting an empty "context full" reply on every turn of a long chat.
 */
object ContextWindow {
  /** Per-message template overhead (role markers, separators). */
  const val MESSAGE_OVERHEAD_TOKENS = 8

  /**
   * Conservative token estimate: most tokenizers average 3–4 UTF-8 bytes per token for English
   * and fewer for code or CJK, so 2.5 bytes/token over-counts rather than under-counts.
   */
  fun estimateTokens(m: ChatMessage): Int {
    val bytes = m.content.toByteArray().size + (m.reasoning?.toByteArray()?.size ?: 0)
    return (bytes / 2.5).toInt() + MESSAGE_OVERHEAD_TOKENS
  }

  /** Room left for the reply: maxTokens (capped at half the context) or a quarter of it. */
  fun reserveFor(nCtx: Int, maxTokens: Int): Int = if (maxTokens > 0) minOf(maxTokens, nCtx / 2) else nCtx / 4

  fun trim(
    messages: List<ChatMessage>,
    nCtx: Int,
    maxTokens: Int,
    estimate: (ChatMessage) -> Int = ::estimateTokens,
  ): List<ChatMessage> {
    val budget = nCtx - reserveFor(nCtx, maxTokens)
    val head = messages.takeWhile { it.role == "system" }.take(1)
    var tail = messages.drop(head.size)
    fun cost() = (head + tail).sumOf(estimate)
    while (tail.size > 1 && cost() > budget) {
      tail = tail.drop(1)
      // Start the window at a user turn so the model never sees a reply without its question.
      while (tail.size > 1 && tail.first().role != "user") tail = tail.drop(1)
    }
    return head + tail
  }
}
