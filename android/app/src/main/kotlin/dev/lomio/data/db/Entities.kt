package dev.lomio.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ModelSource { HF, LOCAL }

enum class ModelState { REMOTE, DOWNLOADING, PAUSED, READY, ERROR }

/** A model the app knows about: downloaded, downloading, or imported. (RESEARCH.md §8.2) */
@Entity(tableName = "model", indices = [Index(value = ["hf_repo", "hf_file"], unique = true)])
data class ModelEntity(
  @PrimaryKey val id: String,
  val source: ModelSource,
  @ColumnInfo(name = "hf_repo") val hfRepo: String? = null,
  @ColumnInfo(name = "hf_file") val hfFile: String? = null,
  @ColumnInfo(name = "hf_revision") val hfRevision: String? = null,
  @ColumnInfo(name = "display_name") val displayName: String,
  @ColumnInfo(name = "file_path") val filePath: String? = null,
  @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
  val sha256: String? = null,
  val state: ModelState,
  @ColumnInfo(name = "bytes_done") val bytesDone: Long = 0,
  @ColumnInfo(name = "error_message") val errorMessage: String? = null,
  // Cached GGUF header metadata.
  val arch: String? = null,
  @ColumnInfo(name = "n_params") val nParams: Long? = null,
  @ColumnInfo(name = "n_layers") val nLayers: Int? = null,
  @ColumnInfo(name = "n_ctx_train") val nCtxTrain: Int? = null,
  @ColumnInfo(name = "n_embd") val nEmbd: Int? = null,
  @ColumnInfo(name = "n_head") val nHead: Int? = null,
  @ColumnInfo(name = "n_head_kv") val nHeadKv: Int? = null,
  @ColumnInfo(name = "key_length") val keyLength: Int? = null,
  @ColumnInfo(name = "value_length") val valueLength: Int? = null,
  val quant: String? = null,
  @ColumnInfo(name = "chat_template") val chatTemplate: String? = null,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "last_used_at") val lastUsedAt: Long? = null,
)

/**
 * Per-model defaults. JSON columns keep the schema migration-friendly while the settings set
 * is still moving; see [dev.lomio.domain.GenerationSettings] and [dev.lomio.domain.LoadSettings].
 */
@Entity(
  tableName = "model_settings",
  foreignKeys =
    [
      ForeignKey(
        entity = ModelEntity::class,
        parentColumns = ["id"],
        childColumns = ["model_id"],
        onDelete = ForeignKey.CASCADE,
      )
    ],
)
data class ModelSettingsEntity(
  @PrimaryKey @ColumnInfo(name = "model_id") val modelId: String,
  @ColumnInfo(name = "load_json") val loadJson: String? = null,
  @ColumnInfo(name = "gen_json") val genJson: String? = null,
  @ColumnInfo(name = "system_prompt") val systemPrompt: String? = null,
)

@Entity(
  tableName = "conversation",
  indices = [Index(value = ["pinned", "updated_at"]), Index("model_id")],
  foreignKeys =
    [
      ForeignKey(
        entity = ModelEntity::class,
        parentColumns = ["id"],
        childColumns = ["model_id"],
        onDelete = ForeignKey.SET_NULL,
      )
    ],
)
data class ConversationEntity(
  @PrimaryKey val id: String,
  val title: String,
  @ColumnInfo(name = "model_id") val modelId: String? = null,
  @ColumnInfo(name = "system_prompt") val systemPrompt: String? = null,
  @ColumnInfo(name = "gen_override_json") val genOverrideJson: String? = null,
  val pinned: Boolean = false,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

enum class MessageRole(val wire: String) {
  SYSTEM("system"),
  USER("user"),
  ASSISTANT("assistant"),
}

enum class MessageStatus { COMPLETE, STREAMING, STOPPED, ERROR }

@Entity(
  tableName = "message",
  indices = [Index(value = ["conversation_id", "created_at"])],
  foreignKeys =
    [
      ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversation_id"],
        onDelete = ForeignKey.CASCADE,
      )
    ],
)
data class MessageEntity(
  @PrimaryKey val id: String,
  @ColumnInfo(name = "conversation_id") val conversationId: String,
  @ColumnInfo(name = "parent_id") val parentId: String? = null,
  val role: MessageRole,
  val content: String,
  val reasoning: String? = null,
  val status: MessageStatus,
  @ColumnInfo(name = "model_id") val modelId: String? = null,
  @ColumnInfo(name = "stats_json") val statsJson: String? = null,
  @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Full-text index over message content for drawer search (external content table). */
@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "message_fts")
data class MessageFts(val content: String)
