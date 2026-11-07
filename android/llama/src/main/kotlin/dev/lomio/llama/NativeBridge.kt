package dev.lomio.llama

/**
 * Raw JNI surface of `liblomio_jni.so` (see src/main/cpp/lomio_jni.cpp).
 *
 * Strings cross the boundary as UTF-8 byte arrays. Every function except [nativeStop] must be
 * called from the single engine thread owned by [LlamaEngine]. Failures surface as
 * [LlamaException].
 */
internal object NativeBridge {
  /** Implemented by Kotlin, invoked from native code during [nativeChat]. */
  interface UpdateCallback {
    fun onUpdate(content: ByteArray, reasoning: ByteArray)
  }

  @Volatile private var libraryLoaded = false
  @Volatile private var backendsInitialized = false

  /** Loads the shared libraries (enough for GGUF header reads). */
  @Synchronized
  fun ensureLoaded() {
    if (libraryLoaded) return
    System.loadLibrary("lomio_jni")
    libraryLoaded = true
  }

  /** Also registers the ggml CPU backend variant best suited to this device. */
  @Synchronized
  fun ensureInitialized(nativeLibDir: String) {
    if (backendsInitialized) return
    ensureLoaded()
    nativeInit(nativeLibDir.toByteArray())
    backendsInitialized = true
  }

  external fun nativeInit(nativeLibDir: ByteArray)

  external fun nativeSystemInfo(): ByteArray

  external fun nativeLoad(
    path: ByteArray,
    nCtx: Int,
    nThreads: Int,
    nBatch: Int,
    nGpuLayers: Int,
    useMmap: Boolean,
  ): Long

  external fun nativeFree(handle: Long)

  /**
   * longs: `[nParams, sizeBytes, nCtxTrain, nCtx, nLayer, supportsThinking]`;
   * strings: `[description, architecture, chatTemplate]`.
   */
  external fun nativeModelInfo(handle: Long, longs: LongArray, strings: Array<ByteArray?>)

  /**
   * longs (-1 = missing): `[fileType, nCtxTrain, nLayer, nEmbd, nHead, nHeadKv, keyLength,
   * valueLength, nTensors, nParams]`; strings: `[architecture, name, sizeLabel, chatTemplate]`.
   * Returns false if the file is not a readable GGUF.
   */
  external fun nativeReadGguf(path: ByteArray, longs: LongArray, strings: Array<ByteArray?>): Boolean

  /** Thread-safe: asks the running [nativeChat] / [nativeBench] to return early. */
  external fun nativeStop()

  /**
   * floats: `[temperature, topP, minP, repeatPenalty, presencePenalty, frequencyPenalty]`;
   * ints: `[topK, repeatLastN, maxTokens]`; stats (out): `[promptTokens, cachedTokens,
   * generatedTokens, ttftMs, promptTps, genTps, stopReasonOrdinal]`.
   */
  external fun nativeChat(
    handle: Long,
    roles: Array<ByteArray>,
    contents: Array<ByteArray>,
    reasonings: Array<ByteArray?>,
    enableThinking: Boolean,
    floats: FloatArray,
    ints: IntArray,
    seed: Long,
    stops: Array<ByteArray>,
    callback: UpdateCallback,
    stats: DoubleArray,
  )

  /** out: `[ppTps, ppStd, tgTps, tgStd]`; returns the comma-separated backend list. */
  external fun nativeBench(handle: Long, pp: Int, tg: Int, reps: Int, out: DoubleArray): ByteArray
}
