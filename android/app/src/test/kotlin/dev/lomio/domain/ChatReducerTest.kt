package dev.lomio.domain

import dev.lomio.data.db.MessageRole
import dev.lomio.data.db.MessageStatus
import dev.lomio.llama.GenerationStats
import dev.lomio.llama.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReducerTest {
  private fun stats(reason: StopReason = StopReason.EOS) = GenerationStats(10, 0, 5, 100, 50.0, 10.0, reason)

  private fun run(vararg events: GenEvent) = events.fold(GenerationState(), ChatReducer::reduce)

  @Test
  fun streamingLifecycle() {
    val started = run(GenEvent.Started("c1", "a1", 5))
    assertTrue(started.isGenerating)
    assertEquals(MessageStatus.STREAMING, started.streaming!!.status)

    val updated = ChatReducer.reduce(started, GenEvent.Update("Hel", "think"))
    assertEquals("Hel", updated.streaming!!.content)
    assertEquals("think", updated.streaming!!.reasoning)

    val done = ChatReducer.reduce(updated, GenEvent.Finished("Hello", "", stats()))
    assertFalse(done.isGenerating)
    assertEquals("Hello", done.lastFinished!!.content)
    assertNull(done.lastFinished!!.reasoning)
    assertEquals(MessageStatus.COMPLETE, done.lastFinished!!.status)
  }

  @Test
  fun cancelledMeansStopped() {
    val s = run(GenEvent.Started("c1", "a1", 5), GenEvent.Finished("Hi", "", stats(StopReason.CANCELLED)))
    assertEquals(MessageStatus.STOPPED, s.lastFinished!!.status)
  }

  @Test
  fun failureKeepsPartialAndError() {
    val s = run(GenEvent.Started("c1", "a1", 5), GenEvent.Update("par", ""), GenEvent.Failed("boom"))
    assertFalse(s.isGenerating)
    assertEquals("boom", s.error)
    assertEquals(MessageStatus.ERROR, s.lastFinished!!.status)
    assertNull(ChatReducer.reduce(s, GenEvent.ErrorShown).error)
  }

  @Test
  fun updatesWithoutGenerationAreIgnored() {
    assertEquals(GenerationState(), run(GenEvent.Update("x", "y")))
  }

  @Test
  fun visibleMessagesOverlayStreamingCopy() {
    val user = UiMessage("u1", MessageRole.USER, "hi", createdAt = 1)
    val dbAssistant = UiMessage("a1", MessageRole.ASSISTANT, "", status = MessageStatus.STREAMING, createdAt = 2)
    val state = run(GenEvent.Started("c1", "a1", 2), GenEvent.Update("Hello", ""))

    val visible = ChatReducer.visibleMessages("c1", listOf(user, dbAssistant), state)
    assertEquals(listOf("hi", "Hello"), visible.map { it.content })

    // Another conversation is unaffected.
    assertEquals(listOf(user), ChatReducer.visibleMessages("c2", listOf(user), state))

    // DB hasn't caught up yet: the streaming message is appended.
    assertEquals(2, ChatReducer.visibleMessages("c1", listOf(user), state).size)

    // Finished and the DB has the final row: trust the DB.
    val finished = ChatReducer.reduce(state, GenEvent.Finished("Hello!", "", stats()))
    val dbFinal = dbAssistant.copy(content = "Hello!", status = MessageStatus.COMPLETE)
    assertEquals(dbFinal, ChatReducer.visibleMessages("c1", listOf(user, dbFinal), finished)[1])
  }
}
