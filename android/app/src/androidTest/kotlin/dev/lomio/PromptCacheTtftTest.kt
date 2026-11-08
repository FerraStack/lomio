package dev.lomio

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lomio.engine.LoadOutcome
import dev.lomio.llama.ChatMessage
import dev.lomio.llama.GenerationEvent
import dev.lomio.llama.GenerationStats
import dev.lomio.llama.LlamaEngine
import dev.lomio.llama.SamplingParams
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prompt cache on the real engine: chat A (a long prompt), then chat B (evicts A, which is saved
 * to disk), then back to A. With the cache, A's history is restored instead of re-processed;
 * without it (cache file deleted), the same turn re-processes everything. Logs both TTFTs
 * (tag lomio-e2e-cache; CI copies the line into the job summary).
 *
 * Uses the SmolVLM-256M text model CI already pushes for the vision test (loaded without its
 * projector): small enough for the emulator, slow enough at prompt processing to measure.
 */
@RunWith(AndroidJUnit4::class)
class PromptCacheTtftTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app
    get() = instrumentation.targetContext.applicationContext as LomioApp

  private fun copyFromShell(src: String, dest: File) {
    dest.parentFile!!.mkdirs()
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $src")).use { input ->
      FileOutputStream(dest).use { input.copyTo(it) }
    }
  }

  /** [ttftMs]: wall clock from the call to the first generated text, cache restore included. */
  private class Turn(val content: String, val stats: GenerationStats, val ttftMs: Long)

  private suspend fun run(flow: Flow<GenerationEvent>): Turn {
    var done: GenerationEvent.Done? = null
    var first = 0L
    val t0 = System.nanoTime()
    withTimeout(600_000) {
      flow.collect {
        if (first == 0L && ((it is GenerationEvent.Update && it.content.isNotEmpty()) || it is GenerationEvent.Done)) {
          first = System.nanoTime()
        }
        if (it is GenerationEvent.Done) done = it
      }
    }
    return Turn(done!!.content, done!!.stats, (first - t0) / 1_000_000)
  }

  @Test
  fun returningToALongChatRestoresItsKvCache() = runBlocking {
    val c = app.container
    val engine = c.engine as LlamaEngine
    val dest = File(app.filesDir, "models/local/e2e-cache.gguf")
    copyFromShell(VisionChatTest.MODEL_ON_DEVICE, dest)
    assertTrue("model was not pushed", dest.length() > 1_000_000)
    val model = c.models.registerLocalFile(dest, displayName = "SmolVLM 256M (cache)")
    c.settings.update { it.copy(systemPrompt = "", gpuEnabled = false, load = it.load.copy(nCtx = 4096, nThreads = 2, nBatch = 512)) }
    try {
      assertTrue(c.modelManager.load(model.id, force = true) is LoadOutcome.Loaded)
      val sampling = SamplingParams(maxTokens = 8, seed = 7, temperature = 0f)
      val notes =
        (1..45).joinToString(" ") { "Note $it: the garden had $it red tulips and ${it + 3} yellow daisies by the old stone wall." }
      val a1 = listOf(ChatMessage("user", "Here are my garden notes. $notes Summarise them."))
      val keyA = "e2e-cache-A-${System.currentTimeMillis()}"
      val keyB = "e2e-cache-B-${System.currentTimeMillis()}"

      val first = run(engine.chat(a1, sampling, cacheKey = keyA))
      Log.i(TAG, "A1: ${first.stats}")
      assertTrue("prompt should be long (${first.stats.promptTokens})", first.stats.promptTokens > 800)

      val a2 = a1 + ChatMessage("assistant", first.content) + ChatMessage("user", "Which flower was more common?")
      val other = listOf(ChatMessage("user", "Say hello."))

      // With the cache: B evicts A (A is saved), then A comes back from disk.
      run(engine.chat(other, sampling, cacheKey = keyB))
      assertTrue("A's cache was not written", c.promptCache.exists(keyA))
      val cached = run(engine.chat(a2, sampling, cacheKey = keyA))
      Log.i(TAG, "A2 with cache: ${cached.stats}")

      // Without: same switch, but A's cache is gone, so its history is processed again.
      run(engine.chat(other, sampling, cacheKey = keyB))
      engine.forgetCache(keyA)
      val uncached = run(engine.chat(a2, sampling, cacheKey = keyA))
      Log.i(TAG, "A2 without cache: ${uncached.stats}")

      assertEquals(uncached.stats.promptTokens, cached.stats.promptTokens)
      assertTrue(
        "history not restored: cached ${cached.stats.cachedTokens} of ${cached.stats.promptTokens}",
        cached.stats.cachedTokens >= first.stats.promptTokens - 1,
      )
      // Without the cache only a shared BOS/template prefix with chat B can match.
      assertTrue("unexpected reuse: ${uncached.stats.cachedTokens}", uncached.stats.cachedTokens < 16)
      Log.i(
        TAG,
        "RESULT TTFT returning to a ${cached.stats.promptTokens}-token chat (restore from disk included): " +
          "${cached.ttftMs} ms with the prompt cache (${cached.stats.cachedTokens} tokens restored) vs " +
          "${uncached.ttftMs} ms without",
      )
      assertTrue("the cache should make the first token faster", cached.ttftMs < uncached.ttftMs)
    } finally {
      c.modelManager.unload()
      c.settings.update { it.copy(lastModelId = null) }
    }
  }

  companion object {
    const val TAG = "lomio-e2e-cache"
  }
}
