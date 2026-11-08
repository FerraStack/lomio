package dev.lomio.api

import dev.lomio.data.ModelRepository
import dev.lomio.data.db.ModelEntity
import dev.lomio.data.settings.SettingsRepository
import dev.lomio.domain.ContextWindow
import dev.lomio.domain.SettingsJson
import dev.lomio.domain.SettingsResolver
import dev.lomio.llama.EngineState
import dev.lomio.llama.GenerationEvent
import dev.lomio.llama.InferenceEngine
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * Serves API requests with the model loaded in the app. Sampling starts from Lomio's global and
 * per-model settings; whatever the request sets wins. The app's default system prompt is not
 * added: API clients send their own. Long conversations keep their newest turns, as in the app.
 */
class EngineApiBackend(
  private val engine: InferenceEngine,
  private val activeModelId: () -> String?,
  private val models: ModelRepository,
  private val settings: SettingsRepository,
  /** True while the app's own chat is generating. */
  private val appBusy: () -> Boolean,
) : ApiBackend {
  override suspend fun model(): ApiModel? {
    val id = activeModelId() ?: return null
    if (engine.state.value !is EngineState.Loaded && engine.state.value !is EngineState.Generating) return null
    val m = models.get(id) ?: return null
    return ApiModel(apiModelId(m), m.createdAt / 1000)
  }

  override suspend fun chat(request: ChatCompletionRequest): Flow<GenerationEvent> {
    if (appBusy()) throw ApiError(503, "Lomio is writing a reply in the app. Try again in a moment.", "server_error", "busy")
    val id = activeModelId() ?: throw ApiError(503, ApiServer.NO_MODEL, "server_error", "model_not_loaded")
    val gen = SettingsResolver.generation(settings.current().generation, SettingsJson.decodeGen(models.settings(id)?.genJson), null)
    val sampling = request.sampling.applyTo(gen.toSamplingParams())
    val nCtx =
      when (val s = engine.state.value) {
        is EngineState.Loaded -> s.info.nCtx
        is EngineState.Generating -> s.info.nCtx
        else -> throw ApiError(503, ApiServer.NO_MODEL, "server_error", "model_not_loaded")
      }
    return engine.chat(ContextWindow.trim(request.messages, nCtx, sampling.maxTokens), sampling, request.enableThinking)
  }

  companion object {
    /** The GGUF file name without `.gguf`, e.g. "Llama-3.2-3B-Instruct-Q4_0": stable and readable. */
    fun apiModelId(m: ModelEntity): String =
      m.filePath?.let { File(it).nameWithoutExtension }?.takeIf { it.isNotBlank() } ?: m.displayName
  }
}
