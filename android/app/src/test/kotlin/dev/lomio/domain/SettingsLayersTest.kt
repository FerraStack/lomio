package dev.lomio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsLayersTest {
  private val global = GenerationSettings(temperature = 0.5f, topK = 20)
  private val model = GenerationSettings(temperature = 0.9f, maxTokens = 256)
  private val chat = GenerationSettings(topK = 5)

  @Test
  fun resolutionOrderIsGlobalThenModelThenChat() {
    val r = SettingsResolver.generation(global, model, chat)
    assertEquals(0.9f, r.temperature) // model beats global
    assertEquals(5, r.topK) // chat beats global
    assertEquals(256, r.maxTokens) // model only
    assertEquals(GenerationSettings.DEFAULTS.topP, r.topP) // nobody: app default
  }

  @Test
  fun eachLayerSeesWhatItInherits() {
    val modelBase = SettingsResolver.modelBase(global)
    assertEquals(0.5f, modelBase.temperature)
    assertEquals(GenerationSettings.DEFAULTS.minP, modelBase.minP)
    val chatBase = SettingsResolver.chatBase(global, model)
    assertEquals(0.9f, chatBase.temperature)
    assertEquals(20, chatBase.topK)
    // The chat layer on top of its base equals the full resolution.
    assertEquals(SettingsResolver.generation(global, model, chat), chatBase.overlay(chat))
  }

  @Test
  fun sourceIsTheMostSpecificLayerThatSetsAValue() {
    assertEquals(SettingSource.CHAT, SettingsResolver.sourceOf(global.topK, model.topK, chat.topK))
    assertEquals(SettingSource.MODEL, SettingsResolver.sourceOf(global.temperature, model.temperature, chat.temperature))
    assertEquals(SettingSource.GLOBAL, SettingsResolver.sourceOf(global.topK, model.topK))
    assertEquals(SettingSource.APP_DEFAULT, SettingsResolver.sourceOf(null, null, null))
  }

  @Test
  fun chatTemplateIsAModelLoadSetting() {
    val g = LoadSettings(nCtx = 4096, nBatch = 512)
    val m = LoadSettings(nCtx = 2048, chatTemplate = "{{ messages }}")
    val r = SettingsResolver.load(g, m)
    assertEquals(2048, r.nCtx)
    assertEquals(512, r.nBatch)
    assertEquals("{{ messages }}", r.toLoadParams().chatTemplate)
    // Blank means "use the GGUF's".
    assertNull(LoadSettings(chatTemplate = "  ").toLoadParams().chatTemplate)
    assertEquals(m, SettingsJson.decodeLoad(SettingsJson.encodeLoad(m)))
  }
}
