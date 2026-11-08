package dev.lomio

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lomio.api.ApiServerStatus
import dev.lomio.domain.GenerationSettings
import dev.lomio.engine.LoadOutcome
import java.io.File
import java.io.FileOutputStream
import java.net.ConnectException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.app.NotificationManager
import dev.lomio.api.ApiServerService

/**
 * The local OpenAI-compatible server on a real device with the real engine: switched on from
 * Settings, then driven over HTTP like any client would (models list, streamed and plain chat
 * completions), then switched off again.
 */
@RunWith(AndroidJUnit4::class)
class ApiServerEndToEndTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app
    get() = instrumentation.targetContext.applicationContext as LomioApp

  private val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()
  private val json = "application/json".toMediaType()
  /** Set from the server's own report once it is bound (not assumed to be [PORT]). */
  private var base = ""

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
    E2eShots.capture(name)
  }

  private fun serverSwitch() = compose.onNode(hasText("OpenAI-compatible server") and isToggleable())

  @Test
  fun servesTheLoadedModelOverHttp() {
    val c = app.container
    // No system permission dialog over the app when the switch asks for notifications.
    shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
    val dest = File(c.models.modelsDir, "local/$MODEL_FILE.gguf")
    copyFromShell(EndToEndChatTest.MODEL_ON_DEVICE, dest)
    val model = runBlocking {
      c.settings.update {
        it.copy(
          generation = GenerationSettings.DEFAULTS.copy(seed = 42),
          load = it.load.copy(nCtx = 256, nThreads = 2), // stories15M was trained with 256 tokens
        )
      }
      c.apiServer.settings.update { it.copy(port = PORT, lan = false) }
      c.models.registerLocalFile(dest, displayName = "Stories (api e2e)")
    }
    try {
      exercise(model.id)
    } finally {
      // Never leave the server running or the model loaded for the next test class.
      runBlocking {
        c.apiServer.setEnabled(false).join() // also stops the foreground service
        c.apiServer.stopServer()
        c.modelManager.unload()
        c.models.delete(model.id)
      }
    }
  }

  private fun exercise(modelId: String) {
    val c = app.container
    val loaded = runBlocking { c.modelManager.load(modelId, force = true) }
    assertTrue("load failed: $loaded", loaded is LoadOutcome.Loaded)

    // Chat → drawer → Settings → switch the server on.
    compose.onNodeWithContentDescription("Open menu").performClick()
    compose.waitForIdle()
    compose.onAllNodesWithText("Settings").onFirst().performClick()
    serverSwitch().performScrollTo().performClick()
    compose.waitUntil(20_000) { c.apiServer.status.value is ApiServerStatus.Running }
    val port = (c.apiServer.status.value as ApiServerStatus.Running).port
    base = "http://127.0.0.1:$port/v1"
    assertEquals(PORT, port)
    // The status is set right after bind; wait until the socket really accepts connections.
    compose.waitUntil(10_000) {
      runCatching { java.net.Socket("127.0.0.1", port).use { true } }.getOrDefault(false)
    }

    // GET /v1/models lists the loaded model under its file name.
    http.newCall(Request.Builder().url("$base/models").build()).execute().use { r ->
      assertEquals(200, r.code)
      val ids = Json.parseToJsonElement(r.body.string()).jsonObject["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
      assertEquals(listOf(MODEL_FILE), ids)
    }

    // Streamed completion: SSE chunks with text, a finish reason, usage and [DONE].
    val streamBody =
      """{"model":"$MODEL_FILE","messages":[{"role":"user","content":"Once upon a time"}],"max_tokens":24,"stream":true,"stream_options":{"include_usage":true}}"""
    http.newCall(Request.Builder().url("$base/chat/completions").post(streamBody.toRequestBody(json)).build()).execute().use { r ->
      assertEquals(200, r.code)
      val data = r.body.string().lines().filter { it.startsWith("data: ") }.map { it.removePrefix("data: ") }
      assertEquals("[DONE]", data.last())
      val chunks = data.dropLast(1).map { Json.parseToJsonElement(it).jsonObject }
      val text =
        chunks.joinToString("") { c ->
          c["choices"]!!.jsonArray.firstOrNull()?.jsonObject?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.content.orEmpty()
        }
      assertTrue("no streamed text", text.isNotBlank())
      val usage = chunks.last()["usage"]!!.jsonObject
      assertTrue(usage["completion_tokens"]!!.jsonPrimitive.int in 1..24)
    }

    // Plain JSON completion.
    val body = """{"messages":[{"role":"user","content":"The little dog"}],"max_tokens":16}"""
    http.newCall(Request.Builder().url("$base/chat/completions").post(body.toRequestBody(json)).build()).execute().use { r ->
      assertEquals(200, r.code)
      val o = Json.parseToJsonElement(r.body.string()).jsonObject
      val content = o["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
      assertTrue("empty reply", content.isNotBlank())
    }

    // The status card counts both replies.
    compose.waitUntil(10_000) { compose.onAllNodesWithText("2 replies served").fetchSemanticsNodes().isNotEmpty() }
    compose.onAllNodesWithText("2 replies served").onFirst().performScrollTo()
    screenshot("e2e_6_api_server")

    // Off again: the port closes.
    serverSwitch().performScrollTo().performClick()
    compose.waitUntil(20_000) { c.apiServer.status.value is ApiServerStatus.Stopped && !c.apiServer.isRunning }
    // A raw connect: an HTTP client may report a closed listener as a reset instead.
    try {
      java.net.Socket("127.0.0.1", port).close()
      fail("server still listening after it was switched off")
    } catch (_: ConnectException) {}
    // ...and its ongoing notification is gone too.
    val nm = app.getSystemService(NotificationManager::class.java)
    compose.waitUntil(5_000) { nm.activeNotifications.none { it.id == ApiServerService.NOTIFICATION_ID } }
  }

  companion object {
    const val PORT = 18080
    const val MODEL_FILE = "stories-api-e2e"
  }
}
