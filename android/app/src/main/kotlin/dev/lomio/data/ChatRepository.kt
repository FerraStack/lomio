package dev.lomio.data

import dev.lomio.data.db.ConversationEntity
import dev.lomio.data.db.ConversationSummary
import dev.lomio.data.db.LomioDatabase
import dev.lomio.data.db.MessageEntity
import dev.lomio.data.db.MessageRole
import dev.lomio.data.db.MessageStatus
import dev.lomio.domain.GenerationSettings
import dev.lomio.domain.HistoryGrouping
import dev.lomio.domain.SettingsJson
import dev.lomio.domain.UiMessage
import dev.lomio.llama.GenerationStats
import dev.lomio.llama.StopReason
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
private data class StatsDto(
  val promptTokens: Int,
  val cachedTokens: Int,
  val generatedTokens: Int,
  val ttftMs: Long,
  val ppTps: Double,
  val tgTps: Double,
  val stopReason: String,
)

object StatsCodec {
  fun encode(s: GenerationStats): String =
    SettingsJson.json.encodeToString(
      StatsDto(
        s.promptTokens,
        s.cachedTokens,
        s.generatedTokens,
        s.timeToFirstTokenMs,
        s.promptTokensPerSec,
        s.genTokensPerSec,
        s.stopReason.name,
      )
    )

  fun decode(json: String?): GenerationStats? =
    json?.let {
      runCatching {
          val d = SettingsJson.json.decodeFromString<StatsDto>(it)
          GenerationStats(
            d.promptTokens,
            d.cachedTokens,
            d.generatedTokens,
            d.ttftMs,
            d.ppTps,
            d.tgTps,
            runCatching { StopReason.valueOf(d.stopReason) }.getOrDefault(StopReason.EOS),
          )
        }
        .getOrNull()
    }
}

fun MessageEntity.toUi() =
  UiMessage(
    id = id,
    role = role,
    content = content,
    reasoning = reasoning,
    status = status,
    stats = StatsCodec.decode(statsJson),
    createdAt = createdAt,
  )

class ChatRepository(private val db: LomioDatabase, private val clock: () -> Long = System::currentTimeMillis) {
  private val conversations = db.conversations()
  private val messages = db.messages()
  private val lastTimestamp = AtomicLong(0)

  /** Strictly increasing timestamps so message order (and truncation) is unambiguous. */
  fun now(): Long = lastTimestamp.updateAndGet { prev -> maxOf(prev + 1, clock()) }

  fun observeHistory(query: String): Flow<List<ConversationSummary>> {
    val fts = HistoryGrouping.ftsQuery(query)
    return if (query.isBlank() || fts.isBlank()) conversations.observeSummaries()
    else conversations.search(query.trim(), fts)
  }

  fun observeConversation(id: String) = conversations.observe(id)

  suspend fun conversation(id: String) = conversations.get(id)

  fun observeMessages(conversationId: String): Flow<List<UiMessage>> =
    messages.observe(conversationId).map { list -> list.map { it.toUi() } }

  suspend fun messages(conversationId: String): List<MessageEntity> = messages.list(conversationId)

  suspend fun message(id: String) = messages.get(id)

  suspend fun create(modelId: String?, title: String = "New chat"): ConversationEntity {
    val t = now()
    val c = ConversationEntity(UUID.randomUUID().toString(), title, modelId, createdAt = t, updatedAt = t)
    conversations.insert(c)
    return c
  }

  suspend fun rename(id: String, title: String) = conversations.rename(id, title.trim(), now())

  suspend fun setPinned(id: String, pinned: Boolean) = conversations.setPinned(id, pinned)

  suspend fun setSystemPrompt(id: String, prompt: String?) =
    conversations.setSystemPrompt(id, prompt?.takeIf { it.isNotBlank() })

  suspend fun setGenerationOverride(id: String, settings: GenerationSettings?) =
    conversations.setGenOverride(id, SettingsJson.encodeGen(settings))

  suspend fun delete(id: String) = conversations.delete(id)

  suspend fun deleteAll() = conversations.deleteAll()

  suspend fun addMessage(
    conversationId: String,
    role: MessageRole,
    content: String,
    status: MessageStatus = MessageStatus.COMPLETE,
    modelId: String? = null,
    parentId: String? = null,
  ): MessageEntity {
    val m =
      MessageEntity(
        id = UUID.randomUUID().toString(),
        conversationId = conversationId,
        parentId = parentId,
        role = role,
        content = content,
        status = status,
        modelId = modelId,
        createdAt = now(),
      )
    messages.upsert(m)
    conversations.touch(conversationId, m.createdAt, modelId)
    return m
  }

  suspend fun finishMessage(
    id: String,
    content: String,
    reasoning: String?,
    status: MessageStatus,
    stats: GenerationStats?,
  ) = messages.finish(id, content, reasoning?.takeIf { it.isNotEmpty() }, status, stats?.let(StatsCodec::encode))

  /** Deletes [fromMessageId] and every later message of the conversation. */
  suspend fun truncateFrom(conversationId: String, fromMessageId: String) {
    val m = messages.get(fromMessageId) ?: return
    messages.truncateFrom(conversationId, m.createdAt)
  }

  suspend fun recoverInterrupted(): Int = messages.markInterrupted()
}
