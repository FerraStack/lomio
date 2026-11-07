package dev.lomio.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lomio.data.db.LomioDatabase
import dev.lomio.data.db.MessageRole
import dev.lomio.data.db.HIT_END
import dev.lomio.data.db.HIT_START
import dev.lomio.data.db.MessageStatus
import dev.lomio.data.db.ModelEntity
import dev.lomio.data.db.ModelSource
import dev.lomio.data.db.ModelState
import dev.lomio.domain.CrashGuard
import dev.lomio.domain.GenerationSettings
import dev.lomio.domain.SettingsJson
import dev.lomio.llama.GenerationStats
import dev.lomio.llama.StopReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
  private lateinit var db: LomioDatabase
  private lateinit var chats: ChatRepository
  private var clock = 1_000L

  @Before
  fun setUp() {
    db = LomioDatabase.inMemory(ApplicationProvider.getApplicationContext())
    chats = ChatRepository(db) { clock }
  }

  @After fun tearDown() = db.close()

  private fun model(id: String) =
    ModelEntity(
      id = id,
      source = ModelSource.HF,
      hfRepo = "org/$id-GGUF",
      hfFile = "$id.gguf",
      displayName = id,
      sizeBytes = 100,
      state = ModelState.READY,
      createdAt = 1,
    )

  @Test
  fun conversationsAndMessagesRoundTrip() = runTest {
    db.models().upsert(model("m1"))
    val c = chats.create("m1")
    val u = chats.addMessage(c.id, MessageRole.USER, "What is Rust?")
    val a = chats.addMessage(c.id, MessageRole.ASSISTANT, "", MessageStatus.STREAMING, "m1")
    assertTrue("timestamps must be strictly increasing", a.createdAt > u.createdAt)

    val stats = GenerationStats(12, 4, 30, 250, 80.0, 15.5, StopReason.EOS)
    chats.finishMessage(a.id, "A language.", "Thinking…", MessageStatus.COMPLETE, stats)

    val msgs = chats.observeMessages(c.id).first()
    assertEquals(listOf("What is Rust?", "A language."), msgs.map { it.content })
    assertEquals("Thinking…", msgs[1].reasoning)
    assertEquals(stats, msgs[1].stats)

    val summary = chats.observeHistory("").first().single()
    assertEquals("m1", summary.modelName)
  }

  @Test
  fun searchUsesTitleAndFullText() = runTest {
    val a = chats.create(null, "Cooking ideas")
    chats.addMessage(a.id, MessageRole.USER, "how do I make risotto")
    val b = chats.create(null, "Travel")
    chats.addMessage(b.id, MessageRole.USER, "best trains in Japan")

    assertEquals(listOf(a.id), chats.observeHistory("risot").first().map { it.id })
    assertEquals(listOf(b.id), chats.observeHistory("travel").first().map { it.id })
    assertEquals(2, chats.observeHistory("  ").first().size)
  }

  @Test
  fun pinnedFirstThenRecent() = runTest {
    val old = chats.create(null, "old")
    clock = 5_000
    val recent = chats.create(null, "recent")
    chats.setPinned(old.id, true)
    assertEquals(listOf(old.id, recent.id), chats.observeHistory("").first().map { it.id })
  }

  @Test
  fun deleteMessageAndCascadeDelete() = runTest {
    val c = chats.create(null)
    chats.addMessage(c.id, MessageRole.USER, "one")
    val two = chats.addMessage(c.id, MessageRole.ASSISTANT, "two")
    chats.addMessage(c.id, MessageRole.USER, "three")
    chats.deleteMessage(c.id, two.id)
    // The reply and everything under it are gone; the question is the leaf again.
    assertEquals(listOf("one"), chats.messages(c.id).map { it.content })
    assertEquals(1, chats.allMessages(c.id).size)

    chats.delete(c.id)
    assertTrue(chats.allMessages(c.id).isEmpty())
    assertNull(chats.conversation(c.id))
  }

  @Test
  fun versionsFormABranchingThread() = runTest {
    val c = chats.create(null)
    val q = chats.addMessage(c.id, MessageRole.USER, "q")
    val a1 = chats.addMessage(c.id, MessageRole.ASSISTANT, "a1")
    // Regenerate: a second reply under the same question.
    val a2 = chats.addMessage(c.id, MessageRole.ASSISTANT, "a2", parent = ChatRepository.Parent.Of(q.id))
    chats.addMessage(c.id, MessageRole.USER, "follow-up")
    assertEquals(listOf("q", "a2", "follow-up"), chats.messages(c.id).map { it.content })
    val shown = chats.observeMessages(c.id).first()
    assertEquals(listOf(0 to 1, 1 to 2, 0 to 1), shown.map { it.versionIndex to it.versionCount })

    // Back to the first reply: its branch has no follow-up.
    chats.stepVersion(c.id, a2.id, -1)
    assertEquals(listOf("q", "a1"), chats.messages(c.id).map { it.content })
    // Forward again lands on the newest leaf of that branch.
    chats.stepVersion(c.id, a1.id, +1)
    assertEquals(listOf("q", "a2", "follow-up"), chats.messages(c.id).map { it.content })
    assertEquals(4, chats.allMessages(c.id).size)
  }

  @Test
  fun messageSearchFindsEveryVersionWithASnippet() = runTest {
    val c = chats.create(null)
    chats.rename(c.id, "Cooking")
    chats.addMessage(c.id, MessageRole.USER, "how do I make a creamy risotto at home")
    val hits = chats.searchMessages("risotto").first()
    assertEquals(1, hits.size)
    assertEquals("Cooking", hits[0].title)
    assertTrue(hits[0].snippet, hits[0].snippet.contains("${HIT_START}risotto$HIT_END"))
    assertTrue(chats.searchMessages("   ").first().isEmpty())
  }

  @Test
  fun deletingModelKeepsConversation() = runTest {
    db.models().upsert(model("m1"))
    val c = chats.create("m1")
    db.models().delete("m1")
    assertNull(chats.conversation(c.id)!!.modelId)
  }

  @Test
  fun interruptedStreamsAreRecovered() = runTest {
    val c = chats.create(null)
    val a = chats.addMessage(c.id, MessageRole.ASSISTANT, "partial", MessageStatus.STREAMING)
    assertEquals(1, chats.recoverInterrupted())
    assertEquals(MessageStatus.STOPPED, chats.message(a.id)!!.status)
  }

  @Test
  fun chatOverridesPersist() = runTest {
    val c = chats.create(null)
    chats.setGenerationOverride(c.id, GenerationSettings(temperature = 0.2f))
    chats.setSystemPrompt(c.id, "Be brief")
    val stored = chats.conversation(c.id)!!
    assertEquals(0.2f, SettingsJson.decodeGen(stored.genOverrideJson)!!.temperature)
    assertEquals("Be brief", stored.systemPrompt)
    chats.setSystemPrompt(c.id, "  ")
    assertNull(chats.conversation(c.id)!!.systemPrompt)
  }

  @Test
  fun downloadProgressUpdates() = runTest {
    db.models().upsert(model("m2").copy(state = ModelState.DOWNLOADING))
    db.models().updateProgress("m2", ModelState.PAUSED, 42)
    val m = db.models().get("m2")!!
    assertEquals(ModelState.PAUSED, m.state)
    assertEquals(42, m.bytesDone)
    assertEquals(m.id, db.models().findByHf("org/m2-GGUF", "m2.gguf")!!.id)
  }

  @Test
  fun crashGuardSurvivesUntilConsumed() {
    val prefs =
      ApplicationProvider.getApplicationContext<android.content.Context>()
        .getSharedPreferences("crash_guard_test", android.content.Context.MODE_PRIVATE)
    val guard = CrashGuard(prefs)
    guard.beginLoad("m1")
    // Simulate a process death: a new guard over the same prefs sees the marker once.
    assertEquals("m1", CrashGuard(prefs).consumeCrashedLoad())
    assertNull(CrashGuard(prefs).consumeCrashedLoad())
    guard.beginLoad("m2")
    guard.endLoad()
    assertNull(guard.consumeCrashedLoad())
  }
}
