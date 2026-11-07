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
) {
  fun overlay(other: LoadSettings?): LoadSettings {
    if (other == null) return this
    return LoadSettings(
      nCtx = other.nCtx ?: nCtx,
      nThreads = other.nThreads ?: nThreads,
      nBatch = other.nBatch ?: nBatch,
      useMmap = other.useMmap ?: useMmap,
    )
  }

  fun toLoadParams(): LoadParams {
    val d = LoadParams()
    return LoadParams(
      nCtx = nCtx ?: d.nCtx,
      nThreads = nThreads ?: d.nThreads,
      nBatch = nBatch ?: d.nBatch,
      useMmap = useMmap ?: d.useMmap,
    )
  }
}

/**
 * Resolves the effective settings for a generation: chat override → model settings → global
 * defaults (RESEARCH.md §8.2). Load settings are per-model only because they need a reload.
 */
object SettingsResolver {
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
