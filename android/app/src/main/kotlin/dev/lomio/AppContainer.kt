package dev.lomio

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dev.lomio.data.AttachmentStore
import dev.lomio.api.ApiServer
import dev.lomio.api.ApiServerController
import dev.lomio.api.ApiServerSettingsRepository
import dev.lomio.api.EngineApiBackend
import dev.lomio.data.ChatRepository
import dev.lomio.data.transfer.ChatArchive
import dev.lomio.data.GgufReader
import dev.lomio.data.ModelRepository
import dev.lomio.data.StorageRepository
import dev.lomio.data.db.LomioDatabase
import dev.lomio.data.download.DownloadGate
import dev.lomio.data.download.DownloadScheduler
import dev.lomio.data.download.ResumableDownloader
import dev.lomio.data.hf.HfClient
import dev.lomio.data.settings.SettingsRepository
import dev.lomio.domain.CrashGuard
import dev.lomio.engine.AndroidMemoryProvider
import dev.lomio.engine.ChatService
import dev.lomio.engine.ModelManager
import dev.lomio.llama.Gguf
import dev.lomio.llama.InferenceEngine
import dev.lomio.llama.LlamaEngine
import dev.lomio.llama.PromptCache
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import android.content.res.Resources
import dev.lomio.engine.EngineText

private val Context.dataStore by preferencesDataStore(name = "settings")
private val Context.apiServerStore by preferencesDataStore(name = "api_server")

/** Manual DI: one instance per process, created by [LomioApp]. */
class AppContainer(context: Context) {
  val appContext: Context = context.applicationContext

  /** Strings for code outside Compose (view models, workers, services), in the app's language. */
  val text: Resources
    get() = appContext.resources

  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  val http: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      .build()

  val db: LomioDatabase = LomioDatabase.create(appContext)
  val settings = SettingsRepository(appContext.dataStore)

  @Volatile private var hfToken: String? = null
  val hfClient = HfClient(http, token = { hfToken })
  val downloader = ResumableDownloader(http)
  val downloads = DownloadScheduler(appContext)
  val downloadGate = DownloadGate()

  val models = ModelRepository(appContext, db, downloads, GgufReader { Gguf.read(it) })
  val storage =
    StorageRepository(
      modelsDir = { models.modelsDir },
      cacheDirs = { listOfNotNull(appContext.cacheDir, appContext.externalCacheDir) },
      databaseFiles = { listOf("lomio.db", "lomio.db-wal", "lomio.db-shm").map(appContext::getDatabasePath) },
    )
  val attachments = AttachmentStore(File(appContext.filesDir, "attachments"))
  val chats = ChatRepository(db, attachmentPath = { attachments.file(it).absolutePath })
  val archive = ChatArchive(db)

  /** Per-chat KV caches (in cacheDir: Android may clear them, which only costs speed). */
  val promptCache = PromptCache(File(appContext.cacheDir, "prompt-cache"))
  val engine: InferenceEngine =
    LlamaEngine(
      appContext.applicationInfo.nativeLibraryDir,
      gpuKernelCacheDir = java.io.File(appContext.cacheDir, "opencl-kernels").apply { mkdirs() }.absolutePath,
      promptCache = promptCache,
    )
  val crashGuard = CrashGuard(appContext.getSharedPreferences("crash_guard", Context.MODE_PRIVATE))
  val memory = AndroidMemoryProvider(appContext)
  private val engineText =
    EngineText(
      loadModelFirst = text.getString(R.string.err_load_first),
      generationStopped = text.getString(R.string.err_generation_stopped),
      generationFailed = text.getString(R.string.message_failed),
      modelNotFound = text.getString(R.string.err_model_not_found),
      notDownloaded = { name -> text.getString(R.string.err_not_downloaded, name) },
      loadFailed = text.getString(R.string.err_load_failed),
      gpuCrashNotice = text.getString(R.string.gpu_crash_notice),
      defaultSystemPrompt = text.getString(R.string.default_system_prompt),
    )
  val modelManager = ModelManager(engine, models, settings, crashGuard, memory, engineText)
  val chatService = ChatService(engine, modelManager, chats, models, settings, scope, engineText)
  val apiServer =
    ApiServerController(
      appContext,
      ApiServer(EngineApiBackend(engine, { modelManager.activeModelId.value }, models, settings, { chatService.isBusy })),
      ApiServerSettingsRepository(appContext.apiServerStore),
      scope,
    )

  fun setHfToken(token: String?) {
    hfToken = token
  }
}
