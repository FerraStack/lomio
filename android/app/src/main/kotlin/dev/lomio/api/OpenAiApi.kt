package dev.lomio.api

import dev.lomio.llama.ChatMessage
import dev.lomio.llama.GenerationStats
import dev.lomio.llama.SamplingParams
import dev.lomio.llama.StopReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** An error with an HTTP status, rendered in OpenAI's `{"error": {...}}` shape. */
class ApiError(val status: Int, override val message: String, val type: String = "invalid_request_error", val code: String? = null) :
  Exception(message)

/** Sampling fields a request may set; null = keep Lomio's setting. */
data class SamplingOverrides(
  val temperature: Float? = null,
  val topP: Float? = null,
  val topK: Int? = null,
  val minP: Float? = null,
  val repeatPenalty: Float? = null,
  val presencePenalty: Float? = null,
  val frequencyPenalty: Float? = null,
  val maxTokens: Int? = null,
  val seed: Long? = null,
  val stop: List<String>? = null,
) {
  fun applyTo(base: SamplingParams): SamplingParams =
    base.copy(
      temperature = temperature ?: base.temperature,
      topP = topP ?: base.topP,
      topK = topK ?: base.topK,
      minP = minP ?: base.minP,
      repeatPenalty = repeatPenalty ?: base.repeatPenalty,
      presencePenalty = presencePenalty ?: base.presencePenalty,
      frequencyPenalty = frequencyPenalty ?: base.frequencyPenalty,
      maxTokens = maxTokens ?: base.maxTokens,
      seed = seed ?: base.seed,
      stop = stop ?: base.stop,
    )
}

data class ChatCompletionRequest(
  val model: String?,
  val messages: List<ChatMessage>,
  val sampling: SamplingOverrides = SamplingOverrides(),
  val stream: Boolean = false,
  /** `stream_options.include_usage`: send a final chunk with token counts. */
  val includeUsage: Boolean = false,
  /** `chat_template_kwargs.enable_thinking` (llama.cpp server's switch); default on. */
  val enableThinking: Boolean = true,
)

data class ApiModel(val id: String, val created: Long, val ownedBy: String = "lomio")

/**
 * The OpenAI Chat Completions wire format, as far as a local single-model server needs it.
 * Pure functions so the parsing and the JSON shapes are unit-tested without a socket.
 */
object OpenAiApi {
  private val json = Json { ignoreUnknownKeys = true }

  private val ROLES = setOf("system", "user", "assistant")

  fun parseChatRequest(body: String): ChatCompletionRequest {
    val root =
      runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        ?: throw ApiError(400, "Request body must be a JSON object")
    val rawMessages = root["messages"] as? JsonArray ?: throw ApiError(400, "'messages' is required and must be an array")
    if (rawMessages.isEmpty()) throw ApiError(400, "'messages' must not be empty")
    val messages =
      rawMessages.mapIndexed { i, el ->
        val m = el as? JsonObject ?: throw ApiError(400, "messages[$i] must be an object")
        val r = m.string("role")
        val role =
          when {
            r == "developer" -> "system" // newer OpenAI name for the system role
            r != null && r in ROLES -> r
            r == "tool" || r == "function" -> throw ApiError(400, "messages[$i]: tool calls are not supported")
            else -> throw ApiError(400, "messages[$i].role must be one of system, user, assistant")
          }
        ChatMessage(role, contentText(m["content"], i), reasoning = m.string("reasoning_content"))
      }
    val stop =
      when (val s = root["stop"]) {
        null, JsonNull -> null
        is JsonPrimitive -> listOfNotNull(s.contentOrNull)
        is JsonArray -> s.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> throw ApiError(400, "'stop' must be a string or an array of strings")
      }
    val maxTokens = root.int("max_completion_tokens") ?: root.int("max_tokens")
    if (maxTokens != null && maxTokens <= 0) throw ApiError(400, "'max_tokens' must be positive")
    if ((root.int("n") ?: 1) != 1) throw ApiError(400, "Only n = 1 is supported")
    return ChatCompletionRequest(
      model = root.string("model"),
      messages = messages,
      sampling =
        SamplingOverrides(
          temperature = root.float("temperature"),
          topP = root.float("top_p"),
          topK = root.int("top_k"),
          minP = root.float("min_p"),
          repeatPenalty = root.float("repeat_penalty"),
          presencePenalty = root.float("presence_penalty"),
          frequencyPenalty = root.float("frequency_penalty"),
          maxTokens = maxTokens,
          seed = (root["seed"] as? JsonPrimitive)?.longOrNull,
          stop = stop,
        ),
      stream = (root["stream"] as? JsonPrimitive)?.booleanOrNull ?: false,
      includeUsage = ((root["stream_options"] as? JsonObject)?.get("include_usage") as? JsonPrimitive)?.booleanOrNull ?: false,
      enableThinking =
        ((root["chat_template_kwargs"] as? JsonObject)?.get("enable_thinking") as? JsonPrimitive)?.booleanOrNull ?: true,
    )
  }

  /** `content` is a string, or an array of parts of which only text parts are supported. */
  private fun contentText(el: JsonElement?, i: Int): String =
    when (el) {
      null, JsonNull -> ""
      is JsonPrimitive -> el.contentOrNull ?: ""
      is JsonArray ->
        el.joinToString("\n") { part ->
          val p = part as? JsonObject ?: throw ApiError(400, "messages[$i].content parts must be objects")
          when (p.string("type")) {
            "text" -> p.string("text").orEmpty()
            else -> throw ApiError(400, "messages[$i]: only text content is supported")
          }
        }
      else -> throw ApiError(400, "messages[$i].content must be a string or an array")
    }

  fun finishReason(stop: StopReason): String =
    when (stop) {
      StopReason.MAX_TOKENS, StopReason.CONTEXT_FULL -> "length"
      else -> "stop"
    }

  fun modelsJson(models: List<ApiModel>): String =
    buildJsonObject {
        put("object", "list")
        putJsonArray("data") { models.forEach { add(modelJson(it)) } }
      }
      .toString()

  fun modelJson(m: ApiModel): JsonObject = buildJsonObject {
    put("id", m.id)
    put("object", "model")
    put("created", m.created)
    put("owned_by", m.ownedBy)
  }

  fun completionJson(id: String, created: Long, model: String, content: String, reasoning: String, stats: GenerationStats): String =
    buildJsonObject {
        put("id", id)
        put("object", "chat.completion")
        put("created", created)
        put("model", model)
        putJsonArray("choices") {
          add(
            buildJsonObject {
              put("index", 0)
              putJsonObject("message") {
                put("role", "assistant")
                put("content", content)
                if (reasoning.isNotEmpty()) put("reasoning_content", reasoning)
              }
              put("finish_reason", finishReason(stats.stopReason))
            }
          )
        }
        put("usage", usageJson(stats))
      }
      .toString()

  /** One streamed chunk. [finishReason] null = more to come. */
  fun chunkJson(
    id: String,
    created: Long,
    model: String,
    role: Boolean = false,
    content: String? = null,
    reasoning: String? = null,
    finishReason: String? = null,
  ): String =
    buildJsonObject {
        put("id", id)
        put("object", "chat.completion.chunk")
        put("created", created)
        put("model", model)
        putJsonArray("choices") {
          add(
            buildJsonObject {
              put("index", 0)
              putJsonObject("delta") {
                if (role) put("role", "assistant")
                if (content != null) put("content", content)
                if (reasoning != null) put("reasoning_content", reasoning)
              }
              put("finish_reason", finishReason?.let(::JsonPrimitive) ?: JsonNull)
            }
          )
        }
      }
      .toString()

  /** The extra chunk `stream_options.include_usage` asks for: empty choices, usage set. */
  fun usageChunkJson(id: String, created: Long, model: String, stats: GenerationStats): String =
    buildJsonObject {
        put("id", id)
        put("object", "chat.completion.chunk")
        put("created", created)
        put("model", model)
        put("choices", buildJsonArray {})
        put("usage", usageJson(stats))
      }
      .toString()

  private fun usageJson(s: GenerationStats) = buildJsonObject {
    put("prompt_tokens", s.promptTokens)
    put("completion_tokens", s.generatedTokens)
    put("total_tokens", s.promptTokens + s.generatedTokens)
  }

  fun errorJson(e: ApiError): String =
    buildJsonObject {
        putJsonObject("error") {
          put("message", e.message)
          put("type", e.type)
          put("code", e.code?.let(::JsonPrimitive) ?: JsonNull)
        }
      }
      .toString()

  /**
   * What to send for a new snapshot when [sent] has already gone out. The engine's parser may
   * rewrite earlier text (e.g. when a `<think>` block closes); a stream can't take text back, so
   * only the part after the common prefix is sent.
   */
  fun delta(sent: String, snapshot: String): String {
    if (snapshot.startsWith(sent)) return snapshot.substring(sent.length)
    return snapshot.substring(sent.commonPrefixWith(snapshot).length.coerceAtMost(snapshot.length))
  }

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

  private fun JsonObject.float(key: String): Float? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.doubleOrNull?.toFloat() ?: throw ApiError(400, "'$key' must be a number")
  }

  private fun JsonObject.int(key: String): Int? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.intOrNull ?: p.doubleOrNull?.takeIf { it % 1.0 == 0.0 }?.toInt() ?: throw ApiError(400, "'$key' must be an integer")
  }

}
