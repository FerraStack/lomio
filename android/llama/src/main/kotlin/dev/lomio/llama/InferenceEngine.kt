package dev.lomio.llama

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface EngineState {
  data object Idle : EngineState

  data class Loading(val path: String) : EngineState

  data class Loaded(val path: String, val info: ModelInfo) : EngineState

  data class Generating(val path: String, val info: ModelInfo) : EngineState

  data class Failed(val path: String, val message: String) : EngineState
}

/**
 * The app talks to inference only through this interface so it can be faked in tests.
 *
 * All native work runs on one dedicated thread inside the implementation; callers may use
 * any dispatcher.
 */
interface InferenceEngine {
  val state: StateFlow<EngineState>

  /** Loads a GGUF model, unloading the previous one first. */
  suspend fun load(path: String, params: LoadParams = LoadParams()): ModelInfo

  suspend fun unload()

  /**
   * Formats [messages] with the model's own chat template (llama.cpp `common_chat`), runs
   * generation and emits parsed snapshots. Reuses the KV cache for the longest common token
   * prefix with the previous call, so switching conversations never leaks state.
   * Cancelling the collector, or calling [stop], ends generation promptly.
   */
  fun chat(
    messages: List<ChatMessage>,
    sampling: SamplingParams = SamplingParams(),
    enableThinking: Boolean = true,
  ): Flow<GenerationEvent>

  /** Requests the running generation to stop; thread-safe, returns immediately. */
  fun stop()

  /** llama-bench style pp/tg measurement on the loaded model. */
  suspend fun bench(pp: Int = 512, tg: Int = 128, repetitions: Int = 3): BenchResult
}
