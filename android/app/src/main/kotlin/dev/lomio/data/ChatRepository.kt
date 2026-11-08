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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import androidx.room.withTransaction
import dev.lomio.data.db.SearchHit
import dev.lomio.domain.MessageTree
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

fun MessageEntity.toUi(versionIndex: Int = 0, versionCount: Int = 1, attachmentPath: (String) -> String = { it }) =
  UiMessage(
    id = id,
    role = role,
    content = content,
    reasoning = reasoning,
    status = status,
    stats = StatsCodec.decode(statsJson),
    createdAt = createdAt,
    versionIndex = versionIndex,
    versionCount = versionCount,
    images = Attachments.decode(attachments).map(attachmentPath),
  )

/**
 * @param attachmentPath maps an attachment file name (as stored in the DB) to its absolute path.
 */
class ChatRepository(
  private val db: LomioDatabase,
  val attachmentPath: (String) -> String = { it },
  private val clock: () -> Long = System::currentTimeMillis,
) {
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

  /** The visible branch of the conversation, with version numbers. */
  fun observeMessages(conversationId: String): Flow<List<UiMessage>> =
    combine(messages.observe(conversationId), conversations.observe(conversationId)) { list, conv ->
      MessageTree.visible(list, conv?.leafId).map { it.message.toUi(it.versionIndex, it.versionCount, attachmentPath) }
    }

  /** Every message of the conversation, all branches included. */
  suspend fun allMessages(conversationId: String): List<MessageEntity> = messages.list(conversationId)

  /** The visible branch, root first: what the model sees. */
  suspend fun messages(conversationId: String): List<MessageEntity> =
    MessageTree.path(messages.list(conversationId), conversations.get(conversationId)?.leafId)

  /** The branch from the root down to [messageId]. */
  suspend fun pathTo(conversationId: String, messageId: String): List<MessageEntity> =
    MessageTree.path(messages.list(conversationId), messageId)

  /** Shows the branch through [messageId], ending at its newest leaf. */
  suspend fun selectBranch(conversationId: String, messageId: String) {
    val all = messages.list(conversationId)
    if (all.none { it.id == messageId }) return
    conversations.setLeaf(conversationId, MessageTree.newestLeafUnder(all, messageId))
  }

  /** Switches the version of [messageId] by [step] (‹ = -1, › = +1). */
  suspend fun stepVersion(conversationId: String, messageId: String, step: Int) {
    val all = messages.list(conversationId)
    val target = MessageTree.stepVersion(all, messageId, step) ?: return
    conversations.setLeaf(conversationId, MessageTree.newestLeafUnder(all, target))
  }

  /** Deletes [messageId] and the replies below it; another version (or the parent) is shown. */
  suspend fun deleteMessage(conversationId: String, messageId: String) =
    db.withTransaction {
      val all = messages.list(conversationId)
      val leaf = MessageTree.leafAfterDelete(all, messageId)
      messages.deleteAll(MessageTree.subtree(all, messageId))
      conversations.setLeaf(conversationId, leaf)
    }

  fun searchMessages(query: String): Flow<List<SearchHit>> {
    val fts = HistoryGrouping.ftsQuery(query)
    return if (fts.isBlank()) flowOf(emptyList()) else messages.searchMessages(fts)
  }

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

  /** Where [addMessage] attaches a message in the conversation tree. */
  sealed interface Parent {
    /** At the end of the visible branch (a normal new message). */
    data object Leaf : Parent

    /** Under a given message, or at the root for null: adds a new version next to its siblings. */
    data class Of(val id: String?) : Parent
  }

  /** Adds a message under [parent] and makes it the shown leaf. */
  suspend fun addMessage(
    conversationId: String,
    role: MessageRole,
    content: String,
    status: MessageStatus = MessageStatus.COMPLETE,
    modelId: String? = null,
    parent: Parent = Parent.Leaf,
    attachments: List<String> = emptyList(),
  ): MessageEntity = db.withTransaction {
    val parentId =
      when (parent) {
        Parent.Leaf -> messages(conversationId).lastOrNull()?.id
        is Parent.Of -> parent.id
      }
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
        attachments = Attachments.encode(attachments),
      )
    messages.upsert(m)
    conversations.touch(conversationId, m.createdAt, modelId)
    conversations.setLeaf(conversationId, m.id)
    m
  }

  suspend fun finishMessage(
    id: String,
    content: String,
    reasoning: String?,
    status: MessageStatus,
    stats: GenerationStats?,
  ) = messages.finish(id, content, reasoning?.takeIf { it.isNotEmpty() }, status, stats?.let(StatsCodec::encode))

  suspend fun recoverInterrupted(): Int = messages.markInterrupted()

  /** File names of every attachment still referenced by a message. */
  suspend fun referencedAttachments(): Set<String> =
    messages.allAttachments().flatMap { Attachments.decode(it) }.toSet()
}
