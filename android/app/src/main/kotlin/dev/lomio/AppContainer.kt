package dev.lomio

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dev.lomio.data.AttachmentStore
import dev.lomio.data.ChatRepository
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
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Manual DI: one instance per process, created by [LomioApp]. */
class AppContainer(context: Context) {
  val appContext: Context = context.applicationContext

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

  val engine: InferenceEngine =
    LlamaEngine(
      appContext.applicationInfo.nativeLibraryDir,
      gpuKernelCacheDir = java.io.File(appContext.cacheDir, "opencl-kernels").apply { mkdirs() }.absolutePath,
    )
  val crashGuard = CrashGuard(appContext.getSharedPreferences("crash_guard", Context.MODE_PRIVATE))
  val memory = AndroidMemoryProvider(appContext)
  val modelManager = ModelManager(engine, models, settings, crashGuard, memory)
  val chatService = ChatService(engine, modelManager, chats, models, settings, scope)

  fun setHfToken(token: String?) {
    hfToken = token
  }
}
