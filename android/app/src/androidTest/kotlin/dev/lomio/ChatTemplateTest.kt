package dev.lomio

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lomio.domain.LoadSettings
import dev.lomio.engine.LoadOutcome
import dev.lomio.llama.ChatMessage
import dev.lomio.llama.ChatTemplates
import dev.lomio.llama.GenerationEvent
import dev.lomio.llama.LlamaException
import dev.lomio.llama.SamplingParams
import dev.lomio.llama.StopReason
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Chat template overrides on the real native engine: every preset renders with llama.cpp's
 * Jinja engine, a broken template reports an error, and a per-model override is what the
 * engine actually uses after a load.
 */
@RunWith(AndroidJUnit4::class)
class ChatTemplateTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app
    get() = instrumentation.targetContext.applicationContext as LomioApp

  @Test
  fun everyPresetRendersTheSampleConversation() {
    for (p in ChatTemplates.PRESETS) {
      val out = ChatTemplates.format(p.source)
      Log.i(TAG, "${p.id}: ${out.replace("\n", "\\n")}")
      assertTrue("${p.id} lost the user turn: $out", out.contains("What is 2 + 2?"))
      assertTrue("${p.id} lost the assistant turn: $out", out.contains("Hi! How can I help?"))
    }
    assertEquals(
      "<|im_start|>user\nHi<|im_end|>\n<|im_start|>assistant\n",
      ChatTemplates.format(ChatTemplates.CHATML.source, listOf(ChatMessage("user", "Hi"))),
    )
    assertTrue(ChatTemplates.format(ChatTemplates.LLAMA3.source).endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
    assertTrue(ChatTemplates.format(ChatTemplates.GEMMA.source).endsWith("<start_of_turn>model\n"))
  }

  @Test
  fun brokenTemplateThrows() {
    try {
      ChatTemplates.format("{% for m in messages %}{{ m.content }")
      fail("expected a template error")
    } catch (e: LlamaException) {
      Log.i(TAG, "broken template: ${e.message}")
      assertTrue(e.message!!.isNotBlank())
    }
  }

  @Test
  fun modelOverrideIsUsedByTheEngine() = runBlocking {
    val c = app.container
    val dest = File(app.filesDir, "models/local/e2e-template.gguf")
    dest.parentFile!!.mkdirs()
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat ${EndToEndChatTest.MODEL_ON_DEVICE}")).use {
      input -> FileOutputStream(dest).use { input.copyTo(it) }
    }
    val model = c.models.registerLocalFile(dest, displayName = "Stories 15M (template)")
    c.settings.update { it.copy(systemPrompt = "", load = it.load.copy(nCtx = 256, nThreads = 2, nBatch = 512)) }
    val messages = listOf(ChatMessage("user", "Once upon a time"))
    val sampling = SamplingParams(maxTokens = 4, seed = 3, temperature = 0f)

    suspend fun promptTokens(): Int {
      assertTrue(c.modelManager.load(model.id, force = true) is LoadOutcome.Loaded)
      val done = withTimeout(60_000) { c.engine.chat(messages, sampling).filterIsInstance<GenerationEvent.Done>().first() }
      assertNotEquals(StopReason.ERROR, done.stats.stopReason)
      return done.stats.promptTokens
    }

    try {
      // stories15M has no template in its GGUF, so the default is ChatML.
      val chatml = promptTokens()
      // Llama 3 markers tokenize very differently from ChatML's.
      val llama3 = LoadSettings(chatTemplate = ChatTemplates.LLAMA3.source)
      c.models.saveSettings(model.id, null, llama3, null)
      val overridden = promptTokens()
      Log.i(TAG, "prompt tokens: default=$chatml llama3 override=$overridden")
      assertNotEquals("the template override was not applied", chatml, overridden)
      assertEquals(llama3.chatTemplate, c.modelManager.activeLoadSettings?.chatTemplate)

      // A broken override falls back to the GGUF's template instead of failing the load.
      c.models.saveSettings(model.id, null, LoadSettings(chatTemplate = "{% if %}"), null)
      assertEquals(chatml, promptTokens())
    } finally {
      c.modelManager.unload()
      c.models.saveSettings(model.id, null, null, null)
      c.settings.update { it.copy(lastModelId = null) }
    }
  }

  companion object {
    const val TAG = "lomio-e2e-template"
  }
}
