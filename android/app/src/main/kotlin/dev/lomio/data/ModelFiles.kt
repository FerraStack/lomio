package dev.lomio.data

import dev.lomio.data.db.ModelEntity
import dev.lomio.domain.SettingsJson
import java.io.File
import kotlinx.serialization.Serializable

/** One part of a split GGUF model. [path] is the file's path in the Hugging Face repo. */
@Serializable data class Shard(val path: String, val size: Long, val sha256: String? = null)

/** The files a model owns on disk: weights (one file, or every part of a split model) and projector. */
object ModelFiles {
  fun encodeShards(shards: List<Shard>): String? =
    shards.takeIf { it.size > 1 }?.let { SettingsJson.json.encodeToString(it) }

  fun decodeShards(json: String?): List<Shard> =
    json?.let { runCatching { SettingsJson.json.decodeFromString<List<Shard>>(it) }.getOrNull() }.orEmpty()

  /**
   * The weights files: just [ModelEntity.filePath], or every part of a split model. The parts sit
   * next to the first one, keeping their gguf-split names, which is how llama.cpp finds them
   * when it loads the first part.
   */
  fun weights(m: ModelEntity): List<File> {
    val first = m.filePath?.let(::File) ?: return emptyList()
    val shards = decodeShards(m.shards)
    if (shards.size < 2) return listOf(first)
    return shards.map { File(first.parentFile, it.path.substringAfterLast('/')) }
  }

  /** Every file the model owns: its weights plus its vision projector. */
  fun owned(m: ModelEntity): List<File> = weights(m) + listOfNotNull(m.mmprojPath?.let(::File))
}

/** Number of files the weights are split into (1 for an ordinary model). */
val ModelEntity.partCount: Int
  get() = ModelFiles.decodeShards(shards).size.coerceAtLeast(1)
