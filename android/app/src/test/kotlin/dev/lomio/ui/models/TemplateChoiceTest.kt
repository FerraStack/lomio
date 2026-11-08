package dev.lomio.ui.models

import dev.lomio.llama.ChatTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateChoiceTest {
  @Test
  fun storedValueMapsToMode() {
    assertEquals(TemplateMode.GGUF, TemplateChoice.modeOf(null))
    assertEquals(TemplateMode.GGUF, TemplateChoice.modeOf(""))
    assertEquals(TemplateMode.PRESET, TemplateChoice.modeOf(ChatTemplates.GEMMA.source))
    assertEquals(TemplateMode.CUSTOM, TemplateChoice.modeOf("{{ messages[0].content }}"))
    assertEquals("gemma", TemplateChoice.presetOf(ChatTemplates.GEMMA.source))
    assertEquals("chatml", TemplateChoice.presetOf("something else"))
  }

  @Test
  fun previewUsesTheTemplateTheEngineWillUse() {
    assertEquals("GGUF", TemplateChoice.effective(TemplateMode.GGUF, "llama3", "x", "GGUF"))
    // A GGUF without a template falls back to ChatML, like llama.cpp does.
    assertEquals(ChatTemplates.CHATML.source, TemplateChoice.effective(TemplateMode.GGUF, "llama3", "x", null))
    assertEquals(ChatTemplates.LLAMA3.source, TemplateChoice.effective(TemplateMode.PRESET, "llama3", "x", "GGUF"))
    assertEquals("x", TemplateChoice.effective(TemplateMode.CUSTOM, "llama3", "x", "GGUF"))
  }

  @Test
  fun presetsAreDistinctAndEndWithAnAssistantTurn() {
    assertEquals(ChatTemplates.PRESETS.size, ChatTemplates.PRESETS.map { it.id }.toSet().size)
    assertEquals(ChatTemplates.PRESETS.size, ChatTemplates.PRESETS.map { it.source }.toSet().size)
    ChatTemplates.PRESETS.filter { it.id != "mistral" }.forEach {
      assertTrue(it.id, it.source.contains("add_generation_prompt"))
    }
  }
}
