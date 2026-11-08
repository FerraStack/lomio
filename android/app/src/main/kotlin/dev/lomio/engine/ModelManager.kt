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

  /** TIGHT or WONT_FIT: the user must confirm ([ModelManager.pendingConfirmation]). */
  data class NeedsConfirmation(val pending: PendingLoad) : LoadOutcome

  data class Failed(val message: String) : LoadOutcome
}

data class PendingLoad(
  val modelId: String,
  val modelName: String,
  val fit: Fit,
  val estimate: MemoryEstimate,
  val memory: DeviceMemory,
)

/**
 * Owns which model is loaded; adds the memory check and the crash-loop guard around the engine.
 *
 * The guard marker is cleared when `engine.load` returns, which happens only after llama.cpp's
 * warm-up decode succeeded (see nativeLoad), i.e. after the first successful decode.
 */
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
  private val _pending = MutableStateFlow<PendingLoad?>(null)
  private var activeLoad: LoadSettings? = null

  /** Load settings the active model was loaded with; null when nothing is loaded. */
  val activeLoadSettings: LoadSettings?
    get() = activeLoad
  private var autoLoadDone = false

  val engineState: StateFlow<EngineState> = engine.state
  val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

  /** Set when the previous process died while loading this model. */
  val crashedModelId: StateFlow<String?> = _crashedModelId.asStateFlow()

  /** A load waiting for the user to accept a memory warning. */
  val pendingConfirmation: StateFlow<PendingLoad?> = _pending.asStateFlow()

  suspend fun loadSettingsFor(modelId: String): LoadSettings {
    val global = settings.current().load
    val perModel = SettingsJson.decodeLoad(models.settings(modelId)?.loadJson)
    return SettingsResolver.load(global, perModel)
  }

  /**
   * Memory verdict for loading [model]. Memory held by the currently loaded model counts as
   * available, because it is unloaded first.
   */
  suspend fun fit(model: ModelEntity, nCtx: Int? = null): Triple<Fit, MemoryEstimate, DeviceMemory> {
    val ctx = nCtx ?: loadSettingsFor(model.id).toLoadParams().nCtx
    val estimate = MemoryFit.estimate(ModelRepository.shapeOf(model), ctx)
    val raw = memory.current()
    val loaded = _activeModelId.value?.let { models.get(it) }
    val reclaim = MemoryFit.reclaimable(loaded?.let(ModelRepository::shapeOf), activeLoad?.toLoadParams()?.nCtx ?: ctx)
    val mem = raw.copy(availableBytes = raw.availableBytes + reclaim)
    return Triple(MemoryFit.verdict(estimate, mem.availableBytes, mem.totalBytes), estimate, mem)
  }

  /** @param force skip the memory confirmation (the user already accepted it). */
  suspend fun load(modelId: String, force: Boolean = false): LoadOutcome =
    mutex.withLock {
      val model = models.get(modelId) ?: return LoadOutcome.Failed("Model not found")
      val path = model.filePath
      if (model.state != ModelState.READY || path == null || !File(path).exists()) {
        return LoadOutcome.Failed("${model.displayName} is not downloaded")
      }
      val load = loadSettingsFor(modelId)
      val (fit, estimate, mem) = fit(model, load.toLoadParams().nCtx)
      if (fit != Fit.FITS && !force) {
        val pending = PendingLoad(modelId, model.displayName, fit, estimate, mem)
        _pending.value = pending
        return LoadOutcome.NeedsConfirmation(pending)
      }
      _pending.value = null

      crashGuard.beginLoad(modelId)
      try {
        val info = engine.load(path, load.toLoadParams())
        _activeModelId.value = modelId
        activeLoad = load
        _crashedModelId.value = null
        models.touch(modelId)
        settings.update { it.copy(lastModelId = modelId) }
        LoadOutcome.Loaded(info)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        // Includes UnsatisfiedLinkError if the native library is missing for this ABI.
        _activeModelId.value = null
        activeLoad = null
        LoadOutcome.Failed(e.message ?: "Failed to load model")
      } finally {
        // Reached only if the process survived the load including its warm-up decode.
        crashGuard.endLoad()
      }
    }

  suspend fun confirmPending(): LoadOutcome? {
    val p = _pending.value ?: return null
    _pending.value = null
    return load(p.modelId, force = true)
  }

  fun dismissPending() {
    _pending.value = null
  }

  suspend fun unload() =
    mutex.withLock {
      engine.unload()
      _activeModelId.value = null
      activeLoad = null
    }

  /** Process start (any reason, including WorkManager): only reads the crash marker. */
  fun onProcessStart() {
    crashGuard.consumeCrashedLoad()?.let { _crashedModelId.value = it }
  }

  /**
   * First time the UI comes to the foreground in this process: auto-load the last model unless
   * its last load crashed (RESEARCH.md §7, product decision 2). Never runs for background starts.
   */
  suspend fun onFirstForeground() {
    if (autoLoadDone) return
    autoLoadDone = true
    if (_crashedModelId.value != null) return
    val s = settings.current()
    val last = s.lastModelId ?: return
    if (s.autoLoadLastModel && models.get(last)?.state == ModelState.READY) load(last)
  }

  fun dismissCrashNotice() {
    _crashedModelId.value = null
  }
}
