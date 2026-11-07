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

    val updated = ChatReducer.reduce(started, GenEvent.Update("a1", "Hel", "think"))
    assertEquals("Hel", updated.streaming!!.content)
    assertEquals("think", updated.streaming!!.reasoning)

    val done = ChatReducer.reduce(updated, GenEvent.Finished("a1", "Hello", "", stats()))
    assertFalse(done.isGenerating)
    assertEquals("Hello", done.lastFinished!!.content)
    assertNull(done.lastFinished!!.reasoning)
    assertEquals(MessageStatus.COMPLETE, done.lastFinished!!.status)
  }

  @Test
  fun promptProgressShowsUntilTheFirstToken() {
    val started = run(GenEvent.Started("c1", "a1", 5))
    val p = ChatReducer.reduce(started, GenEvent.PromptProgress("a1", 300, 1200))
    assertEquals(0.25f, p.streaming!!.promptProgress!!, 1e-6f)
    // Another message's progress is ignored.
    assertEquals(p, ChatReducer.reduce(p, GenEvent.PromptProgress("other", 900, 1200)))
    // The first token clears it, and later progress events can't bring it back.
    val first = ChatReducer.reduce(p, GenEvent.Update("a1", "Once", ""))
    assertNull(first.streaming!!.promptProgress)
    assertNull(ChatReducer.reduce(first, GenEvent.PromptProgress("a1", 1200, 1200)).streaming!!.promptProgress)
    // Empty prompt counts as done; values are clamped.
    assertEquals(1f, ChatReducer.reduce(started, GenEvent.PromptProgress("a1", 0, 0)).streaming!!.promptProgress!!, 0f)
    assertEquals(1f, ChatReducer.reduce(started, GenEvent.PromptProgress("a1", 9, 3)).streaming!!.promptProgress!!, 0f)
  }

  @Test
  fun stopDuringPromptClearsProgress() {
    val s = run(
      GenEvent.Started("c1", "a1", 5),
      GenEvent.PromptProgress("a1", 64, 512),
      GenEvent.Finished("a1", "", "", stats(StopReason.CANCELLED)),
    )
    assertNull(s.lastFinished!!.promptProgress)
    assertEquals(MessageStatus.STOPPED, s.lastFinished!!.status)
  }

  @Test
  fun cancelledMeansStopped() {
    val s = run(GenEvent.Started("c1", "a1", 5), GenEvent.Finished("a1", "Hi", "", stats(StopReason.CANCELLED)))
    assertEquals(MessageStatus.STOPPED, s.lastFinished!!.status)
  }

  @Test
  fun failureKeepsPartialAndError() {
    val s = run(GenEvent.Started("c1", "a1", 5), GenEvent.Update("a1", "par", ""), GenEvent.Failed("a1", "boom"))
    assertFalse(s.isGenerating)
    assertEquals("boom", s.error)
    assertEquals(MessageStatus.ERROR, s.lastFinished!!.status)
    assertNull(ChatReducer.reduce(s, GenEvent.ErrorShown).error)
  }

  @Test
  fun errorStopReasonIsStoredAsError() {
    val s = run(GenEvent.Started("c1", "a1", 5), GenEvent.Finished("a1", "x", "", stats(StopReason.ERROR)))
    assertEquals(MessageStatus.ERROR, s.lastFinished!!.status)
    assertEquals(MessageStatus.COMPLETE, statusFor(StopReason.CONTEXT_FULL))
  }

  @Test
  fun eventsForAnotherAssistantMessageAreIgnored() {
    val s = run(GenEvent.Started("c1", "a1", 5), GenEvent.Update("a1", "mine", ""))
    // A stray second generation must not mix its tokens into, or finish, the current reply.
    assertEquals("mine", ChatReducer.reduce(s, GenEvent.Update("a2", "theirs", "")).streaming!!.content)
    assertTrue(ChatReducer.reduce(s, GenEvent.Finished("a2", "theirs", "", stats())).isGenerating)
    assertTrue(ChatReducer.reduce(s, GenEvent.Failed("a2", "boom")).isGenerating)
    assertFalse(ChatReducer.reduce(s, GenEvent.Failed(null, "no model")).isGenerating)
  }

  @Test
  fun updatesWithoutGenerationAreIgnored() {
    assertEquals(GenerationState(), run(GenEvent.Update("a1", "x", "y")))
  }

  @Test
  fun visibleMessagesOverlayStreamingCopy() {
    val user = UiMessage("u1", MessageRole.USER, "hi", createdAt = 1)
    val dbAssistant = UiMessage("a1", MessageRole.ASSISTANT, "", status = MessageStatus.STREAMING, createdAt = 2)
    val state = run(GenEvent.Started("c1", "a1", 2), GenEvent.Update("a1", "Hello", ""))

    val visible = ChatReducer.visibleMessages("c1", listOf(user, dbAssistant), state)
    assertEquals(listOf("hi", "Hello"), visible.map { it.content })

    // Another conversation is unaffected.
    assertEquals(listOf(user), ChatReducer.visibleMessages("c2", listOf(user), state))

    // DB hasn't caught up yet: the streaming message is appended.
    assertEquals(2, ChatReducer.visibleMessages("c1", listOf(user), state).size)

    // Finished and the DB has the final row: trust the DB.
    val finished = ChatReducer.reduce(state, GenEvent.Finished("a1", "Hello!", "", stats()))
    val dbFinal = dbAssistant.copy(content = "Hello!", status = MessageStatus.COMPLETE)
    assertEquals(dbFinal, ChatReducer.visibleMessages("c1", listOf(user, dbFinal), finished)[1])
  }
}
