package dev.lomio.api

import dev.lomio.llama.GenerationEvent
import dev.lomio.llama.GenerationStats
import dev.lomio.llama.StopReason
import java.net.ConnectException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The real server on a real socket, driven by OkHttp, with a fake model behind it. */
class ApiServerTest {
  private val stats = GenerationStats(9, 0, 3, 50, 100.0, 20.0, StopReason.EOS)

  private inner class FakeBackend : ApiBackend {
    var model: ApiModel? = ApiModel("tiny-Q4_K_M", 1_700_000_000)
    var last: ChatCompletionRequest? = null
    var events: Flow<GenerationEvent> = flow {
      emit(GenerationEvent.PromptProgress(4, 9))
      emit(GenerationEvent.Update("", "Thinking"))
      emit(GenerationEvent.Update("Hel", "Thinking"))
      emit(GenerationEvent.Update("Hello", "Thinking"))
      emit(GenerationEvent.Done("Hello world", "Thinking", stats))
    }

    override suspend fun model() = model

    override suspend fun chat(request: ChatCompletionRequest): Flow<GenerationEvent> {
      last = request
      return events
    }
  }

  private val backend = FakeBackend()
  private val server = ApiServer(backend, clock = { 1_700_000_123_000 })
  private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
  private val jsonType = "application/json".toMediaType()
  private var port = 0

  private fun start(lan: Boolean = false, token: String? = null) {
    port = server.start(ApiServerConfig(0, lan, token))
  }

  @After fun tearDown() = server.stop()

  private fun url(path: String) = "http://127.0.0.1:$port$path"

  private fun chat(body: String, configure: Request.Builder.() -> Unit = {}): Response =
    http.newCall(Request.Builder().url(url("/v1/chat/completions")).post(body.toRequestBody(jsonType)).apply(configure).build()).execute()

  private fun json(r: Response): JsonObject = Json.parseToJsonElement(r.body.string()).jsonObject

  private val hello = """{"model":"anything","messages":[{"role":"user","content":"Hi"}]}"""

  @Test
  fun listsTheLoadedModel() {
    start()
    http.newCall(Request.Builder().url(url("/v1/models")).build()).execute().use { r ->
      assertEquals(200, r.code)
      val data = json(r)["data"]!!.jsonArray
      assertEquals("tiny-Q4_K_M", data.single().jsonObject["id"]!!.jsonPrimitive.content)
    }
    backend.model = null
    http.newCall(Request.Builder().url(url("/v1/models")).build()).execute().use { r -> assertTrue(json(r)["data"]!!.jsonArray.isEmpty()) }
  }

  @Test
  fun answersWithJson() {
    start()
    chat(hello).use { r ->
      assertEquals(200, r.code)
      assertEquals("no-store", r.header("Cache-Control"))
      val o = json(r)
      val msg = o["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
      assertEquals("Hello world", msg["content"]!!.jsonPrimitive.content)
      assertEquals("Thinking", msg["reasoning_content"]!!.jsonPrimitive.content)
      assertEquals("tiny-Q4_K_M", o["model"]!!.jsonPrimitive.content)
      assertEquals(1_700_000_123L, o["created"]!!.jsonPrimitive.content.toLong())
      assertEquals(12, o["usage"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.int)
    }
    assertEquals("Hi", backend.last!!.messages.single().content)
    assertEquals(1, server.stats.value.served)
  }

  @Test
  fun streamsServerSentEvents() {
    start()
    chat("""{"messages":[{"role":"user","content":"Hi"}],"stream":true,"stream_options":{"include_usage":true}}""").use { r ->
      assertEquals(200, r.code)
      assertTrue(r.header("Content-Type")!!.startsWith("text/event-stream"))
      val lines = r.body.string().lines()
      assertTrue("prompt progress goes out as an SSE comment", lines.any { it.startsWith(": prompt 4/9") })
      val data = lines.filter { it.startsWith("data: ") }.map { it.removePrefix("data: ") }
      assertEquals("[DONE]", data.last())
      val chunks = data.dropLast(1).map { Json.parseToJsonElement(it).jsonObject }
      val first = chunks.first()["choices"]!!.jsonArray[0].jsonObject["delta"]!!.jsonObject
      assertEquals("assistant", first["role"]!!.jsonPrimitive.content)
      fun deltas(field: String) =
        chunks.mapNotNull { c -> c["choices"]!!.jsonArray.firstOrNull()?.jsonObject?.get("delta")?.jsonObject?.get(field)?.jsonPrimitive?.content }.joinToString("")
      assertEquals("Hello world", deltas("content"))
      assertEquals("Thinking", deltas("reasoning_content"))
      val finish = chunks.mapNotNull { c -> c["choices"]!!.jsonArray.firstOrNull()?.jsonObject?.get("finish_reason")?.jsonPrimitive?.takeIf { it.isString }?.content }
      assertEquals(listOf("stop"), finish)
      assertEquals(12, chunks.last()["usage"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.int)
    }
  }

  @Test
  fun explainsWhenNoModelIsLoaded() {
    start()
    backend.model = null
    chat(hello).use { r ->
      assertEquals(503, r.code)
      assertEquals("5", r.header("Retry-After"))
      assertEquals("model_not_loaded", json(r)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }
  }

  @Test
  fun rejectsBadRequests() {
    start()
    chat("{oops").use { assertEquals(400, it.code) }
    http.newCall(Request.Builder().url(url("/v1/chat/completions")).post(hello.toRequestBody("text/plain".toMediaType())).build()).execute().use {
      assertEquals(415, it.code)
    }
    http.newCall(Request.Builder().url(url("/v1/embeddings")).post(hello.toRequestBody(jsonType)).build()).execute().use { assertEquals(404, it.code) }
    http.newCall(Request.Builder().url(url("/v1/chat/completions")).build()).execute().use { assertEquals(405, it.code) }
  }

  @Test
  fun loopbackModeRefusesForeignHostNames() {
    start()
    chat(hello) { header("Host", "evil.example:$port") }.use { assertEquals(403, it.code) }
    chat(hello) { header("Host", "localhost:$port") }.use { assertEquals(200, it.code) }
  }

  @Test
  fun lanModeNeedsTheKey() {
    start(lan = true, token = "lomio-secret")
    chat(hello).use {
      assertEquals(401, it.code)
      assertEquals("Bearer", it.header("WWW-Authenticate"))
    }
    chat(hello) { header("Authorization", "Bearer wrong") }.use { assertEquals(401, it.code) }
    chat(hello) { header("Authorization", "Bearer lomio-secret") }.use { assertEquals(200, it.code) }
  }

  @Test
  fun acceptsChunkedRequestBodies() {
    start()
    val chunked =
      object : RequestBody() {
        override fun contentType(): MediaType = jsonType

        override fun contentLength() = -1L

        override fun writeTo(sink: BufferedSink) {
          sink.writeUtf8(hello)
        }
      }
    http.newCall(Request.Builder().url(url("/v1/chat/completions")).post(chunked).build()).execute().use { assertEquals(200, it.code) }
  }

  @Test
  fun aClientThatHangsUpCancelsItsGeneration() {
    start()
    val cancelled = CompletableDeferred<Unit>()
    backend.events = flow {
      emit(GenerationEvent.Update("a", ""))
      try {
        delay(60_000)
      } finally {
        cancelled.complete(Unit)
      }
    }
    val call = http.newCall(Request.Builder().url(url("/v1/chat/completions")).post("""{"messages":[{"role":"user","content":"Hi"}],"stream":true}""".toRequestBody(jsonType)).build())
    val r = call.execute()
    val source = r.body.source()
    // Wait until generation is under way, then hang up.
    while (source.readUtf8Line()?.contains("\"a\"") != true) Unit
    r.close()
    call.cancel()
    runBlocking { withTimeout(5_000) { cancelled.await() } }
  }

  @Test
  fun runsOneGenerationAtATime() {
    start()
    val running = AtomicInteger()
    val peak = AtomicInteger()
    backend.events = flow {
      peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
      delay(200)
      running.decrementAndGet()
      emit(GenerationEvent.Done("ok", "", stats))
    }
    val threads = List(3) { Thread { chat(hello).use { assertEquals(200, it.code) } } }
    threads.forEach(Thread::start)
    threads.forEach { it.join(10_000) }
    assertEquals(1, peak.get())
    assertEquals(3, server.stats.value.served)
    assertEquals(0, server.stats.value.active)
  }

  @Test
  fun stopFreesThePort() {
    start()
    assertTrue(server.isRunning)
    server.stop()
    assertFalse(server.isRunning)
    try {
      http.newCall(Request.Builder().url(url("/v1/models")).build()).execute().close()
      fail("still listening")
    } catch (_: ConnectException) {}
  }
}
