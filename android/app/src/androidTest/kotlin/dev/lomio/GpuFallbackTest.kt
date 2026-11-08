package dev.lomio

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lomio.engine.LoadOutcome
import dev.lomio.llama.EngineState
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GPU acceleration turned on where there is no GPU backend (the x86_64 emulator; CI can't test
 * Adreno): the model must still load, on the CPU, with the reason shown in the chat banner and
 * on the Benchmark screen, and the benchmark must run. Real-device steps: docs/GPU.md.
 */
@RunWith(AndroidJUnit4::class)
class GpuFallbackTest {
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

  @Test
  fun gpuOnLoadsOnTheCpuAndSaysWhy() {
    val c = app.container
    val dest = File(app.filesDir, "models/local/e2e-gpu.gguf")
    copyFromShell(EndToEndChatTest.MODEL_ON_DEVICE, dest)
    assertTrue("model was not pushed", dest.length() > 1_000_000)
    try {
      val model = runBlocking {
        c.settings.update {
          it.copy(gpuEnabled = true, gpuLayers = 99, load = it.load.copy(nCtx = 256, nThreads = 2))
        }
        c.models.registerLocalFile(dest, displayName = "Stories 15M (gpu)")
      }
      val outcome = runBlocking { c.modelManager.load(model.id, force = true) }
      assertTrue("load failed: $outcome", outcome is LoadOutcome.Loaded)
      val info = (outcome as LoadOutcome.Loaded).info
      Log.i(TAG, "backend=${info.backend} gpuNote=${info.gpuNote}")
      assertEquals("CPU", info.backend)
      assertTrue("no reason given: ${info.gpuNote}", info.gpuNote?.contains("no GPU backend") == true)
      assertNotNull(c.modelManager.gpuNotice.value)

      // Chat: the banner explains the fallback.
      compose.waitUntil(10_000) {
        compose.onAllNodesWithText("GPU acceleration isn't working", substring = true).fetchSemanticsNodes().isNotEmpty()
      }
      screenshot("e2e_gpu_1_notice")

      // Benchmark: shows where the model runs and why the GPU isn't used, then a real run.
      compose.onAllNodesWithContentDescription("Open menu").onFirst().performClick()
      compose.waitUntil(5_000) { compose.onAllNodesWithText("Benchmark").fetchSemanticsNodes().isNotEmpty() }
      compose.onAllNodesWithText("Benchmark").onFirst().performClick()
      compose.waitUntil(10_000) { compose.onAllNodesWithText("Runs on: CPU").fetchSemanticsNodes().isNotEmpty() }
      compose.onNodeWithText("Run").performClick()
      compose.waitUntil(180_000) {
        compose.onAllNodesWithText("Text generation", substring = true).fetchSemanticsNodes().isNotEmpty()
      }
      compose.waitUntil(5_000) { compose.onAllNodes(hasText("GPU not used", substring = true)).fetchSemanticsNodes().isNotEmpty() }
      screenshot("e2e_gpu_2_benchmark")
      assertTrue(c.modelManager.engineState.value is EngineState.Loaded)
    } finally {
      runBlocking {
        c.modelManager.unload()
        c.settings.update { it.copy(gpuEnabled = false, lastModelId = null) }
      }
    }
  }

  companion object {
    const val TAG = "lomio-e2e-gpu"
  }
}
