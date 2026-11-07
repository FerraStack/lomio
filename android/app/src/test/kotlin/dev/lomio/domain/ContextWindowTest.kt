package dev.lomio.domain

import dev.lomio.llama.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowTest {
  private fun turn(i: Int) = listOf(ChatMessage("user", "question $i " + "x".repeat(200)), ChatMessage("assistant", "answer $i " + "y".repeat(200)))

  @Test
  fun shortChatsAreUntouched() {
    val msgs = listOf(ChatMessage("system", "Be brief")) + turn(1)
    assertEquals(msgs, ContextWindow.trim(msgs, nCtx = 4096, maxTokens = -1))
  }

  @Test
  fun dropsOldestTurnsButKeepsSystemPromptAndStartsAtAUserTurn() {
    val msgs = listOf(ChatMessage("system", "Be brief")) + (1..50).flatMap(::turn) + ChatMessage("user", "latest")
    val trimmed = ContextWindow.trim(msgs, nCtx = 1024, maxTokens = 256)
    assertEquals("system", trimmed.first().role)
    assertEquals("user", trimmed[1].role)
    assertEquals("latest", trimmed.last().content)
    val budget = 1024 - ContextWindow.reserveFor(1024, 256)
    assertTrue(trimmed.sumOf(ContextWindow::estimateTokens) <= budget)
    assertTrue(trimmed.size < msgs.size)
  }

  @Test
  fun alwaysKeepsTheLatestMessage() {
    val huge = ChatMessage("user", "z".repeat(100_000))
    assertEquals(listOf(huge), ContextWindow.trim(listOf(ChatMessage("user", "old"), huge), 512, -1))
  }

  @Test
  fun reserve() {
    assertEquals(256, ContextWindow.reserveFor(4096, 256))
    assertEquals(2048, ContextWindow.reserveFor(4096, 100_000))
    assertEquals(1024, ContextWindow.reserveFor(4096, -1))
  }
}
