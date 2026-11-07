package dev.lomio.domain

import dev.lomio.data.db.MessageEntity
import dev.lomio.data.db.MessageRole
import dev.lomio.data.db.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageTreeTest {
  private var t = 0L

  private fun msg(id: String, parent: String?, role: MessageRole) =
    MessageEntity(id, "c", parent, role, id, status = MessageStatus.COMPLETE, createdAt = ++t)

  // u1 ─ a1
  //    └ a2 (regenerated) ─ u2 ─ a3
  //                        └ u2b (edited) ─ a4
  private val u1 = msg("u1", null, MessageRole.USER)
  private val a1 = msg("a1", "u1", MessageRole.ASSISTANT)
  private val a2 = msg("a2", "u1", MessageRole.ASSISTANT)
  private val u2 = msg("u2", "a2", MessageRole.USER)
  private val a3 = msg("a3", "u2", MessageRole.ASSISTANT)
  private val u2b = msg("u2b", "a2", MessageRole.USER)
  private val a4 = msg("a4", "u2b", MessageRole.ASSISTANT)
  private val all = listOf(u1, a1, a2, u2, a3, u2b, a4)

  private fun ids(l: List<MessageEntity>) = l.map { it.id }

  @Test
  fun pathFollowsTheLeaf() {
    assertEquals(listOf("u1", "a2", "u2", "a3"), ids(MessageTree.path(all, "a3")))
    assertEquals(listOf("u1", "a1"), ids(MessageTree.path(all, "a1")))
    // No (or a stale) leaf: newest message.
    assertEquals(listOf("u1", "a2", "u2b", "a4"), ids(MessageTree.path(all, null)))
    assertEquals(listOf("u1", "a2", "u2b", "a4"), ids(MessageTree.path(all, "deleted")))
    assertEquals(emptyList<String>(), ids(MessageTree.path(emptyList(), null)))
  }

  @Test
  fun versionsAreSiblingsOfTheSameRole() {
    val v = MessageTree.visible(all, "a4")
    assertEquals(listOf(0 to 1, 1 to 2, 1 to 2, 0 to 1), v.map { it.versionIndex to it.versionCount })
  }

  @Test
  fun switchingLandsOnTheNewestLeafOfThatBranch() {
    assertEquals("a1", MessageTree.stepVersion(all, "a2", -1))
    assertNull(MessageTree.stepVersion(all, "a1", -1))
    assertNull(MessageTree.stepVersion(all, "a2", +1))
    assertEquals("a1", MessageTree.newestLeafUnder(all, "a1"))
    assertEquals("a4", MessageTree.newestLeafUnder(all, "a2"))
    assertEquals("a3", MessageTree.newestLeafUnder(all, "u2"))
  }

  @Test
  fun deletingPicksAnotherVersionOrTheParent() {
    assertEquals(setOf("a2", "u2", "a3", "u2b", "a4"), MessageTree.subtree(all, "a2"))
    // Delete the current reply version: the other version is shown.
    assertEquals("a1", MessageTree.leafAfterDelete(all, "a2"))
    // Delete the only reply: back to its question.
    assertEquals("u2", MessageTree.leafAfterDelete(all, "a3"))
    // Delete an edited question: the other version and its newest leaf.
    assertEquals("a3", MessageTree.leafAfterDelete(all, "u2b"))
    assertNull(MessageTree.leafAfterDelete(listOf(u1), "u1"))
  }

  @Test
  fun cyclesDoNotHang() {
    val x = msg("x", "y", MessageRole.USER)
    val y = msg("y", "x", MessageRole.ASSISTANT)
    assertEquals(2, MessageTree.path(listOf(x, y), "y").size)
    assertEquals(setOf("x", "y"), MessageTree.subtree(listOf(x, y), "x"))
  }
}
