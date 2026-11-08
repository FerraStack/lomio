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

    fun onPromptProgress(done: Int, total: Int)
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
    chatTemplate: ByteArray?,
    mmprojPath: ByteArray?,
  ): Long

  /**
   * Formats [roles]/[contents] with a Jinja chat template, without a model. Stateless and
   * thread-safe (unlike the rest of this bridge). Throws [LlamaException] when the template does
   * not parse or render.
   */
  external fun nativeFormatChat(
    template: ByteArray,
    bosToken: ByteArray,
    eosToken: ByteArray,
    roles: Array<ByteArray>,
    contents: Array<ByteArray>,
    addGenerationPrompt: Boolean,
  ): ByteArray

  external fun nativeFree(handle: Long)

  /**
   * longs: `[nParams, sizeBytes, nCtxTrain, nCtx, nLayer, supportsThinking, supportsVision]`;
   * strings: `[description, architecture, chatTemplate, backend, gpuNote]`.
   */
  external fun nativeModelInfo(handle: Long, longs: LongArray, strings: Array<ByteArray?>)

  /**
   * longs (-1 = missing): `[fileType, nCtxTrain, nLayer, nEmbd, nHead, nHeadKv, keyLength,
   * valueLength, nTensors, nParams]`; strings: `[architecture, name, sizeLabel, chatTemplate]`.
   * Returns false if the file is not a readable GGUF.
   */
  external fun nativeReadGguf(path: ByteArray, longs: LongArray, strings: Array<ByteArray?>): Boolean

  /**
   * Thread-safe: cancels every chat/bench operation whose op id is ≤ [uptoOpId], including one
   * that hasn't reached native code yet.
   */
  external fun nativeStop(uptoOpId: Long)

  /**
   * floats: `[temperature, topP, minP, repeatPenalty, presencePenalty, frequencyPenalty]`;
   * ints: `[topK, repeatLastN, maxTokens]`; stats (out): `[promptTokens, cachedTokens,
   * generatedTokens, ttftMs, promptTps, genTps, stopReasonOrdinal]`. images: the image paths of
   * all messages in order; imageCounts[i]: how many of them belong to message i.
   */
  external fun nativeChat(
    handle: Long,
    roles: Array<ByteArray>,
    contents: Array<ByteArray>,
    reasonings: Array<ByteArray?>,
    images: Array<ByteArray>,
    imageCounts: IntArray,
    enableThinking: Boolean,
    floats: FloatArray,
    ints: IntArray,
    seed: Long,
    stops: Array<ByteArray>,
    callback: UpdateCallback,
    stats: DoubleArray,
    opId: Long,
  )

  /** out: `[ppTps, ppStd, tgTps, tgStd]`; returns where the model runs (see [ModelInfo.backend]). */
  external fun nativeBench(handle: Long, pp: Int, tg: Int, reps: Int, out: DoubleArray, opId: Long): ByteArray
}
