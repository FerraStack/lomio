package dev.lomio.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.lomio.data.db.LomioDatabase
import dev.lomio.data.db.ModelEntity
import dev.lomio.data.db.ModelSettingsEntity
import dev.lomio.data.db.ModelSource
import dev.lomio.data.db.ModelState
import dev.lomio.data.download.DownloadScheduler
import dev.lomio.data.download.DownloadWorker
import dev.lomio.data.download.InsufficientStorageException
import dev.lomio.data.hf.GgufNames
import dev.lomio.data.hf.HfFile
import dev.lomio.domain.GenerationSettings
import dev.lomio.domain.LoadSettings
import dev.lomio.domain.ModelShape
import dev.lomio.domain.SettingsJson
import dev.lomio.llama.GgufMetadata
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Reads GGUF header metadata; the real implementation is native (llama.cpp gguf API). */
fun interface GgufReader {
  fun read(path: String): GgufMetadata?
}

class ModelRepository(
  private val context: Context,
  private val db: LomioDatabase,
  private val downloads: DownloadScheduler,
  private val gguf: GgufReader,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val dao = db.models()

  /**
   * In noBackupFilesDir: multi-GB models must never go into cloud backup or device-to-device
   * transfer (see also res/xml/data_extraction_rules.xml).
   */
  val modelsDir: File
    get() = File(context.noBackupFilesDir, "models")

  fun observeModels(): Flow<List<ModelEntity>> = dao.observeAll()

  suspend fun get(id: String): ModelEntity? = dao.get(id)

  suspend fun all(): List<ModelEntity> = dao.observeAll().first()

  /** Destination for a Hugging Face file: `filesDir/models/hf_<owner>_<repo>/<file>`. */
  fun hfDestination(repo: String, path: String): File =
    File(File(modelsDir, "hf_" + repo.replace("/", "_")), path.substringAfterLast("/"))

  /** @throws InsufficientStorageException before anything is enqueued if the file can't fit. */
  suspend fun download(file: HfFile): ModelEntity {
    val existing = dao.findByHf(file.repo, file.path)
    val free = withContext(Dispatchers.IO) { modelsDir.apply { mkdirs() }.usableSpace }
    val needed = file.sizeBytes - (existing?.bytesDone ?: 0) + DownloadWorker.STORAGE_MARGIN_BYTES
    if (needed > free) throw InsufficientStorageException(needed, free)
    val model =
      (existing
          ?: ModelEntity(
            id = UUID.randomUUID().toString(),
            source = ModelSource.HF,
            hfRepo = file.repo,
            hfFile = file.path,
            hfRevision = file.revision,
            displayName = GgufNames.displayName(file.fileName),
            filePath = hfDestination(file.repo, file.path).absolutePath,
            sizeBytes = file.sizeBytes,
            sha256 = file.sha256,
            state = ModelState.REMOTE,
            quant = file.quant,
            createdAt = clock(),
          ))
        .copy(state = ModelState.QUEUED, errorMessage = null)
    dao.upsert(model)
    downloads.enqueue(model.id, model.sizeBytes - model.bytesDone)
    return model
  }

  suspend fun pause(id: String) {
    downloads.cancel(id)
    val m = dao.get(id) ?: return
    if (m.state == ModelState.DOWNLOADING || m.state == ModelState.QUEUED) {
      dao.updateProgress(id, ModelState.PAUSED, m.bytesDone)
    }
  }

  suspend fun resume(id: String) {
    val m = dao.get(id) ?: return
    dao.updateProgress(id, ModelState.QUEUED, m.bytesDone)
    downloads.enqueue(id, m.sizeBytes - m.bytesDone)
  }

  /** Cancels any download and removes the model, its files and settings. */
  suspend fun delete(id: String) {
    downloads.cancel(id)
    val m = dao.get(id) ?: return
    withContext(Dispatchers.IO) {
      m.filePath?.let { path ->
        val f = File(path)
        // Only delete files we own (inside app storage).
        if (f.canonicalPath.startsWith(modelsDir.canonicalPath)) {
          f.delete()
          File("$path.part").delete()
          f.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
        }
      }
    }
    dao.delete(id)
  }

  suspend fun rename(id: String, name: String) = dao.rename(id, name.trim())

  suspend fun touch(id: String) = dao.touch(id, clock())

  /** Copies a GGUF picked with the Storage Access Framework into app storage. */
  suspend fun importLocal(uri: Uri): ModelEntity =
    withContext(Dispatchers.IO) {
      val resolver = context.contentResolver
      var name = "model.gguf"
      var size = -1L
      resolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
          c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
          c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!c.isNull(it)) size = c.getLong(it) }
        }
      }
      if (!name.endsWith(".gguf", ignoreCase = true)) throw IOException("Not a .gguf file: $name")
      val dir = File(modelsDir, "local").apply { mkdirs() }
      var dest = File(dir, name)
      var n = 1
      while (dest.exists()) dest = File(dir, name.removeSuffix(".gguf") + " (${n++}).gguf")
      val tmp = File(dest.path + ".part")
      resolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
        ?: throw IOException("Cannot open $uri")
      if (!tmp.renameTo(dest)) throw IOException("Cannot move imported file into place")
      val model =
        ModelEntity(
          id = UUID.randomUUID().toString(),
          source = ModelSource.LOCAL,
          displayName = GgufNames.displayName(name),
          filePath = dest.absolutePath,
          sizeBytes = if (size > 0) size else dest.length(),
          state = ModelState.READY,
          bytesDone = dest.length(),
          quant = GgufNames.quant(name),
          createdAt = clock(),
        )
      val withMeta = withMetadata(model, dest)
      dao.upsert(withMeta)
      withMeta
    }

  /** Registers a GGUF that is already inside app storage (used by tests and the importer). */
  suspend fun registerLocalFile(file: File, displayName: String = GgufNames.displayName(file.name)): ModelEntity =
    withContext(Dispatchers.IO) {
      val model =
        ModelEntity(
          id = UUID.randomUUID().toString(),
          source = ModelSource.LOCAL,
          displayName = displayName,
          filePath = file.absolutePath,
          sizeBytes = file.length(),
          state = ModelState.READY,
          bytesDone = file.length(),
          quant = GgufNames.quant(file.name),
          createdAt = clock(),
        )
      withMetadata(model, file).also { dao.upsert(it) }
    }

  /** Fills cached GGUF metadata; a broken header is reported but doesn't fail the import. */
  fun withMetadata(model: ModelEntity, file: File): ModelEntity {
    val meta = runCatching { gguf.read(file.absolutePath) }.getOrNull() ?: return model
    return model.copy(
      arch = meta.architecture,
      displayName = model.displayName,
      nParams = meta.nParams.takeIf { it > 0 },
      nLayers = meta.nLayer,
      nCtxTrain = meta.nCtxTrain,
      nEmbd = meta.nEmbd,
      nHead = meta.nHead,
      nHeadKv = meta.nHeadKv,
      keyLength = meta.keyLength,
      valueLength = meta.valueLength,
      quant = meta.fileType?.let(::quantName) ?: model.quant,
      chatTemplate = meta.chatTemplate,
    )
  }

  suspend fun settings(modelId: String): ModelSettingsEntity? = dao.settings(modelId)

  fun observeSettings(modelId: String) = dao.observeSettings(modelId)

  suspend fun saveSettings(
    modelId: String,
    gen: GenerationSettings?,
    load: LoadSettings?,
    systemPrompt: String?,
  ) {
    dao.upsertSettings(
      ModelSettingsEntity(
        modelId = modelId,
        loadJson = SettingsJson.encodeLoad(load),
        genJson = SettingsJson.encodeGen(gen),
        systemPrompt = systemPrompt?.takeIf { it.isNotBlank() },
      )
    )
  }

  companion object {
    fun shapeOf(m: ModelEntity) =
      ModelShape(
        fileSizeBytes = m.sizeBytes,
        nLayers = m.nLayers,
        nEmbd = m.nEmbd,
        nHead = m.nHead,
        nHeadKv = m.nHeadKv,
        keyLength = m.keyLength,
        valueLength = m.valueLength,
      )

    /** llama.cpp `llama_ftype` → quant label. */
    fun quantName(fileType: Int): String? =
      mapOf(
          0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 7 to "Q8_0", 8 to "Q5_0", 9 to "Q5_1",
          10 to "Q2_K", 11 to "Q3_K_S", 12 to "Q3_K_M", 13 to "Q3_K_L", 14 to "Q4_K_S",
          15 to "Q4_K_M", 16 to "Q5_K_S", 17 to "Q5_K_M", 18 to "Q6_K", 19 to "IQ2_XXS",
          20 to "IQ2_XS", 21 to "Q2_K_S", 22 to "IQ3_XS", 23 to "IQ3_XXS", 24 to "IQ1_S",
          25 to "IQ4_NL", 26 to "IQ3_S", 27 to "IQ3_M", 28 to "IQ2_S", 29 to "IQ2_M",
          30 to "IQ4_XS", 31 to "IQ1_M", 32 to "BF16", 36 to "TQ1_0", 37 to "TQ2_0", 38 to "MXFP4",
        )[fileType]
  }
}
