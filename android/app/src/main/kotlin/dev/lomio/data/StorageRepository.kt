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
  val cacheBytes: Long,
  /** Chat history: the Room database and its journal files. */
  val chatsBytes: Long,
) {
  val leftoverBytes: Long
    get() = leftovers.sumOf { it.sizeBytes }

  val totalBytes: Long
    get() = modelBytes.values.sum() + leftoverBytes + cacheBytes + chatsBytes
}

/**
 * Measures and cleans app storage. Plain [File]s and lambdas so it runs in JVM unit tests.
 *
 * @param modelsDir where downloads and imports live ([ModelRepository.modelsDir])
 * @param cacheDirs scratch space Android may also clear on its own
 * @param databaseFiles the chat database files, for display only
 */
class StorageRepository(
  private val modelsDir: () -> File,
  private val cacheDirs: () -> List<File>,
  private val databaseFiles: () -> List<File>,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  suspend fun scan(models: List<ModelEntity>): StorageScan =
    withContext(Dispatchers.IO) {
      val dir = modelsDir().apply { mkdirs() }
      val sizes = mutableMapOf<String, Long>()
      val missing = mutableSetOf<String>()
      for (m in models) {
        // One model, however many files: every part of a split model and the vision projector
        // count towards it (a file still downloading counts with its part file).
        fun onDisk(f: File) = if (f.isFile) f.length() else File("${f.path}.part").takeIf { it.isFile }?.length() ?: 0L
        sizes[m.id] = ModelFiles.owned(m).sumOf(::onDisk)
        if (m.state == ModelState.READY && ModelFiles.weights(m).any { !it.isFile }) missing += m.id
      }
      StorageScan(
        volumeBytes = dir.totalSpace,
        freeBytes = dir.usableSpace,
        modelBytes = sizes,
        missingFiles = missing,
        leftovers = findLeftovers(dir, models),
        cacheBytes = cacheDirs().sumOf { sizeOf(it) },
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
      var freed = 0L
      for (dir in cacheDirs()) {
        dir.listFiles()?.forEach { child ->
          val size = sizeOf(child)
          if (child.deleteRecursively()) freed += size
        }
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
    val referenced = models.flatMap { m -> ModelFiles.owned(m).map { it.canonicalPath } }
    return Storage.leftovers(files, referenced, clock())
  }

  /** Removes empty sub-directories (e.g. `hf_owner_repo/`) but keeps [root] itself. */
  private fun pruneEmptyDirs(root: File) {
    root.walkBottomUp().filter { it.isDirectory && it != root && it.list().isNullOrEmpty() }.forEach { it.delete() }
  }

  private fun sizeOf(f: File): Long = if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
