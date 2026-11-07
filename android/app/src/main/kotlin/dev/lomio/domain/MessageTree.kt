package dev.lomio.domain

import dev.lomio.data.db.MessageEntity

/**
 * A conversation's messages form a tree through `parentId`: regenerating a reply or editing a
 * message adds a sibling instead of deleting history. The conversation's leaf picks the branch
 * that is shown and sent to the model. Pure functions; the repository feeds them all messages of
 * one conversation.
 */
object MessageTree {
  /** A message on the visible branch, with its position among its versions. */
  data class Visible(val message: MessageEntity, val versionIndex: Int, val versionCount: Int)

  private val order = compareBy<MessageEntity>({ it.createdAt }, { it.id })

  /** The newest message: the leaf when none is stored (or the stored one was deleted). */
  fun defaultLeaf(messages: List<MessageEntity>): MessageEntity? = messages.maxWithOrNull(order)

  /** Root-to-leaf path ending at [leafId]. */
  fun path(messages: List<MessageEntity>, leafId: String?): List<MessageEntity> {
    val byId = messages.associateBy { it.id }
    var cur = leafId?.let(byId::get) ?: defaultLeaf(messages) ?: return emptyList()
    val out = ArrayList<MessageEntity>()
    val seen = HashSet<String>()
    while (seen.add(cur.id)) {
      out += cur
      cur = cur.parentId?.let(byId::get) ?: break
    }
    return out.asReversed()
  }

  /** Versions of [m]: messages with the same parent and role, oldest first. */
  fun siblings(messages: List<MessageEntity>, m: MessageEntity): List<MessageEntity> =
    messages.filter { it.parentId == m.parentId && it.role == m.role }.sortedWith(order)

  /** The visible branch with version numbers for the ‹ 1/2 › switcher. */
  fun visible(messages: List<MessageEntity>, leafId: String?): List<Visible> =
    path(messages, leafId).map { m ->
      val versions = siblings(messages, m)
      Visible(m, versions.indexOfFirst { it.id == m.id }.coerceAtLeast(0), versions.size.coerceAtLeast(1))
    }

  /** Where to land when switching to [id]: follow its newest child down to a leaf. */
  fun newestLeafUnder(messages: List<MessageEntity>, id: String): String {
    val children = messages.groupBy { it.parentId }
    var cur = id
    val seen = HashSet<String>()
    while (seen.add(cur)) {
      cur = children[cur]?.maxWithOrNull(order)?.id ?: break
    }
    return cur
  }

  /** [id] and everything below it. */
  fun subtree(messages: List<MessageEntity>, id: String): Set<String> {
    val children = messages.groupBy { it.parentId }
    val out = LinkedHashSet<String>()
    val stack = ArrayDeque(listOf(id))
    while (stack.isNotEmpty()) {
      val cur = stack.removeLast()
      if (out.add(cur)) children[cur]?.forEach { stack.addLast(it.id) }
    }
    return out
  }

  /**
   * The leaf to show after deleting [id] (with its subtree): the newest remaining version of it,
   * else its parent; null when nothing is left.
   */
  fun leafAfterDelete(messages: List<MessageEntity>, id: String): String? {
    val m = messages.firstOrNull { it.id == id } ?: return defaultLeaf(messages)?.id
    val gone = subtree(messages, id)
    val rest = messages.filter { it.id !in gone }
    val sibling = siblings(rest, m).maxWithOrNull(order)
    return when {
      sibling != null -> newestLeafUnder(rest, sibling.id)
      m.parentId != null && rest.any { it.id == m.parentId } -> m.parentId
      else -> defaultLeaf(rest)?.id
    }
  }

  /** The version [step] places away from [id] (‹ = -1, › = +1), or null at either end. */
  fun stepVersion(messages: List<MessageEntity>, id: String, step: Int): String? {
    val m = messages.firstOrNull { it.id == id } ?: return null
    val versions = siblings(messages, m)
    val i = versions.indexOfFirst { it.id == id } + step
    return versions.getOrNull(i)?.id
  }
}
