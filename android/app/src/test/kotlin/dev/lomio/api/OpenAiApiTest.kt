package dev.lomio.api

import dev.lomio.llama.ChatMessage
import dev.lomio.llama.GenerationStats
import dev.lomio.llama.SamplingParams
import dev.lomio.llama.StopReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenAiApiTest {
  private fun parse(body: String) = OpenAiApi.parseChatRequest(body)

  private fun rejects(body: String, fragment: String) {
    try {
      parse(body)
      fail("accepted $body")
    } catch (e: ApiError) {
      assertEquals(400, e.status)
      assertTrue("'${e.message}' should mention '$fragment'", e.message.contains(fragment))
    }
  }

  @Test
  fun parsesAMinimalRequest() {
    val r = parse("""{"model":"x","messages":[{"role":"user","content":"Hi"}]}""")
    assertEquals("x", r.model)
    assertEquals(listOf(ChatMessage("user", "Hi")), r.messages)
    assertFalse(r.stream)
    assertTrue(r.enableThinking)
    assertEquals(SamplingOverrides(), r.sampling)
  }

  @Test
  fun parsesSamplingStreamingAndLlamaCppExtras() {
    val r =
      parse(
        """{"messages":[{"role":"developer","content":"Be brief"},{"role":"user","content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}],
           "temperature":0.2,"top_p":0.9,"top_k":20,"min_p":0.1,"max_completion_tokens":64,"max_tokens":999,"seed":7,
           "stop":"END","presence_penalty":0.5,"stream":true,"stream_options":{"include_usage":true},
           "chat_template_kwargs":{"enable_thinking":false},"unknown_field":{"ignored":true}}"""
      )
    assertEquals(listOf(ChatMessage("system", "Be brief"), ChatMessage("user", "a\nb")), r.messages)
    assertEquals(
      SamplingOverrides(temperature = 0.2f, topP = 0.9f, topK = 20, minP = 0.1f, presencePenalty = 0.5f, maxTokens = 64, seed = 7, stop = listOf("END")),
      r.sampling,
    )
    assertTrue(r.stream && r.includeUsage)
    assertFalse(r.enableThinking)
  }

  @Test
  fun overridesOnlyWhatTheRequestSets() {
    val base = SamplingParams(temperature = 0.7f, topK = 40, stop = listOf("x"))
    val merged = SamplingOverrides(temperature = 0.1f, stop = listOf("a", "b")).applyTo(base)
    assertEquals(base.copy(temperature = 0.1f, stop = listOf("a", "b")), merged)
  }

  @Test
  fun rejectsWhatItCannotServe() {
    rejects("not json", "JSON object")
    rejects("""{"messages":[]}""", "must not be empty")
    rejects("""{"model":"x"}""", "'messages' is required")
    rejects("""{"messages":[{"role":"tool","content":"r"}]}""", "tool calls")
    rejects("""{"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"x"}}]}]}""", "only text")
    rejects("""{"messages":[{"role":"user","content":"x"}],"n":2}""", "n = 1")
    rejects("""{"messages":[{"role":"user","content":"x"}],"temperature":"hot"}""", "'temperature'")
    rejects("""{"messages":[{"role":"user","content":"x"}],"max_tokens":0}""", "positive")
  }

  private val stats = GenerationStats(12, 0, 5, 100, 50.0, 20.0, StopReason.MAX_TOKENS)

  @Test
  fun completionHasOpenAiShape() {
    val o = Json.parseToJsonElement(OpenAiApi.completionJson("id1", 1700, "m", "Hello", "", stats)).jsonObject
    assertEquals("chat.completion", o["object"]!!.jsonPrimitive.content)
    val choice = o["choices"]!!.jsonArray[0].jsonObject
    assertEquals("Hello", choice["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
    assertNull("no empty reasoning", choice["message"]!!.jsonObject["reasoning_content"])
    assertEquals("length", choice["finish_reason"]!!.jsonPrimitive.content)
    assertEquals(17, o["usage"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.int)
  }

  @Test
  fun chunksCarryDeltasAndNullFinishReasonUntilTheEnd() {
    val c = Json.parseToJsonElement(OpenAiApi.chunkJson("id1", 1, "m", content = "Hel")).jsonObject
    val choice = c["choices"]!!.jsonArray[0].jsonObject
    assertEquals("Hel", choice["delta"]!!.jsonObject["content"]!!.jsonPrimitive.content)
    assertEquals(JsonNull, choice["finish_reason"])
    val first = Json.parseToJsonElement(OpenAiApi.chunkJson("id1", 1, "m", role = true, content = "")).jsonObject
    assertEquals("assistant", first["choices"]!!.jsonArray[0].jsonObject["delta"]!!.jsonObject["role"]!!.jsonPrimitive.content)
    val usage = Json.parseToJsonElement(OpenAiApi.usageChunkJson("id1", 1, "m", stats)).jsonObject
    assertTrue(usage["choices"]!!.jsonArray.isEmpty())
  }

  @Test
  fun deltasAppendAndSurviveRewrites() {
    assertEquals(" world", OpenAiApi.delta("Hello", "Hello world"))
    assertEquals("", OpenAiApi.delta("Hello", "Hello"))
    // The parser moved "<thi" out of the content: only new text after the common part goes out.
    assertEquals("Answer", OpenAiApi.delta("<thi", "Answer"))
    assertEquals("x", OpenAiApi.delta("abc", "abx"))
  }

  @Test
  fun errorsUseOpenAiShape() {
    val e = Json.parseToJsonElement(OpenAiApi.errorJson(ApiError(401, "nope", code = "invalid_api_key"))).jsonObject["error"]!!.jsonObject
    assertEquals("nope", e["message"]!!.jsonPrimitive.content)
    assertEquals("invalid_api_key", e["code"]!!.jsonPrimitive.content)
  }

  @Test
  fun apiKeysAreLongAndDistinct() {
    val a = ApiServer.newApiKey()
    assertTrue(a.startsWith("lomio-") && a.length == 38)
    assertFalse(a == ApiServer.newApiKey())
  }
}
