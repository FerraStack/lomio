package dev.lomio.domain

import dev.lomio.data.db.ConversationSummary
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryAndSettingsTest {
  private val now = Instant.parse("2026-11-07T12:00:00Z")
  private val day = 24L * 3600 * 1000

  private fun c(id: String, ageDays: Double, pinned: Boolean = false) =
    ConversationSummary(id, id, pinned, now.toEpochMilli() - (ageDays * day).toLong(), null)

  @Test
  fun groupsIntoBuckets() {
    val groups =
      HistoryGrouping.group(
        listOf(c("p", 40.0, pinned = true), c("t", 0.1), c("y", 1.0), c("w", 3.0), c("lw", 9.0), c("o", 30.0)),
        now,
        ZoneOffset.UTC,
      )
    assertEquals(
      listOf(
        HistoryBucket.PINNED to listOf("p"),
        HistoryBucket.TODAY to listOf("t"),
        HistoryBucket.YESTERDAY to listOf("y"),
        HistoryBucket.THIS_WEEK to listOf("w"),
        HistoryBucket.LAST_WEEK to listOf("lw"),
        HistoryBucket.OLDER to listOf("o"),
      ),
      groups.map { (b, items) -> b to items.map { it.id } },
    )
  }

  @Test
  fun ftsQuerySanitizesInput() {
    assertEquals("rust* async*", HistoryGrouping.ftsQuery("  rust \"async\" "))
    assertEquals("", HistoryGrouping.ftsQuery("*** ()"))
  }

  @Test
  fun titles() {
    assertEquals("Hello there", Titles.fromFirstMessage("  Hello there \nsecond line"))
    val long = "Explain the difference between processes and threads in modern operating systems"
    val t = Titles.fromFirstMessage(long)
    assertEquals("Explain the difference between…", t)
    assertEquals("New chat", Titles.fromFirstMessage("   "))
  }

  @Test
  fun generationResolutionOrder() {
    val global = GenerationSettings(temperature = 0.5f, topK = 20)
    val model = GenerationSettings(temperature = 0.9f, maxTokens = 256)
    val chat = GenerationSettings(temperature = 0.1f)
    val r = SettingsResolver.generation(global, model, chat)
    assertEquals(0.1f, r.temperature)
    assertEquals(20, r.topK)
    assertEquals(256, r.maxTokens)
    assertEquals(GenerationSettings.DEFAULTS.topP, r.topP)
    val p = r.toSamplingParams()
    assertEquals(0.1f, p.temperature)
    assertEquals(256, p.maxTokens)
  }

  @Test
  fun systemPromptResolution() {
    assertEquals("chat", SettingsResolver.systemPrompt("global", "model", "chat"))
    assertEquals("model", SettingsResolver.systemPrompt("global", "model", "  "))
    assertEquals("global", SettingsResolver.systemPrompt("global", null, null))
  }

  @Test
  fun settingsJsonRoundTrip() {
    val g = GenerationSettings(temperature = 0.3f, stop = listOf("</s>"))
    assertEquals(g, SettingsJson.decodeGen(SettingsJson.encodeGen(g)))
    assertEquals(null, SettingsJson.decodeGen("{not json"))
    val l = LoadSettings(nCtx = 2048)
    assertEquals(l, SettingsJson.decodeLoad(SettingsJson.encodeLoad(l)))
    assertEquals(2048, LoadSettings(nThreads = 2).overlay(l).nCtx)
  }
}
