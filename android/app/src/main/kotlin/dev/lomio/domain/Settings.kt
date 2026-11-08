package dev.lomio.domain

import dev.lomio.llama.LoadParams
import dev.lomio.llama.SamplingParams
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Sampling settings with every field optional so layers can override each other. */
@Serializable
data class GenerationSettings(
  val temperature: Float? = null,
  val topK: Int? = null,
  val topP: Float? = null,
  val minP: Float? = null,
  val repeatPenalty: Float? = null,
  val repeatLastN: Int? = null,
  val maxTokens: Int? = null,
  val seed: Long? = null,
  val stop: List<String>? = null,
) {
  /** Values set in [other] win over values in this. */
  fun overlay(other: GenerationSettings?): GenerationSettings {
    if (other == null) return this
    return GenerationSettings(
      temperature = other.temperature ?: temperature,
      topK = other.topK ?: topK,
      topP = other.topP ?: topP,
      minP = other.minP ?: minP,
      repeatPenalty = other.repeatPenalty ?: repeatPenalty,
      repeatLastN = other.repeatLastN ?: repeatLastN,
      maxTokens = other.maxTokens ?: maxTokens,
      seed = other.seed ?: seed,
      stop = other.stop ?: stop,
    )
  }

  fun toSamplingParams(): SamplingParams {
    val d = SamplingParams()
    return SamplingParams(
      temperature = temperature ?: d.temperature,
      topK = topK ?: d.topK,
      topP = topP ?: d.topP,
      minP = minP ?: d.minP,
      repeatPenalty = repeatPenalty ?: d.repeatPenalty,
      repeatLastN = repeatLastN ?: d.repeatLastN,
      maxTokens = maxTokens ?: d.maxTokens,
      seed = seed ?: d.seed,
      stop = stop ?: d.stop,
    )
  }

  companion object {
    /** PocketPal's defaults (RESEARCH.md §2.1). */
    val DEFAULTS =
      GenerationSettings(
        temperature = 0.7f,
        topK = 40,
        topP = 0.95f,
        minP = 0.05f,
        repeatPenalty = 1.0f,
        repeatLastN = 64,
        maxTokens = -1,
        seed = -1,
        stop = emptyList(),
      )
  }
}

@Serializable
data class LoadSettings(
  val nCtx: Int? = null,
  val nThreads: Int? = null,
  val nBatch: Int? = null,
  val useMmap: Boolean? = null,
  /** Jinja chat template overriding the GGUF's; only set per model. Null = the GGUF's. */
  val chatTemplate: String? = null,
) {
  fun overlay(other: LoadSettings?): LoadSettings {
    if (other == null) return this
    return LoadSettings(
      nCtx = other.nCtx ?: nCtx,
      nThreads = other.nThreads ?: nThreads,
      nBatch = other.nBatch ?: nBatch,
      useMmap = other.useMmap ?: useMmap,
      chatTemplate = other.chatTemplate ?: chatTemplate,
    )
  }

  fun toLoadParams(): LoadParams {
    val d = LoadParams()
    return LoadParams(
      nCtx = nCtx ?: d.nCtx,
      nThreads = nThreads ?: d.nThreads,
      nBatch = nBatch ?: d.nBatch,
      useMmap = useMmap ?: d.useMmap,
      chatTemplate = chatTemplate?.takeIf { it.isNotBlank() },
    )
  }
}

/** The layer a resolved setting comes from; the most specific layer that sets it wins. */
enum class SettingSource { APP_DEFAULT, GLOBAL, MODEL, CHAT }

/**
 * Resolves the effective settings for a generation: chat override → model settings → global
 * defaults (RESEARCH.md §8.2). Load settings are per-model only because they need a reload.
 */
object SettingsResolver {
  /** Which layer supplies a value, given each layer's own (nullable) value. */
  fun sourceOf(global: Any?, model: Any? = null, chat: Any? = null): SettingSource =
    when {
      chat != null -> SettingSource.CHAT
      model != null -> SettingSource.MODEL
      global != null -> SettingSource.GLOBAL
      else -> SettingSource.APP_DEFAULT
    }

  /** What a model inherits before its own settings: app defaults overlaid with global ones. */
  fun modelBase(global: GenerationSettings): GenerationSettings = GenerationSettings.DEFAULTS.overlay(global)

  /** What a chat inherits before its own overrides: global, then the model's settings. */
  fun chatBase(global: GenerationSettings, model: GenerationSettings?): GenerationSettings =
    modelBase(global).overlay(model)

  fun generation(
    global: GenerationSettings,
    model: GenerationSettings?,
    chat: GenerationSettings?,
  ): GenerationSettings = GenerationSettings.DEFAULTS.overlay(global).overlay(model).overlay(chat)

  fun systemPrompt(global: String, model: String?, chat: String?): String =
    chat?.takeIf { it.isNotBlank() } ?: model?.takeIf { it.isNotBlank() } ?: global

  fun load(global: LoadSettings, model: LoadSettings?): LoadSettings = global.overlay(model)
}

object SettingsJson {
  val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
  }

  fun encodeGen(s: GenerationSettings?): String? = s?.let { json.encodeToString(it) }

  fun decodeGen(s: String?): GenerationSettings? =
    s?.let { runCatching { json.decodeFromString<GenerationSettings>(it) }.getOrNull() }

  fun encodeLoad(s: LoadSettings?): String? = s?.let { json.encodeToString(it) }

  fun decodeLoad(s: String?): LoadSettings? =
    s?.let { runCatching { json.decodeFromString<LoadSettings>(it) }.getOrNull() }
}
