package dev.lomio

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ComposeTimeoutException
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lomio.data.db.MessageRole
import dev.lomio.data.db.MessageStatus
import dev.lomio.domain.GenerationSettings
import dev.lomio.engine.LoadOutcome
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Streams replies from the real engine 20 times while the test scrolls the list, opens the
 * overflow menu and stops some replies mid-stream, so recomposition, layout and draw run
 * constantly while tokens arrive. A Compose threading bug (state touched off the main thread,
 * layout/draw observed from two threads) crashes the app or fails the rule here.
 */
@RunWith(AndroidJUnit4::class)
class StreamingStressTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app
    get() = instrumentation.targetContext.applicationContext as LomioApp

  @Test
  fun twentyStreamsWhileScrollingAndStopping() {
    val c = app.container
    val dest = File(app.filesDir, "models/local/e2e-stress.gguf")
    dest.parentFile!!.mkdirs()
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat ${EndToEndChatTest.MODEL_ON_DEVICE}")).use {
      input -> FileOutputStream(dest).use { input.copyTo(it) }
    }
    runBlocking {
      val model = c.models.registerLocalFile(dest, displayName = "Stories 15M (stress)")
      c.settings.update {
        it.copy(
          systemPrompt = "",
          generation = GenerationSettings.DEFAULTS.copy(maxTokens = 96),
          load = it.load.copy(nCtx = 256, nThreads = 2, nBatch = 512),
        )
      }
      assertTrue(c.modelManager.load(model.id, force = true) is LoadOutcome.Loaded)
    }
    try {
      repeat(ROUNDS) { round ->
        // A fresh chat each round keeps the prompt inside the 256-token context.
        compose.onNodeWithContentDescription("More").performClick()
        // The overflow menu's item (the title and the drawer button also say "New chat").
        compose.onNode(hasText("New chat") and hasAnyAncestor(isPopup())).performClick()
        compose.onNode(hasSetTextAction() and hasText("Message")).performTextInput("Once upon a time $round")
        // Explicit "this round's generation has started" signal: either it is streaming now, or it
        // already finished (a 96-token reply from a 15M model can complete before we first look,
        // which made a plain `isGenerating` wait time out).
        val finishedBefore = c.chatService.state.value.lastFinished?.id
        // The composer moves while the keyboard animates in, so a synthetic tap can land where the
        // button was a frame ago (round 5 of #18's run: no reply ever started). Wait until Send is
        // enabled, then invoke its click action directly instead of tapping coordinates.
        await(round, "Send to be enabled with the typed text", 10_000) {
          compose.onAllNodes(hasContentDescription("Send") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasContentDescription("Send") and isEnabled()).performSemanticsAction(SemanticsActions.OnClick)
        await(round, "the reply to start", 30_000) {
          val s = c.chatService.state.value
          s.isGenerating || s.lastFinished?.id != finishedBefore || s.error != null
        }

        var swipes = 0
        while (c.chatService.state.value.isGenerating && swipes < 12) {
          compose.onRoot().performTouchInput { if (swipes % 2 == 0) swipeDown() else swipeUp() }
          swipes++
          if (round % 4 == 3 && swipes == 2) {
            // The reply may end between the check and the tap; a missed Stop is fine here.
            runCatching { compose.onNodeWithContentDescription("Stop").performSemanticsAction(SemanticsActions.OnClick) }
          }
        }
        await(round, "the reply to finish", 60_000) { !c.chatService.state.value.isGenerating }
        compose.waitForIdle()
        Log.i(TAG, "round $round done after $swipes swipes: ${describe()}")

        val conversation = c.chatService.state.value.lastFinished
        assertTrue("round $round: no reply", conversation != null)
        assertNotEquals("round $round: generation failed", MessageStatus.ERROR, conversation!!.status)
        assertEquals(MessageRole.ASSISTANT, conversation.role)
      }
    } finally {
      runBlocking {
        c.modelManager.unload()
        c.settings.update { it.copy(lastModelId = null) }
      }
    }
  }

  /** Current generation state, for failure messages and the log. */
  private fun describe(): String {
    val s = app.container.chatService.state.value
    return "generating=${s.isGenerating} streaming=${s.streaming?.let { "${it.id} (${it.content.length} chars)" }} " +
      "lastFinished=${s.lastFinished?.let { "${it.id} ${it.status}" }} error=${s.error}"
  }

  /** waitUntil that fails with which wait timed out and the generation state, instead of a bare timeout. */
  private fun await(round: Int, what: String, timeoutMs: Long, condition: () -> Boolean) {
    try {
      compose.waitUntil(timeoutMs, condition)
    } catch (e: ComposeTimeoutException) {
      throw AssertionError("round $round: timed out after $timeoutMs ms waiting for $what; ${describe()}", e)
    }
    app.container.chatService.state.value.error?.let { throw AssertionError("round $round: generation error: $it") }
  }

  private companion object {
    const val ROUNDS = 20
    const val TAG = "lomio-e2e-stress"
  }
}
