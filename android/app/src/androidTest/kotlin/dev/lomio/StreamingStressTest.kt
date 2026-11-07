package dev.lomio

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
        compose.onNodeWithText("New chat").performClick()
        compose.onNode(hasSetTextAction() and hasText("Message")).performTextInput("Once upon a time $round")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(30_000) { c.chatService.state.value.isGenerating }

        var swipes = 0
        while (c.chatService.state.value.isGenerating && swipes < 12) {
          compose.onRoot().performTouchInput { if (swipes % 2 == 0) swipeDown() else swipeUp() }
          swipes++
          if (round % 4 == 3 && swipes == 2) {
            compose.onAllNodesWithContentDescription("Stop").fetchSemanticsNodes().takeIf { it.isNotEmpty() }?.let {
              compose.onNodeWithContentDescription("Stop").performClick()
            }
          }
        }
        compose.waitUntil(60_000) { !c.chatService.state.value.isGenerating }
        compose.waitForIdle()

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

  private companion object {
    const val ROUNDS = 20
  }
}
