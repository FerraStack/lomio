package dev.lomio.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lomio.data.db.LomioDatabase
import dev.lomio.data.db.MessageRole
import dev.lomio.data.transfer.ArchiveException
import dev.lomio.data.transfer.ChatArchive
import dev.lomio.domain.GenerationSettings
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatArchiveTest {
  private lateinit var source: LomioDatabase
  private lateinit var target: LomioDatabase

  @Before
  fun setUp() {
    source = LomioDatabase.inMemory(ApplicationProvider.getApplicationContext())
    target = LomioDatabase.inMemory(ApplicationProvider.getApplicationContext())
  }

  @After
  fun tearDown() {
    source.close()
    target.close()
  }

  /** A chat with a regenerated reply (two versions), a system prompt and sampler overrides. */
  private suspend fun seed(): String {
    val chats = ChatRepository(source)
    val c = chats.create(null, "Kyoto trip")
    chats.setSystemPrompt(c.id, "Be brief.")
    chats.setGenerationOverride(c.id, GenerationSettings(temperature = 0.3f))
    val q = chats.addMessage(c.id, MessageRole.USER, "Plan day one")
    chats.addMessage(c.id, MessageRole.ASSISTANT, "Fushimi Inari at dawn.")
    chats.addMessage(c.id, MessageRole.ASSISTANT, "Arashiyama, then Gion.", parent = ChatRepository.Parent.Of(q.id))
    return c.id
  }

  @Test
  fun jsonRoundTripKeepsVersionsBranchAndSettings() = runTest {
    val id = seed()
    val json = ChatArchive(source).exportJson()
    assertEquals(1 to 0, ChatArchive(target).importJson(json).let { it.imported to it.skipped })

    val back = ChatRepository(target)
    assertEquals(listOf("Plan day one", "Arashiyama, then Gion."), back.messages(id).map { it.content })
    assertEquals(3, back.allMessages(id).size)
    val shown = back.observeMessages(id).first()
    assertEquals(1 to 2, shown[1].versionIndex to shown[1].versionCount)
    val conv = back.conversation(id)!!
    assertEquals("Kyoto trip", conv.title)
    assertEquals("Be brief.", conv.systemPrompt)
    assertTrue(conv.genOverrideJson!!.contains("0.3"))
    // Search works on imported messages.
    assertEquals(1, back.searchMessages("Arashiyama").first().size)

    // Importing the same file again changes nothing.
    assertEquals(0 to 1, ChatArchive(target).importJson(json).let { it.imported to it.skipped })
    assertEquals(3, back.allMessages(id).size)
  }

  @Test
  fun markdownShowsTheVisibleBranch() = runTest {
    seed()
    val md = ChatArchive(source, clock = { 1_794_000_000_000L }).exportMarkdown(zone = ZoneOffset.UTC)
    assertTrue(md, md.startsWith("# Kyoto trip\n\n_Exported from Lomio · "))
    assertTrue(md, md.contains("> **System:** Be brief.\n\n**You**\n\nPlan day one\n\n**Assistant**\n\nArashiyama, then Gion.\n"))
    assertTrue("hidden version leaked", !md.contains("Fushimi"))
  }

  @Test
  fun rejectsFilesThatAreNotExports() = runTest {
    for (bad in listOf("not json", """{"format":"other","exportedAt":1,"conversations":[]}""", """{"format":"lomio-chats","version":9,"exportedAt":1,"conversations":[]}""")) {
      try {
        ChatArchive(target).importJson(bad)
        fail("accepted: $bad")
      } catch (e: ArchiveException) {
        assertTrue(e.message!!.isNotBlank())
      }
    }
  }

  @Test
  fun unknownModelsAreDroppedAndStreamingBecomesStopped() = runTest {
    val json =
      """{"format":"lomio-chats","version":1,"exportedAt":1,"conversations":[{"id":"c9","title":"x","createdAt":1,"updatedAt":2,
        "leafId":"m2","messages":[{"id":"m1","role":"user","content":"hi","createdAt":1},
        {"id":"m2","parentId":"m1","role":"assistant","content":"partial","status":"STREAMING","model":"gone","createdAt":2}]}]}"""
    ChatArchive(target).importJson(json)
    val m2 = ChatRepository(target).message("m2")!!
    assertNull(m2.modelId)
    assertEquals(dev.lomio.data.db.MessageStatus.STOPPED, m2.status)
  }
}
