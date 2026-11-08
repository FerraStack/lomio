package dev.lomio.data.transfer

import androidx.room.withTransaction
import dev.lomio.data.Attachments
import dev.lomio.data.StatsCodec
import dev.lomio.data.db.ConversationEntity
import dev.lomio.data.db.LomioDatabase
import dev.lomio.data.db.MessageEntity
import dev.lomio.data.db.MessageRole
import dev.lomio.data.db.MessageStatus
import dev.lomio.domain.MessageTree
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The JSON export format. Versioned so a future app can still read it; everything a chat needs
 * to come back identical (all message versions, the shown branch, per-chat settings) is in it.
 */
@Serializable
data class ArchiveFile(
  val format: String = FORMAT,
  val version: Int = 1,
  val exportedAt: Long,
  val app: String = "Lomio",
  val conversations: List<ArchiveConversation>,
) {
  companion object {
    const val FORMAT = "lomio-chats"
  }
}

@Serializable
data class ArchiveConversation(
  val id: String,
  val title: String,
  val pinned: Boolean = false,
  val createdAt: Long,
  val updatedAt: Long,
  val systemPrompt: String? = null,
  /** Per-chat sampler overrides, as stored (GenerationSettings JSON). */
  val generationJson: String? = null,
  val leafId: String? = null,
  val messages: List<ArchiveMessage>,
)

@Serializable
data class ArchiveMessage(
  val id: String,
  val parentId: String? = null,
  val role: String,
  val content: String,
  val reasoning: String? = null,
  val status: String = "COMPLETE",
  val model: String? = null,
  @SerialName("stats") val statsJson: String? = null,
  val createdAt: Long,
)

data class ImportResult(val imported: Int, val skipped: Int)

class ArchiveException(message: String) : Exception(message)

/** Export and import of chats (RESEARCH.md §9.3 "Data"). */
class ChatArchive(private val db: LomioDatabase, private val clock: () -> Long = System::currentTimeMillis) {
  companion object {
    /** User-facing errors; the UI shows them translated (see TransferViewModel). */
    const val NOT_LOMIO = "This isn't a Lomio chat export."
    const val NEWER_VERSION = "This export is from a newer Lomio; update the app first."
  }

  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
  }

  private suspend fun conversations(ids: Collection<String>?): List<ConversationEntity> {
    val all = db.conversations().all()
    return if (ids == null) all else all.filter { it.id in ids }
  }

  suspend fun exportJson(ids: Collection<String>? = null): String {
    val convs = conversations(ids).map { c ->
      val msgs = db.messages().list(c.id)
      ArchiveConversation(
        id = c.id,
        title = c.title,
        pinned = c.pinned,
        createdAt = c.createdAt,
        updatedAt = c.updatedAt,
        systemPrompt = c.systemPrompt,
        generationJson = c.genOverrideJson,
        leafId = c.leafId,
        messages =
          msgs.map {
            ArchiveMessage(it.id, it.parentId, it.role.wire, it.content, it.reasoning, it.status.name, it.modelId, it.statsJson, it.createdAt)
          },
      )
    }
    return json.encodeToString(ArchiveFile.serializer(), ArchiveFile(exportedAt = clock(), conversations = convs))
  }

  /** Readable Markdown of the shown branch of each chat. Not importable (use JSON for that). */
  suspend fun exportMarkdown(ids: Collection<String>? = null, zone: ZoneId = ZoneId.systemDefault()): String {
    val date = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.US).withZone(zone)
    val models = db.models().all().associate { it.id to it.displayName }
    return buildString {
      conversations(ids).forEachIndexed { i, c ->
        if (i > 0) append("\n---\n\n")
        append("# ").append(c.title.trim()).append("\n\n")
        append("_Exported from Lomio · ").append(date.format(Instant.ofEpochMilli(clock()))).append("_\n\n")
        c.systemPrompt?.takeIf { it.isNotBlank() }?.let { append("> **System:** ").append(it.trim().replace("\n", "\n> ")).append("\n\n") }
        MessageTree.path(db.messages().list(c.id), c.leafId).forEach { m ->
          when (m.role) {
            MessageRole.USER -> append("**You**\n\n")
            MessageRole.ASSISTANT -> {
              append("**Assistant**")
              m.modelId?.let(models::get)?.let { append(" · ").append(it) }
              append("\n\n")
            }
            MessageRole.SYSTEM -> append("**System**\n\n")
          }
          append(m.content.trim()).append("\n\n")
          // Images stay on the device; the export is text only.
          Attachments.decode(m.attachments).size.takeIf { it > 0 }?.let { n ->
            append("_(").append(if (n == 1) "1 image" else "$n images").append(" not included)_\n\n")
          }
        }
      }
    }.trimEnd() + "\n"
  }

  /**
   * Imports a JSON export. Chats whose id already exists are skipped, so importing the same
   * file twice is harmless. Model links are dropped when that model isn't on this device.
   */
  suspend fun importJson(text: String): ImportResult {
    val file =
      runCatching { json.decodeFromString(ArchiveFile.serializer(), text) }
        .getOrElse { throw ArchiveException(NOT_LOMIO) }
    if (file.format != ArchiveFile.FORMAT) throw ArchiveException(NOT_LOMIO)
    if (file.version > 1) throw ArchiveException(NEWER_VERSION)
    var imported = 0
    var skipped = 0
    db.withTransaction {
      val models = db.models().all().map { it.id }.toSet()
      for (c in file.conversations) {
        if (db.conversations().get(c.id) != null) {
          skipped++
          continue
        }
        db.conversations().insert(
          ConversationEntity(
            id = c.id,
            title = c.title.ifBlank { "Imported chat" },
            modelId = null,
            systemPrompt = c.systemPrompt,
            genOverrideJson = c.generationJson,
            pinned = c.pinned,
            createdAt = c.createdAt,
            updatedAt = c.updatedAt,
            leafId = c.leafId,
          )
        )
        c.messages.forEach { m ->
          db.messages().upsert(
            MessageEntity(
              id = m.id,
              conversationId = c.id,
              parentId = m.parentId,
              role = MessageRole.entries.firstOrNull { it.wire == m.role } ?: MessageRole.USER,
              content = m.content,
              reasoning = m.reasoning,
              status =
                runCatching { MessageStatus.valueOf(m.status) }.getOrDefault(MessageStatus.COMPLETE)
                  .let { if (it == MessageStatus.STREAMING) MessageStatus.STOPPED else it },
              modelId = m.model?.takeIf { it in models },
              statsJson = m.statsJson?.takeIf { StatsCodec.decode(it) != null },
              createdAt = m.createdAt,
            )
          )
        }
        imported++
      }
    }
    return ImportResult(imported, skipped)
  }
}
