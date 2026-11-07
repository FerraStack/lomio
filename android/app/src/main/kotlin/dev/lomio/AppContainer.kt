package dev.lomio

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dev.lomio.data.ChatRepository
import dev.lomio.data.GgufReader
import dev.lomio.data.ModelRepository
import dev.lomio.data.db.LomioDatabase
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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Manual DI: one instance per process, created by [LomioApp]. */
class AppContainer(context: Context) {
  private val appContext = context.applicationContext

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

  val models = ModelRepository(appContext, db, downloads, GgufReader { Gguf.read(it) })
  val chats = ChatRepository(db)

  val engine: InferenceEngine = LlamaEngine(appContext.applicationInfo.nativeLibraryDir)
  val crashGuard = CrashGuard(appContext.getSharedPreferences("crash_guard", Context.MODE_PRIVATE))
  val memory = AndroidMemoryProvider(appContext)
  val modelManager = ModelManager(engine, models, settings, crashGuard, memory)
  val chatService = ChatService(engine, modelManager, chats, models, settings, scope)

  fun setHfToken(token: String?) {
    hfToken = token
  }
}
