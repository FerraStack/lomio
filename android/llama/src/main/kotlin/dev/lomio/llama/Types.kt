package dev.lomio.llama

/** Parameters that require a model reload when changed. */
data class LoadParams(
  val nCtx: Int = 4096,
  /** 0 = pick automatically from the number of online cores. */
  val nThreads: Int = 0,
  val nBatch: Int = 512,
  /** CPU-only for now; kept so a GPU backend can be added later. */
  val nGpuLayers: Int = 0,
  val useMmap: Boolean = true,
  /** Jinja chat template to use instead of the GGUF's own; null = the GGUF's. */
  val chatTemplate: String? = null,
)

/** Sampler settings for one generation. Defaults follow PocketPal / llama.cpp conventions. */
data class SamplingParams(
  val temperature: Float = 0.7f,
  val topK: Int = 40,
  val topP: Float = 0.95f,
  val minP: Float = 0.05f,
  /** 1.0 = disabled. */
  val repeatPenalty: Float = 1.0f,
  val repeatLastN: Int = 64,
  val presencePenalty: Float = 0f,
  val frequencyPenalty: Float = 0f,
  /** -1 = until EOS / context full. */
  val maxTokens: Int = -1,
  /** -1 = random seed. */
  val seed: Long = -1,
  /** Extra stop strings on top of the ones the chat template defines. */
  val stop: List<String> = emptyList(),
)

data class ChatMessage(
  /** "system" | "user" | "assistant" */
  val role: String,
  val content: String,
  /** Reasoning of earlier assistant turns; passed to templates that support it. */
  val reasoning: String? = null,
)

/** Metadata of a loaded model (from llama.cpp after load). */
data class ModelInfo(
  val description: String,
  val architecture: String?,
  val nParams: Long,
  val sizeBytes: Long,
  val nCtxTrain: Int,
  val nCtx: Int,
  val nLayer: Int,
  val chatTemplate: String?,
  /** True if the template supports an enable_thinking switch. */
  val supportsThinking: Boolean,
)

/** Header metadata read straight from a GGUF file without loading weights. */
data class GgufMetadata(
  val architecture: String?,
  val name: String?,
  val sizeLabel: String?,
  /** llama.cpp `general.file_type`, e.g. 2 = Q4_0, 15 = Q4_K_M. */
  val fileType: Int?,
  val nCtxTrain: Int?,
  val nLayer: Int?,
  val nEmbd: Int?,
  val nHead: Int?,
  val nHeadKv: Int?,
  /** Per-head key / value width when the model declares it (otherwise nEmbd / nHead). */
  val keyLength: Int?,
  val valueLength: Int?,
  val chatTemplate: String?,
  val nTensors: Long,
  /** Sum of all tensor element counts (≈ parameter count). */
  val nParams: Long,
)

enum class StopReason { EOS, STOP_STRING, MAX_TOKENS, CONTEXT_FULL, CANCELLED, ERROR }

data class GenerationStats(
  val promptTokens: Int,
  /** Prompt tokens reused from the KV cache (prefix match with the previous turn). */
  val cachedTokens: Int,
  val generatedTokens: Int,
  val timeToFirstTokenMs: Long,
  val promptTokensPerSec: Double,
  val genTokensPerSec: Double,
  val stopReason: StopReason,
)

sealed interface GenerationEvent {
  /**
   * Prompt processing progress: [done] of [total] prompt tokens are in the KV cache (the reused
   * prefix counts as done). Sent before the first batch and after each one.
   */
  data class PromptProgress(val done: Int, val total: Int) : GenerationEvent

  /**
   * Full parsed snapshot of the assistant reply so far (not a delta): the template-aware
   * parser may rewrite earlier text, e.g. when a `<think>` tag closes.
   */
  data class Update(val content: String, val reasoning: String) : GenerationEvent

  data class Done(val content: String, val reasoning: String, val stats: GenerationStats) :
    GenerationEvent
}

data class BenchResult(
  val pp: Int,
  val tg: Int,
  val repetitions: Int,
  val ppTokensPerSec: Double,
  val ppStdDev: Double,
  val tgTokensPerSec: Double,
  val tgStdDev: Double,
  val modelDescription: String,
  val backend: String,
)

class LlamaException(message: String) : RuntimeException(message)
