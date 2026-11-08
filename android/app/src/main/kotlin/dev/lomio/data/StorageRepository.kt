package dev.lomio.data

import dev.lomio.data.db.ModelEntity
import dev.lomio.data.db.ModelState
import dev.lomio.domain.Storage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the Storage screen shows; sizes are bytes on disk. */
data class StorageScan(
  /** Size of the volume that holds the models, and what's free on it. */
  val volumeBytes: Long,
  val freeBytes: Long,
  /** Model id → bytes on disk (the finished file, or the `.part` of a download in progress). */
  val modelBytes: Map<String, Long>,
  /** Models marked downloaded whose file is gone. */
  val missingFiles: Set<String>,
  val leftovers: List<Storage.FileEntry>,
  /** Temporary files, not counting the chat caches. */
  val cacheBytes: Long,
  /** Chat history: the Room database and its journal files. */
  val chatsBytes: Long,
  /** Saved prompt caches that let long chats reopen without re-reading them. */
  val chatCacheBytes: Long = 0,
) {
  val leftoverBytes: Long
    get() = leftovers.sumOf { it.sizeBytes }

  val totalBytes: Long
    get() = modelBytes.values.sum() + leftoverBytes + cacheBytes + chatsBytes + chatCacheBytes
}

/**
 * Measures and cleans app storage. Plain [File]s and lambdas so it runs in JVM unit tests.
 *
 * @param modelsDir where downloads and imports live ([ModelRepository.modelsDir])
 * @param cacheDirs scratch space Android may also clear on its own
 * @param databaseFiles the chat database files, for display only
 * @param chatCacheDir the prompt caches (inside a cache dir); shown and cleared on their own
 */
class StorageRepository(
  private val modelsDir: () -> File,
  private val cacheDirs: () -> List<File>,
  private val databaseFiles: () -> List<File>,
  private val chatCacheDir: () -> File? = { null },
  private val clock: () -> Long = System::currentTimeMillis,
) {
  suspend fun scan(models: List<ModelEntity>): StorageScan =
    withContext(Dispatchers.IO) {
      val dir = modelsDir().apply { mkdirs() }
      val sizes = mutableMapOf<String, Long>()
      val missing = mutableSetOf<String>()
      for (m in models) {
        val path = m.filePath
        val file = path?.let(::File)
        val part = path?.let { File("$it.part") }
        // A vision model's projector counts as part of the model.
        val projector = m.mmprojPath?.let(::File)
        val projectorBytes =
          when {
            projector == null -> 0L
            projector.isFile -> projector.length()
            else -> File("${projector.path}.part").takeIf { it.isFile }?.length() ?: 0L
          }
        sizes[m.id] =
          projectorBytes +
            when {
              file?.isFile == true -> file.length()
              part?.isFile == true -> part.length()
              else -> 0L
            }
        if (m.state == ModelState.READY && file?.isFile != true) missing += m.id
      }
      StorageScan(
        volumeBytes = dir.totalSpace,
        freeBytes = dir.usableSpace,
        modelBytes = sizes,
        missingFiles = missing,
        leftovers = findLeftovers(dir, models),
        cacheBytes = cacheDirs().sumOf { sizeOf(it, skip = chatCacheDir()) },
        chatCacheBytes = chatCacheDir()?.let { sizeOf(it) } ?: 0L,
        chatsBytes = databaseFiles().filter { it.isFile }.sumOf { it.length() },
      )
    }

  /** Deletes leftover files (found afresh, never from a stale scan). Returns the bytes freed. */
  suspend fun deleteLeftovers(models: List<ModelEntity>): Long =
    withContext(Dispatchers.IO) {
      val dir = modelsDir()
      var freed = 0L
      for (f in findLeftovers(dir, models)) {
        val file = File(f.path)
        if (file.delete()) freed += f.sizeBytes
      }
      pruneEmptyDirs(dir)
      freed
    }

  /** Empties the cache directories (the directories themselves stay). Returns the bytes freed. */
  suspend fun clearCache(): Long =
    withContext(Dispatchers.IO) {
      val keep = chatCacheDir()?.canonicalFile
      var freed = 0L
      for (dir in cacheDirs()) {
        dir.listFiles()?.forEach { child ->
          if (keep != null && child.canonicalFile == keep) return@forEach // has its own row
          val size = sizeOf(child)
          if (child.deleteRecursively()) freed += size
        }
      }
      freed
    }

  /** Deletes the saved prompt caches (the folder stays). Only costs speed: they're rebuilt. */
  suspend fun clearChatCaches(): Long =
    withContext(Dispatchers.IO) {
      var freed = 0L
      chatCacheDir()?.listFiles()?.forEach { f ->
        val size = sizeOf(f)
        if (f.deleteRecursively()) freed += size
      }
      freed
    }

  private fun findLeftovers(dir: File, models: List<ModelEntity>): List<Storage.FileEntry> {
    val root = dir.canonicalFile
    val files =
      root
        .walkTopDown()
        .filter { it.isFile }
        .map { Storage.FileEntry(it.canonicalPath, it.length(), it.lastModified()) }
        .toList()
    val referenced = models.flatMap { m -> listOfNotNull(m.filePath, m.mmprojPath).map { File(it).canonicalPath } }
    return Storage.leftovers(files, referenced, clock())
  }

  /** Removes empty sub-directories (e.g. `hf_owner_repo/`) but keeps [root] itself. */
  private fun pruneEmptyDirs(root: File) {
    root.walkBottomUp().filter { it.isDirectory && it != root && it.list().isNullOrEmpty() }.forEach { it.delete() }
  }

  private fun sizeOf(f: File, skip: File? = null): Long {
    if (f.isFile) return f.length()
    val skipped = skip?.canonicalFile
    return f.walkTopDown().onEnter { skipped == null || it.canonicalFile != skipped }.filter { it.isFile }.sumOf { it.length() }
  }
}
