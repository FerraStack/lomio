package dev.lomio

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lomio.data.db.MessageRole
import dev.lomio.data.db.MessageStatus
import dev.lomio.domain.GenerationSettings
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the native engine end to end on a device/emulator: import a tiny GGUF, load it from
 * the model sheet, send a message, see tokens stream and the stats line appear.
 *
 * CI pushes the model to [MODEL_ON_DEVICE] first (scripts/ci/emulator-e2e.sh) and pulls the
 * screenshots from [SHOT_DIR] afterwards.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndChatTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app
    get() = instrumentation.targetContext.applicationContext as LomioApp

  private fun shell(cmd: String): String =
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).use {
      it.readBytes().decodeToString()
    }

  /** The shell user can read /data/local/tmp; the app can't, so stream the file through it. */
  private fun copyFromShell(src: String, dest: File) {
    dest.parentFile!!.mkdirs()
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $src")).use { input ->
      FileOutputStream(dest).use { input.copyTo(it) }
    }
  }

  private fun screenshot(name: String) {
    compose.waitForIdle()
    shell("mkdir -p $SHOT_DIR")
    shell("screencap -p $SHOT_DIR/$name.png")
  }

  @Test
  fun loadsModelAndStreamsAReply() {
    val c = app.container
    val dest = File(app.filesDir, "models/local/e2e-model.gguf")
    copyFromShell(MODEL_ON_DEVICE, dest)
    assertTrue("model was not pushed to $MODEL_ON_DEVICE", dest.length() > 1_000_000)

    runBlocking {
      c.models.registerLocalFile(dest, displayName = MODEL_NAME)
      c.settings.update {
        it.copy(
          systemPrompt = "",
          generation = GenerationSettings.DEFAULTS.copy(maxTokens = 160, seed = 42),
          // stories15M was trained with a 256-token context.
          load = it.load.copy(nCtx = 256, nThreads = 2),
        )
      }
    }

    // Tap the title -> model sheet -> pick the model.
    compose.onNodeWithContentDescription("Choose model").performClick()
    compose.waitUntil(10_000) { compose.onAllNodesWithText(MODEL_NAME).fetchSemanticsNodes().isNotEmpty() }
    compose.onAllNodesWithText(MODEL_NAME).onFirst().performClick()
    screenshot("e2e_1_loading")
    compose.waitUntil(120_000) { c.modelManager.activeModelId.value != null }

    compose.onNode(hasSetTextAction()).performTextInput("Once upon a time")
    compose.onNodeWithContentDescription("Send").performClick()

    compose.waitUntil(60_000) { c.chatService.state.value.streaming?.content?.isNotEmpty() == true || hasStats() }
    screenshot("e2e_2_streaming")

    compose.waitUntil(180_000) { !c.chatService.state.value.isGenerating }
    compose.waitUntil(15_000) { hasStats() }
    screenshot("e2e_3_finished")

    val conversationId = runBlocking { c.chats.observeHistory("").first() }.first().id
    val reply = runBlocking { c.chats.messages(conversationId) }.last()
    assertEquals(MessageRole.ASSISTANT, reply.role)
    assertEquals(MessageStatus.COMPLETE, reply.status)
    assertTrue("empty reply", reply.content.isNotBlank())
    assertTrue("no stats", reply.statsJson?.contains("generatedTokens") == true)
  }

  private fun hasStats() =
    compose.onAllNodesWithText("tok/s", substring = true).fetchSemanticsNodes().isNotEmpty()

  companion object {
    const val MODEL_ON_DEVICE = "/data/local/tmp/e2e-model.gguf"
    const val MODEL_NAME = "Stories 15M (e2e)"
    const val SHOT_DIR = "/data/local/tmp/e2e"
  }
}
