package dev.lomio.engine

import android.app.ActivityManager
import android.content.Context
import dev.lomio.data.ModelRepository
import dev.lomio.data.db.ModelEntity
import dev.lomio.data.db.ModelState
import dev.lomio.data.settings.SettingsRepository
import dev.lomio.domain.CrashGuard
import dev.lomio.domain.Fit
import dev.lomio.domain.LoadSettings
import dev.lomio.domain.MemoryEstimate
import dev.lomio.domain.MemoryFit
import dev.lomio.domain.SettingsJson
import dev.lomio.domain.SettingsResolver
import dev.lomio.llama.EngineState
import dev.lomio.llama.InferenceEngine
import dev.lomio.llama.ModelInfo
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DeviceMemory(val availableBytes: Long, val totalBytes: Long)

fun interface DeviceMemoryProvider {
  fun current(): DeviceMemory
}

class AndroidMemoryProvider(private val context: Context) : DeviceMemoryProvider {
  override fun current(): DeviceMemory {
    val am = context.getSystemService(ActivityManager::class.java)
    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    return DeviceMemory(info.availMem, info.totalMem)
  }
}

sealed interface LoadOutcome {
  data class Loaded(val info: ModelInfo) : LoadOutcome

  /** Refused by the memory check; retry with `force = true` to load anyway. */
  data class WontFit(val estimate: MemoryEstimate, val memory: DeviceMemory) : LoadOutcome

  data class Failed(val message: String) : LoadOutcome
}

/** Owns which model is loaded; adds the memory check and crash-loop guard around the engine. */
class ModelManager(
  private val engine: InferenceEngine,
  private val models: ModelRepository,
  private val settings: SettingsRepository,
  private val crashGuard: CrashGuard,
  private val memory: DeviceMemoryProvider,
) {
  private val mutex = Mutex()
  private val _activeModelId = MutableStateFlow<String?>(null)
  private val _crashedModelId = MutableStateFlow<String?>(null)

  val engineState: StateFlow<EngineState> = engine.state
  val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

  /** Set when the previous process died while loading this model. */
  val crashedModelId: StateFlow<String?> = _crashedModelId.asStateFlow()

  suspend fun loadSettingsFor(modelId: String): LoadSettings {
    val global = settings.current().load
    val perModel = SettingsJson.decodeLoad(models.settings(modelId)?.loadJson)
    return SettingsResolver.load(global, perModel)
  }

  suspend fun fit(model: ModelEntity, nCtx: Int? = null): Pair<Fit, MemoryEstimate> {
    val ctx = nCtx ?: loadSettingsFor(model.id).toLoadParams().nCtx
    val estimate = MemoryFit.estimate(ModelRepository.shapeOf(model), ctx)
    val mem = memory.current()
    return MemoryFit.verdict(estimate, mem.availableBytes, mem.totalBytes) to estimate
  }

  suspend fun load(modelId: String, force: Boolean = false): LoadOutcome =
    mutex.withLock {
      val model = models.get(modelId) ?: return LoadOutcome.Failed("Model not found")
      val path = model.filePath
      if (model.state != ModelState.READY || path == null || !File(path).exists()) {
        return LoadOutcome.Failed("${model.displayName} is not downloaded")
      }
      val params = loadSettingsFor(modelId).toLoadParams()
      val (fit, estimate) = fit(model, params.nCtx)
      if (fit == Fit.WONT_FIT && !force) return LoadOutcome.WontFit(estimate, memory.current())

      crashGuard.beginLoad(modelId)
      try {
        val info = engine.load(path, params)
        _activeModelId.value = modelId
        _crashedModelId.value = null
        models.touch(modelId)
        settings.update { it.copy(lastModelId = modelId) }
        LoadOutcome.Loaded(info)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        // Includes UnsatisfiedLinkError if the native library is missing for this ABI.
        _activeModelId.value = null
        LoadOutcome.Failed(e.message ?: "Failed to load model")
      } finally {
        crashGuard.endLoad()
      }
    }

  suspend fun unload() =
    mutex.withLock {
      engine.unload()
      _activeModelId.value = null
    }

  /**
   * Called once per process start. Never auto-loads a model whose last load crashed
   * (RESEARCH.md §7, product decision 2).
   */
  suspend fun onAppStart() {
    val crashed = crashGuard.consumeCrashedLoad()
    if (crashed != null) {
      _crashedModelId.value = crashed
      return
    }
    val s = settings.current()
    val last = s.lastModelId ?: return
    if (s.autoLoadLastModel && models.get(last)?.state == ModelState.READY) load(last)
  }

  fun dismissCrashNotice() {
    _crashedModelId.value = null
  }
}
