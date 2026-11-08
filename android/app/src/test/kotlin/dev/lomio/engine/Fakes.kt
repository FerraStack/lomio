package dev.lomio.engine

import dev.lomio.llama.BenchResult
import dev.lomio.llama.ChatMessage
import dev.lomio.llama.EngineState
import dev.lomio.llama.GenerationEvent
import dev.lomio.llama.GenerationStats
import dev.lomio.llama.InferenceEngine
import dev.lomio.llama.LoadParams
import dev.lomio.llama.ModelInfo
import dev.lomio.llama.SamplingParams
import dev.lomio.llama.StopReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/** Scriptable [InferenceEngine] for JVM tests. */
class FakeEngine(var nCtx: Int = 4096) : InferenceEngine {
  override val state = MutableStateFlow<EngineState>(EngineState.Idle)
  var chatCalls = 0
  var loads = mutableListOf<String>()
  var lastMessages: List<ChatMessage> = emptyList()
  var gate: CompletableDeferred<Unit>? = null
  var stopReason = StopReason.EOS
  var failWith: Throwable? = null
  var loadFailure: Throwable? = null
  var lastLoadParams: LoadParams? = null

  private fun info(vision: Boolean = false) =
    ModelInfo("fake 1B", "llama", 1_000_000_000, 600_000_000, 8192, nCtx, 16, null, false, supportsVision = vision)

  override suspend fun load(path: String, params: LoadParams): ModelInfo {
    loadFailure?.let { throw it }
    loads += path
    lastLoadParams = params
    if (params.mmprojPath != null) {
      nCtx = params.nCtx
      return info(vision = true).also { state.value = EngineState.Loaded(path, it) }
    }
    nCtx = params.nCtx
    return info().also { state.value = EngineState.Loaded(path, it) }
  }

  override suspend fun unload() {
    state.value = EngineState.Idle
  }

  override fun chat(messages: List<ChatMessage>, sampling: SamplingParams, enableThinking: Boolean): Flow<GenerationEvent> =
    flow {
      chatCalls++
      lastMessages = messages
      emit(GenerationEvent.Update("Hel", ""))
      gate?.await()
      failWith?.let { throw it }
      emit(GenerationEvent.Done("Hello", "", GenerationStats(10, 0, 2, 50, 100.0, 20.0, stopReason)))
    }

  override fun stop() {}

  override suspend fun bench(pp: Int, tg: Int, repetitions: Int): BenchResult = error("not used")
}

class FakeMemory(var available: Long, var total: Long) : DeviceMemoryProvider {
  override fun current() = DeviceMemory(available, total)
}
