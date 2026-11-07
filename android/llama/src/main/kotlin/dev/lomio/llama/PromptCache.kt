package dev.lomio.llama

import java.io.File

/**
 * On-disk prompt caches, one per chat: `<key>.lpc` (llama.cpp's seq-state file: tokens + KV)
 * plus `<key>.lpc.meta` (fingerprint, cell positions). Pure file bookkeeping; the engine does
 * the native save/restore.
 *
 * Limits: a single cache larger than [maxFileBytes] is not written, and after each save the
 * least recently used caches are deleted until the total is under [maxTotalBytes].
 */
class PromptCache(
  val dir: File,
  val maxTotalBytes: Long = 1L shl 30,
  val maxFileBytes: Long = 512L shl 20,
) {
  fun file(key: String): File = File(dir, sanitize(key) + EXT)

  fun exists(key: String): Boolean = file(key).exists() && meta(file(key)).exists()

  /** Marks a cache as just used (LRU order is by modification time). */
  fun touch(key: String) {
    val now = System.currentTimeMillis()
    file(key).setLastModified(now)
    meta(file(key)).setLastModified(now)
  }

  fun delete(key: String) {
    val f = file(key)
    f.delete()
    meta(f).delete()
  }

  /** Total bytes of all caches. */
  fun totalBytes(): Long = entries().sumOf { it.length() + meta(it).length() }

  /** Deletes the least recently used caches (never [keep]) until the total fits. */
  fun trim(keep: String? = null) {
    val keepFile = keep?.let { file(it).name }
    var total = totalBytes()
    for (f in entries().sortedBy { it.lastModified() }) {
      if (total <= maxTotalBytes) break
      if (f.name == keepFile) continue
      total -= f.length() + meta(f).length()
      f.delete()
      meta(f).delete()
    }
    // Leftovers of interrupted writes.
    dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
  }

  /** Deletes caches whose key is not in [keys] (e.g. of deleted chats). */
  fun retainOnly(keys: Set<String>) {
    val names = keys.map { sanitize(it) + EXT }.toSet()
    entries().filter { it.name !in names }.forEach {
      it.delete()
      meta(it).delete()
    }
  }

  private fun entries(): List<File> = dir.listFiles { f -> f.name.endsWith(EXT) }?.toList().orEmpty()

  private fun meta(f: File) = File(f.path + ".meta")

  companion object {
    const val EXT = ".lpc"

    fun sanitize(key: String): String = key.replace(Regex("[^A-Za-z0-9_-]"), "_").take(100)
  }
}
