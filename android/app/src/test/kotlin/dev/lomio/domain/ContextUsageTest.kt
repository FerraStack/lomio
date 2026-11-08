package dev.lomio.domain

import dev.lomio.data.db.MessageRole
import dev.lomio.llama.GenerationStats
import dev.lomio.llama.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextUsageTest {
  private fun reply(id: String, prompt: Int, gen: Int) =
    UiMessage(id, MessageRole.ASSISTANT, "r", stats = GenerationStats(prompt, 0, gen, 10, 1.0, 1.0, StopReason.EOS))

  @Test
  fun usageComesFromTheLatestReply() {
    val msgs = listOf(UiMessage("u1", MessageRole.USER, "q"), reply("a1", 1000, 50), UiMessage("u2", MessageRole.USER, "q"), reply("a2", 3000, 190))
    val u = ContextUsage.of(msgs, 4096)!!
    assertEquals(3190, u.usedTokens)
    assertEquals(77, u.percent)
    assertTrue(u.nearlyFull)
    assertFalse(ContextUsage.of(msgs.take(2), 4096)!!.nearlyFull)
  }

  @Test
  fun unknownWithoutAReplyOrRightAfterASummary() {
    assertNull(ContextUsage.of(listOf(UiMessage("u1", MessageRole.USER, "q")), 4096))
    assertNull(ContextUsage.of(listOf(reply("a1", 3000, 10)), null))
    val summarized = listOf(reply("a1", 3000, 10), UiMessage("s1", MessageRole.SYSTEM, "summary"))
    assertNull(ContextUsage.of(summarized, 4096))
  }
}
