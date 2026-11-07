package dev.lomio.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lomio.data.db.LomioDatabase
import dev.lomio.data.db.MessageRole
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
  fun truncateAndCascadeDelete() = runTest {
    val c = chats.create(null)
    chats.addMessage(c.id, MessageRole.USER, "one")
    val two = chats.addMessage(c.id, MessageRole.ASSISTANT, "two")
    chats.addMessage(c.id, MessageRole.USER, "three")
    chats.truncateFrom(c.id, two.id)
    assertEquals(listOf("one"), chats.messages(c.id).map { it.content })

    chats.delete(c.id)
    assertTrue(chats.messages(c.id).isEmpty())
    assertNull(chats.conversation(c.id))
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
