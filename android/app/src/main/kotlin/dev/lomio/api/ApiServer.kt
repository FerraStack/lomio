package dev.lomio.api

import dev.lomio.llama.GenerationEvent
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the server needs from the app: the loaded model and a way to run a chat on it. */
interface ApiBackend {
  /** The model requests will run on, or null when none is loaded. */
  suspend fun model(): ApiModel?

  /**
   * Starts a generation for [request]. Throws [ApiError] (503) when no model is loaded or the
   * engine is busy with the app's own chat. Cancelling the collector stops the generation.
   */
  suspend fun chat(request: ChatCompletionRequest): Flow<GenerationEvent>
}

/**
 * @param lan false = bind 127.0.0.1 only (apps on this phone); true = all interfaces, and every
 *   request must carry `Authorization: Bearer <token>`.
 */
data class ApiServerConfig(val port: Int, val lan: Boolean, val token: String?) {
  init {
    require(!lan || !token.isNullOrBlank()) { "LAN mode needs an API key" }
  }
}

data class ApiServerStats(
  /** Chat completions answered since the server started. */
  val served: Int = 0,
  /** Requests generating or waiting for their turn. */
  val active: Int = 0,
)

/**
 * A small OpenAI-compatible HTTP server: `GET /v1/models`, `POST /v1/chat/completions` (JSON or
 * SSE streaming). One generation runs at a time; further requests wait their turn. A client
 * that disconnects cancels its generation.
 */
class ApiServer(private val backend: ApiBackend, private val clock: () -> Long = System::currentTimeMillis) {
  private var server: ServerSocket? = null
  private var scope: CoroutineScope? = null
  private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
  private val turn = Mutex()
  private val random = SecureRandom()

  private val _stats = MutableStateFlow(ApiServerStats())
  val stats: StateFlow<ApiServerStats> = _stats.asStateFlow()

  val isRunning: Boolean
    @Synchronized get() = server != null

  /** Binds and starts accepting. Returns the bound port (useful with port 0 in tests). */
  @Synchronized
  fun start(config: ApiServerConfig): Int {
    check(server == null) { "already running" }
    // Not InetAddress.getLoopbackAddress(): on Android that is ::1, and clients (and our docs)
    // use http://127.0.0.1.
    val address = InetAddress.getByName(if (config.lan) "0.0.0.0" else "127.0.0.1")
    val ss = ServerSocket()
    try {
      ss.reuseAddress = true
      ss.bind(InetSocketAddress(address, config.port), 32)
    } catch (e: IOException) {
      ss.close()
      throw e
    }
    val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    server = ss
    scope = s
    _stats.value = ApiServerStats()
    s.launch {
      while (isActive) {
        val client =
          try {
            ss.accept()
          } catch (_: IOException) {
            break // closed by stop()
          }
        clients += client
        launch { handle(client, config, s) }
      }
    }
    return ss.localPort
  }

  @Synchronized
  fun stop() {
    server?.close()
    server = null
    scope?.cancel()
    scope = null
    // Unblock handlers stuck in socket reads or writes.
    synchronized(clients) {
      clients.forEach { runCatching { it.close() } }
      clients.clear()
    }
  }

  private suspend fun handle(client: Socket, config: ApiServerConfig, serverScope: CoroutineScope) {
    try {
      client.soTimeout = REQUEST_READ_TIMEOUT_MS
      val input = BufferedInputStream(client.getInputStream())
      val res = HttpResponder(BufferedOutputStream(client.getOutputStream()))
      val req =
        try {
          Http.readRequest(input) ?: return
        } catch (e: ApiError) {
          res.error(e)
          return
        }
      try {
        route(req, res, client, input, config, serverScope)
      } catch (e: ApiError) {
        if (!res.committed) res.error(e) else runCatching { res.event(OpenAiApi.errorJson(e)) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: IOException) {
        // The client went away.
      } catch (e: Throwable) {
        val err = ApiError(500, e.message ?: "Internal error", "server_error")
        if (!res.committed) res.error(err) else runCatching { res.event(OpenAiApi.errorJson(err)) }
      }
    } catch (_: IOException) {
    } finally {
      clients -= client
      runCatching { client.close() }
    }
  }

  private suspend fun route(
    req: HttpRequest,
    res: HttpResponder,
    client: Socket,
    input: InputStream,
    config: ApiServerConfig,
    serverScope: CoroutineScope,
  ) {
    authorize(req, config)
    val path = req.path.trimEnd('/')
    when {
      path == "" || path == "/health" || path == "/v1" -> {
        requireMethod(req, "GET")
        res.json(200, """{"status":"ok"}""")
      }
      path == "/v1/models" -> {
        requireMethod(req, "GET")
        res.json(200, OpenAiApi.modelsJson(listOfNotNull(backend.model())))
      }
      path.startsWith("/v1/models/") -> {
        requireMethod(req, "GET")
        val id = path.removePrefix("/v1/models/")
        val m = backend.model()?.takeIf { it.id == id } ?: throw ApiError(404, "Model '$id' is not loaded", code = "model_not_found")
        res.json(200, OpenAiApi.modelJson(m).toString())
      }
      path == "/v1/chat/completions" -> {
        requireMethod(req, "POST")
        if (req.header("content-type")?.contains("application/json", ignoreCase = true) != true) {
          // Also keeps web pages out: a cross-site JSON POST needs a CORS preflight we never allow.
          throw ApiError(415, "Send the body as JSON with 'Content-Type: application/json'")
        }
        val request = OpenAiApi.parseChatRequest(req.body.decodeToString())
        chat(request, res, client, input, serverScope)
      }
      else -> throw ApiError(404, "Unknown endpoint ${req.method} ${req.path}", code = "unknown_url")
    }
  }

  private fun requireMethod(req: HttpRequest, method: String) {
    if (req.method != method) throw ApiError(405, "Use $method for ${req.path}")
  }

  private fun authorize(req: HttpRequest, config: ApiServerConfig) {
    if (config.lan) {
      val given = req.header("authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
      val ok = MessageDigest.isEqual(given.toByteArray(), config.token.orEmpty().toByteArray())
      if (!ok) throw ApiError(401, "Missing or wrong API key. Send 'Authorization: Bearer <key>' with the key shown in Lomio's settings.", code = "invalid_api_key")
    } else {
      // Loopback only: refuse other Host names so a web page can't reach us by DNS rebinding.
      val host = req.header("host") ?: return
      val name = if (host.startsWith("[")) host.substringBefore(']') + "]" else host.substringBefore(':')
      if (name.lowercase() !in LOOPBACK_HOSTS) throw ApiError(403, "Host '$host' is not allowed", code = "forbidden_host")
    }
  }

  private suspend fun chat(
    request: ChatCompletionRequest,
    res: HttpResponder,
    client: Socket,
    input: InputStream,
    serverScope: CoroutineScope,
  ) = coroutineScope {
    _stats.update { it.copy(active = it.active + 1) }
    try {
      val work = launch { turn.withLock { generate(request, res) } }
      // The client sends nothing after its request; EOF means it hung up, so stop generating.
      // Not a child of this scope: it stays blocked in read() until handle() closes the socket.
      serverScope.launch {
        try {
          client.soTimeout = 0
          if (input.read() == -1) work.cancel()
        } catch (_: IOException) {}
      }
      work.join()
    } finally {
      _stats.update { it.copy(active = it.active - 1) }
    }
  }

  private suspend fun generate(request: ChatCompletionRequest, res: HttpResponder) {
    val model = backend.model() ?: throw ApiError(503, NO_MODEL, "server_error", "model_not_loaded")
    val flow = backend.chat(request)
    val id = "chatcmpl-" + randomId()
    val created = clock() / 1000
    if (!request.stream) {
      var done: GenerationEvent.Done? = null
      flow.collect { if (it is GenerationEvent.Done) done = it }
      val d = done ?: throw ApiError(500, "The generation ended without a result", "server_error")
      // Counted before the reply goes out, so a client that sees the reply also sees the count.
      _stats.update { it.copy(served = it.served + 1) }
      res.json(200, OpenAiApi.completionJson(id, created, model.id, d.content, d.reasoning, d.stats))
      return
    }
    res.startEventStream()
    res.event(OpenAiApi.chunkJson(id, created, model.id, role = true, content = ""))
    var sentContent = ""
    var sentReasoning = ""
    fun send(content: String, reasoning: String) {
      val r = OpenAiApi.delta(sentReasoning, reasoning)
      if (r.isNotEmpty()) res.event(OpenAiApi.chunkJson(id, created, model.id, reasoning = r))
      sentReasoning = reasoning
      val c = OpenAiApi.delta(sentContent, content)
      if (c.isNotEmpty()) res.event(OpenAiApi.chunkJson(id, created, model.id, content = c))
      sentContent = content
    }
    flow.collect { ev ->
      when (ev) {
        is GenerationEvent.PromptProgress -> res.comment("prompt ${ev.done}/${ev.total}")
        is GenerationEvent.Update -> send(ev.content, ev.reasoning)
        is GenerationEvent.Done -> {
          send(ev.content, ev.reasoning)
          res.event(OpenAiApi.chunkJson(id, created, model.id, finishReason = OpenAiApi.finishReason(ev.stats.stopReason)))
          if (request.includeUsage) res.event(OpenAiApi.usageChunkJson(id, created, model.id, ev.stats))
          _stats.update { it.copy(served = it.served + 1) }
          res.event("[DONE]")
        }
      }
    }
  }

  private fun randomId(): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return String(CharArray(24) { alphabet[random.nextInt(alphabet.length)] })
  }

  companion object {
    const val DEFAULT_PORT = 8080
    const val REQUEST_READ_TIMEOUT_MS = 30_000
    const val NO_MODEL = "No model is loaded. Open Lomio and load a model, then try again."
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")

    /** A fresh API key: "lomio-" and 32 random letters and digits (~190 bits). */
    fun newApiKey(random: SecureRandom = SecureRandom()): String {
      val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
      return "lomio-" + String(CharArray(32) { alphabet[random.nextInt(alphabet.length)] })
    }
  }
}
