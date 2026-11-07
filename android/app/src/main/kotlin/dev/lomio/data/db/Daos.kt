package dev.lomio.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
  @Query("SELECT * FROM model ORDER BY COALESCE(last_used_at, created_at) DESC")
  fun observeAll(): Flow<List<ModelEntity>>

  @Query("SELECT * FROM model WHERE id = :id") suspend fun get(id: String): ModelEntity?

  @Query("SELECT * FROM model WHERE id = :id") fun observe(id: String): Flow<ModelEntity?>

  @Query("SELECT * FROM model WHERE hf_repo = :repo AND hf_file = :file")
  suspend fun findByHf(repo: String, file: String): ModelEntity?

  @Upsert suspend fun upsert(model: ModelEntity)

  @Query("UPDATE model SET state = :state, bytes_done = :bytesDone, error_message = :error WHERE id = :id")
  suspend fun updateProgress(id: String, state: ModelState, bytesDone: Long, error: String? = null)

  @Query("UPDATE model SET last_used_at = :at WHERE id = :id")
  suspend fun touch(id: String, at: Long)

  @Query("UPDATE model SET display_name = :name WHERE id = :id")
  suspend fun rename(id: String, name: String)

  @Query("DELETE FROM model WHERE id = :id") suspend fun delete(id: String)

  @Query("SELECT * FROM model_settings WHERE model_id = :modelId")
  suspend fun settings(modelId: String): ModelSettingsEntity?

  @Query("SELECT * FROM model_settings WHERE model_id = :modelId")
  fun observeSettings(modelId: String): Flow<ModelSettingsEntity?>

  @Upsert suspend fun upsertSettings(settings: ModelSettingsEntity)
}

/** Drawer row: conversation plus the model name, without loading messages. */
data class ConversationSummary(
  val id: String,
  val title: String,
  val pinned: Boolean,
  val updatedAt: Long,
  val modelName: String?,
)

@Dao
interface ConversationDao {
  @Query(
    """
    SELECT c.id, c.title, c.pinned, c.updated_at AS updatedAt, m.display_name AS modelName
    FROM conversation c LEFT JOIN model m ON m.id = c.model_id
    ORDER BY c.pinned DESC, c.updated_at DESC
    """
  )
  fun observeSummaries(): Flow<List<ConversationSummary>>

  @Query(
    """
    SELECT c.id, c.title, c.pinned, c.updated_at AS updatedAt, m.display_name AS modelName
    FROM conversation c LEFT JOIN model m ON m.id = c.model_id
    WHERE c.title LIKE '%' || :query || '%'
       OR c.id IN (
         SELECT msg.conversation_id FROM message msg
         JOIN message_fts ON msg.rowid = message_fts.rowid
         WHERE message_fts MATCH :ftsQuery
       )
    ORDER BY c.pinned DESC, c.updated_at DESC
    """
  )
  fun search(query: String, ftsQuery: String): Flow<List<ConversationSummary>>

  @Query("SELECT * FROM conversation WHERE id = :id") suspend fun get(id: String): ConversationEntity?

  @Query("SELECT * FROM conversation WHERE id = :id")
  fun observe(id: String): Flow<ConversationEntity?>

  @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(conversation: ConversationEntity)

  @Upsert suspend fun upsert(conversation: ConversationEntity)

  @Query("UPDATE conversation SET title = :title, updated_at = :at WHERE id = :id")
  suspend fun rename(id: String, title: String, at: Long)

  @Query("UPDATE conversation SET pinned = :pinned WHERE id = :id")
  suspend fun setPinned(id: String, pinned: Boolean)

  @Query("UPDATE conversation SET updated_at = :at, model_id = COALESCE(:modelId, model_id) WHERE id = :id")
  suspend fun touch(id: String, at: Long, modelId: String?)

  @Query("UPDATE conversation SET system_prompt = :prompt WHERE id = :id")
  suspend fun setSystemPrompt(id: String, prompt: String?)

  @Query("UPDATE conversation SET gen_override_json = :json WHERE id = :id")
  suspend fun setGenOverride(id: String, json: String?)

  @Query("DELETE FROM conversation WHERE id = :id") suspend fun delete(id: String)

  @Query("DELETE FROM conversation") suspend fun deleteAll()
}

@Dao
interface MessageDao {
  @Query("SELECT * FROM message WHERE conversation_id = :conversationId ORDER BY created_at, rowid")
  fun observe(conversationId: String): Flow<List<MessageEntity>>

  @Query("SELECT * FROM message WHERE conversation_id = :conversationId ORDER BY created_at, rowid")
  suspend fun list(conversationId: String): List<MessageEntity>

  @Query("SELECT * FROM message WHERE id = :id") suspend fun get(id: String): MessageEntity?

  @Upsert suspend fun upsert(message: MessageEntity)

  @Query(
    "UPDATE message SET content = :content, reasoning = :reasoning, status = :status, stats_json = :statsJson WHERE id = :id"
  )
  suspend fun finish(
    id: String,
    content: String,
    reasoning: String?,
    status: MessageStatus,
    statsJson: String?,
  )

  @Query("DELETE FROM message WHERE id = :id") suspend fun delete(id: String)

  /** Removes [fromCreatedAt] and everything after it (edit-and-resend / regenerate). */
  @Query("DELETE FROM message WHERE conversation_id = :conversationId AND created_at >= :fromCreatedAt")
  suspend fun truncateFrom(conversationId: String, fromCreatedAt: Long)

  /** Messages left in STREAMING state by a crash or process death. */
  @Query("UPDATE message SET status = 'STOPPED' WHERE status = 'STREAMING'")
  suspend fun markInterrupted(): Int

  @Transaction
  suspend fun replaceTail(conversationId: String, fromCreatedAt: Long, newMessages: List<MessageEntity>) {
    truncateFrom(conversationId, fromCreatedAt)
    newMessages.forEach { upsert(it) }
  }
}
