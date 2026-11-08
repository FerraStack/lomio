package dev.lomio

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.isInternal
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lomio.data.Attachments
import dev.lomio.data.StatsCodec
import dev.lomio.data.db.MessageRole
import dev.lomio.data.db.MessageStatus
import dev.lomio.domain.GenerationSettings
import dev.lomio.llama.EngineState
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.hamcrest.CoreMatchers.not
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vision end to end on the real engine: a small vision model (SmolVLM-256M + its mmproj, pushed
 * by CI), the attach button → system Photo Picker (stubbed with espresso-intents, it is another
 * app) → thumbnail chip → send → a non-empty answer about the image.
 */
@RunWith(AndroidJUnit4::class)
class VisionChatTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app
    get() = instrumentation.targetContext.applicationContext as LomioApp

  private fun shell(cmd: String): String =
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).use {
      it.readBytes().decodeToString()
    }

  private fun copyFromShell(src: String, dest: File) {
    dest.parentFile!!.mkdirs()
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $src")).use { input ->
      FileOutputStream(dest).use { input.copyTo(it) }
    }
  }

  private fun screenshot(name: String) {
    compose.waitForIdle()
    shell("mkdir -p ${EndToEndChatTest.SHOT_DIR}")
    shell("screencap -p ${EndToEndChatTest.SHOT_DIR}/$name.png")
  }

  /** A simple drawn scene: blue sky, yellow sun, green hill, red house with a brown roof. */
  private fun sceneUri(): Uri {
    val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(Color.rgb(120, 180, 235))
    paint.color = Color.rgb(250, 205, 60)
    canvas.drawCircle(510f, 100f, 56f, paint)
    paint.color = Color.rgb(90, 160, 80)
    canvas.drawOval(-160f, 300f, 800f, 720f, paint)
    paint.color = Color.rgb(200, 70, 60)
    canvas.drawRect(210f, 240f, 370f, 370f, paint)
    paint.color = Color.rgb(110, 60, 40)
    canvas.drawPath(Path().apply { moveTo(190f, 240f); lineTo(290f, 165f); lineTo(390f, 240f); close() }, paint)
    val file = File(app.cacheDir, "e2e-scene.png")
    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return Uri.fromFile(file)
  }

  @After
  fun tearDown() {
    runCatching { Intents.release() }
  }

  @Test
  fun attachesAnImageAndGetsAnAnswer() {
    val c = app.container
    val modelDest = File(app.filesDir, "models/local/e2e-vision.gguf")
    val projDest = File(app.filesDir, "models/local/e2e-vision-mmproj.gguf")
    copyFromShell(MODEL_ON_DEVICE, modelDest)
    copyFromShell(MMPROJ_ON_DEVICE, projDest)
    assertTrue("vision model was not pushed", modelDest.length() > 1_000_000)
    assertTrue("projector was not pushed", projDest.length() > 1_000_000)

    runBlocking {
      val m = c.models.registerLocalFile(modelDest, displayName = MODEL_NAME)
      c.db.models().upsert(
        m.copy(
          mmprojFile = projDest.name,
          mmprojPath = projDest.absolutePath,
          mmprojSize = projDest.length(),
        )
      )
      c.settings.update {
        it.copy(
          systemPrompt = "",
          generation = GenerationSettings.DEFAULTS.copy(maxTokens = 48, seed = 42, temperature = 0f),
          load = it.load.copy(nCtx = 4096, nThreads = 2),
        )
      }
    }

    // Earlier test classes run in the same process: close any leftover system dialog first.
    shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
    compose.onNodeWithContentDescription("Choose model").performClick()
    compose.waitUntil(10_000) { compose.onAllNodesWithText(MODEL_NAME, substring = true).fetchSemanticsNodes().isNotEmpty() }
    compose.onAllNodesWithText(MODEL_NAME, substring = true).onFirst().performClick()
    compose.waitUntil(30_000) { c.modelManager.engineState.value !is EngineState.Idle }
    // Model + projector load, including the projector's warm-up encode.
    compose.waitUntil(300_000) { c.modelManager.activeModelId.value != null }
    val info = (c.modelManager.engineState.value as EngineState.Loaded).info
    assertTrue("projector not loaded", info.supportsVision)
    // The model sheet closes on pick, but its hide animation can stall while the projector's
    // warm-up keeps the emulator's CPUs busy. Give it time, then fall back to a system Back
    // (an input event: Espresso's pressBack needs window focus, which the sheet holds).
    val sheetOpen = { compose.onAllNodesWithText("Choose a model").fetchSemanticsNodes().isNotEmpty() }
    runCatching { compose.waitUntil(60_000) { !sheetOpen() } }
    if (sheetOpen()) {
      shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
      shell("input keyevent KEYCODE_BACK")
      compose.waitUntil(30_000) { !sheetOpen() }
    }

    // Attach: the Photo Picker is another app, so answer its intent with our image.
    Intents.init()
    intending(not(isInternal())).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(sceneUri())))
    compose.onNodeWithContentDescription("Attach image").performClick()
    compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("Attached image 1")).fetchSemanticsNodes().isNotEmpty() }
    compose.onNode(hasSetTextAction() and hasText("Message")).performTextInput("What is in this image?")
    screenshot("e2e_vision_1_attached")

    val t0 = System.currentTimeMillis()
    compose.onNodeWithContentDescription("Send").performClick()
    compose.waitUntil(30_000) { c.chatService.state.value.isGenerating }
    compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Attached image 1")).fetchSemanticsNodes().isNotEmpty() }
    screenshot("e2e_vision_2_sent")

    compose.waitUntil(900_000) { !c.chatService.state.value.isGenerating }
    compose.waitUntil(15_000) { compose.onAllNodesWithText("tok/s", substring = true).fetchSemanticsNodes().isNotEmpty() }
    screenshot("e2e_vision_3_answer")

    val conversationId = runBlocking { c.chats.observeHistory("").first() }.first().id
    val messages = runBlocking { c.chats.messages(conversationId) }
    val user = messages.first { it.role == MessageRole.USER }
    assertEquals(1, Attachments.decode(user.attachments).size)
    val reply = messages.last()
    assertEquals(MessageRole.ASSISTANT, reply.role)
    assertEquals(MessageStatus.COMPLETE, reply.status)
    assertTrue("empty answer to the image", reply.content.isNotBlank())
    val stats = StatsCodec.decode(reply.statsJson)!!
    // The prompt holds the image's tokens, so it is far longer than the question alone.
    assertTrue("image tokens missing from the prompt (${stats.promptTokens})", stats.promptTokens > 50)
    Log.i(TAG, "vision answer in ${System.currentTimeMillis() - t0} ms: stats=$stats reply=${reply.content}")
  }

  companion object {
    const val TAG = "lomio-e2e-vision"
    const val MODEL_ON_DEVICE = "/data/local/tmp/e2e-vision.gguf"
    const val MMPROJ_ON_DEVICE = "/data/local/tmp/e2e-vision-mmproj.gguf"
    const val MODEL_NAME = "SmolVLM 256M (e2e)"
  }
}
