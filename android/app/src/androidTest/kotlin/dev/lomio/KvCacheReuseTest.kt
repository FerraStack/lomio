package dev.lomio

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lomio.engine.LoadOutcome
import dev.lomio.llama.ChatMessage
import dev.lomio.llama.GenerationEvent
import dev.lomio.llama.GenerationStats
import dev.lomio.llama.SamplingParams
import dev.lomio.llama.StopReason
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #3 on the real engine: Stop during prompt processing must keep the prefix that was
 * already decoded, so sending the same prompt again only processes the rest.
 *
 * Runs straight against the app's engine (no UI) with a tiny batch size, so the prompt takes
 * many batches and the stop lands in the middle of prompt processing.
 */
@RunWith(AndroidJUnit4::class)
class KvCacheReuseTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app
    get() = instrumentation.targetContext.applicationContext as LomioApp

  private fun copyFromShell(src: String, dest: File) {
    dest.parentFile!!.mkdirs()
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $src")).use { input ->
      FileOutputStream(dest).use { input.copyTo(it) }
    }
  }

  private class Run(val stats: GenerationStats, val lastDone: Int, val total: Int, val progressEvents: Int)

  /** Collects one generation; [onProgress] may call stop(). */
  private suspend fun collect(flow: Flow<GenerationEvent>, onProgress: (GenerationEvent.PromptProgress) -> Unit = {}): Run {
    var lastDone = 0
    var total = 0
    var events = 0
    var done: GenerationEvent.Done? = null
    flow.collect { ev ->
      when (ev) {
        is GenerationEvent.PromptProgress -> {
          events++
          lastDone = maxOf(lastDone, ev.done)
          total = ev.total
          onProgress(ev)
        }
        is GenerationEvent.Done -> done = ev
        is GenerationEvent.Update -> Unit
      }
    }
    return Run(done!!.stats, lastDone, total, events)
  }

  @Test
  fun stopDuringPromptKeepsTheDecodedPrefix() = runBlocking {
    val c = app.container
    val dest = File(app.filesDir, "models/local/e2e-kv.gguf")
    copyFromShell(EndToEndChatTest.MODEL_ON_DEVICE, dest)
    assertTrue("model was not pushed", dest.length() > 1_000_000)
    val model = c.models.registerLocalFile(dest, displayName = "Stories 15M (kv)")
    // 4-token batches: an ~800-token prompt takes ~200 decode calls, so a stop after the first
    // batch reliably lands mid-prompt. The context is above the model's 256-token training
    // length; quality doesn't matter here, only the cache bookkeeping.
    c.settings.update { it.copy(systemPrompt = "", load = it.load.copy(nCtx = 1024, nThreads = 2, nBatch = 4)) }
    try {
      assertTrue(c.modelManager.load(model.id, force = true) is LoadOutcome.Loaded)

      val prompt = (1..60).joinToString(" ") { "Lily saw a big red ball number $it in the park." }
      val messages = listOf(ChatMessage("user", prompt))
      val sampling = SamplingParams(maxTokens = 8, seed = 7, temperature = 0f)

      // 1. Stop as soon as the first batch is in the KV cache.
      val stopped =
        withTimeout(120_000) {
          collect(c.engine.chat(messages, sampling)) { if (it.done > 0) c.engine.stop() }
        }
      Log.i(TAG, "run 1: total=${stopped.total} lastDone=${stopped.lastDone} stats=${stopped.stats}")
      assertEquals(StopReason.CANCELLED, stopped.stats.stopReason)
      assertTrue("prompt should be long (${stopped.total} tokens)", stopped.total > 400)
      assertTrue("stop did not land during prompt processing", stopped.lastDone < stopped.total)
      assertEquals(0, stopped.stats.generatedTokens)

      // 2. Same prompt again: everything decoded before the stop is reused.
      val again = withTimeout(120_000) { collect(c.engine.chat(messages, sampling)) }
      Log.i(TAG, "run 2: stats=${again.stats}")
      assertTrue(
        "cached ${again.stats.cachedTokens} < decoded before stop ${stopped.lastDone}",
        again.stats.cachedTokens >= stopped.lastDone,
      )
      assertTrue(again.stats.cachedTokens > 0)
      assertNotEquals(StopReason.CANCELLED, again.stats.stopReason)
      assertTrue("no reply tokens", again.stats.generatedTokens > 0)
      // Progress starts at the reused prefix, not at zero.
      assertTrue(again.progressEvents > 0)

      // 3. A third identical run reuses the whole prompt but the last token.
      val third = withTimeout(120_000) { collect(c.engine.chat(messages, sampling)) }
      assertEquals(third.stats.promptTokens - 1, third.stats.cachedTokens)
    } finally {
      c.modelManager.unload()
      c.settings.update { it.copy(lastModelId = null) }
    }
  }

  companion object {
    const val TAG = "lomio-e2e-kv"
  }
}
