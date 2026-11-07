package dev.lomio.llama

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * llama.cpp-backed [InferenceEngine]. One instance per process; all native calls run on a
 * single dedicated thread because llama.cpp contexts are not thread-safe.
 *
 * @param nativeLibDir `context.applicationInfo.nativeLibraryDir`; the ggml CPU backend
 *   variants (`libggml-cpu-android_*.so`) are loaded from there.
 */
class LlamaEngine(private val nativeLibDir: String) : InferenceEngine {
  private val dispatcher: CoroutineDispatcher =
    Executors.newSingleThreadExecutor { r -> Thread(r, "lomio-llama").apply { isDaemon = true } }
      .asCoroutineDispatcher()

  private val _state = MutableStateFlow<EngineState>(EngineState.Idle)

  /** Every chat/bench call gets an id; stop() cancels up to the latest one (see lomio_jni.cpp). */
  private val opIds = AtomicLong(0)
  override val state: StateFlow<EngineState> = _state.asStateFlow()

  // Only touched on [dispatcher].
  private var handle: Long = 0L
  private var loadedPath: String? = null
  private var loadedInfo: ModelInfo? = null

  override suspend fun load(path: String, params: LoadParams): ModelInfo =
    withContext(dispatcher) {
      NativeBridge.ensureInitialized(nativeLibDir)
      freeCurrent()
      _state.value = EngineState.Loading(path)
      try {
        handle =
          NativeBridge.nativeLoad(
            path.toByteArray(),
            params.nCtx,
            params.nThreads,
            params.nBatch,
            params.nGpuLayers,
            params.useMmap,
          )
        val info = readInfo(handle)
        loadedPath = path
        loadedInfo = info
        _state.value = EngineState.Loaded(path, info)
        info
      } catch (e: Throwable) {
        handle = 0L
        _state.value = EngineState.Failed(path, e.message ?: e.toString())
        throw e
      }
    }

  override suspend fun unload() {
    NativeBridge.nativeStopIfLoaded(opIds.get())
    withContext(dispatcher) {
      freeCurrent()
      _state.value = EngineState.Idle
    }
  }

  override fun chat(
    messages: List<ChatMessage>,
    sampling: SamplingParams,
    enableThinking: Boolean,
  ): Flow<GenerationEvent> =
    callbackFlow {
        val opId = opIds.incrementAndGet()
        val job =
          launch(dispatcher) {
            val h = handle
            val path = loadedPath
            val info = loadedInfo
            if (h == 0L || path == null || info == null) {
              close(LlamaException("no model loaded"))
              return@launch
            }
            _state.value = EngineState.Generating(path, info)
            var last = GenerationEvent.Update("", "")
            val callback =
              object : NativeBridge.UpdateCallback {
                override fun onUpdate(content: ByteArray, reasoning: ByteArray) {
                  last = GenerationEvent.Update(content.decodeToString(), reasoning.decodeToString())
                  trySend(last)
                }
              }
            val stats = DoubleArray(7)
            try {
              ensureActive()
              NativeBridge.nativeChat(
                h,
                messages.map { it.role.toByteArray() }.toTypedArray(),
                messages.map { it.content.toByteArray() }.toTypedArray(),
                messages.map { it.reasoning?.toByteArray() }.toTypedArray(),
                enableThinking,
                floatArrayOf(
                  sampling.temperature,
                  sampling.topP,
                  sampling.minP,
                  sampling.repeatPenalty,
                  sampling.presencePenalty,
                  sampling.frequencyPenalty,
                ),
                intArrayOf(sampling.topK, sampling.repeatLastN, sampling.maxTokens),
                sampling.seed,
                sampling.stop.filter { it.isNotEmpty() }.map { it.toByteArray() }.toTypedArray(),
                callback,
                stats,
                opId,
              )
              trySend(GenerationEvent.Done(last.content, last.reasoning, stats.toStats()))
              close()
            } catch (e: Throwable) {
              close(e)
            } finally {
              _state.value = EngineState.Loaded(path, info)
            }
          }
        awaitClose {
          // Collector cancelled (or finished): make sure native generation returns.
          if (job.isActive) NativeBridge.nativeStop(opId)
        }
      }
      // Snapshots carry full state, so a slow collector only ever needs the latest one.
      .conflate()

  override fun stop() {
    NativeBridge.nativeStopIfLoaded(opIds.get())
  }

  override suspend fun bench(pp: Int, tg: Int, repetitions: Int): BenchResult =
    withContext(dispatcher) {
      val h = handle
      val info = loadedInfo ?: throw LlamaException("no model loaded")
      val out = DoubleArray(4)
      val backend = NativeBridge.nativeBench(h, pp, tg, repetitions, out, opIds.incrementAndGet()).decodeToString()
      BenchResult(
        pp = pp,
        tg = tg,
        repetitions = repetitions,
        ppTokensPerSec = out[0],
        ppStdDev = out[1],
        tgTokensPerSec = out[2],
        tgStdDev = out[3],
        modelDescription = info.description,
        backend = backend,
      )
    }

  /** llama.cpp's system info line (selected CPU variant, SIMD features). */
  suspend fun systemInfo(): String =
    withContext(dispatcher) {
      NativeBridge.ensureInitialized(nativeLibDir)
      NativeBridge.nativeSystemInfo().decodeToString()
    }

  private fun freeCurrent() {
    if (handle != 0L) {
      NativeBridge.nativeFree(handle)
      handle = 0L
    }
    loadedPath = null
    loadedInfo = null
  }

  private fun readInfo(h: Long): ModelInfo {
    val longs = LongArray(6)
    val strings = arrayOfNulls<ByteArray>(3)
    NativeBridge.nativeModelInfo(h, longs, strings)
    return ModelInfo(
      description = strings[0]?.decodeToString().orEmpty(),
      architecture = strings[1]?.decodeToString(),
      nParams = longs[0],
      sizeBytes = longs[1],
      nCtxTrain = longs[2].toInt(),
      nCtx = longs[3].toInt(),
      nLayer = longs[4].toInt(),
      chatTemplate = strings[2]?.decodeToString(),
      supportsThinking = longs[5] != 0L,
    )
  }
}

private fun NativeBridge.nativeStopIfLoaded(uptoOpId: Long) {
  // nativeStop only updates an atomic, but the library must be loaded to call it.
  try {
    nativeStop(uptoOpId)
  } catch (e: UnsatisfiedLinkError) {
    // Library not loaded yet, so nothing can be running.
  }
}

private fun DoubleArray.toStats() =
  GenerationStats(
    promptTokens = this[0].toInt(),
    cachedTokens = this[1].toInt(),
    generatedTokens = this[2].toInt(),
    timeToFirstTokenMs = this[3].toLong(),
    promptTokensPerSec = this[4],
    genTokensPerSec = this[5],
    stopReason = StopReason.entries.getOrElse(this[6].toInt()) { StopReason.ERROR },
  )

/** Reads GGUF header metadata without loading the weights (cheap; safe on any thread). */
object Gguf {
  /** Returns null if [path] is not a readable GGUF file. */
  fun read(path: String): GgufMetadata? {
    NativeBridge.ensureLoaded()
    val longs = LongArray(10)
    val strings = arrayOfNulls<ByteArray>(4)
    if (!NativeBridge.nativeReadGguf(path.toByteArray(), longs, strings)) return null
    fun int(i: Int): Int? = longs[i].takeIf { it >= 0 }?.toInt()
    return GgufMetadata(
      architecture = strings[0]?.decodeToString(),
      name = strings[1]?.decodeToString(),
      sizeLabel = strings[2]?.decodeToString(),
      fileType = int(0),
      nCtxTrain = int(1),
      nLayer = int(2),
      nEmbd = int(3),
      nHead = int(4),
      nHeadKv = int(5),
      keyLength = int(6),
      valueLength = int(7),
      chatTemplate = strings[3]?.decodeToString(),
      nTensors = longs[8].coerceAtLeast(0),
      nParams = longs[9].coerceAtLeast(0),
    )
  }
}
