package dev.lomio.engine

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lomio.data.ChatRepository
import dev.lomio.data.GgufReader
import dev.lomio.data.ModelRepository
import dev.lomio.data.db.LomioDatabase
import dev.lomio.data.db.MessageRole
import dev.lomio.data.db.MessageStatus
import dev.lomio.data.db.ModelEntity
import dev.lomio.data.db.ModelSource
import dev.lomio.data.db.ModelState
import kotlinx.coroutines.runBlocking
import dev.lomio.data.download.DownloadScheduler
import dev.lomio.data.settings.SettingsRepository
import dev.lomio.domain.ContextWindow
import dev.lomio.llama.EngineState
import dev.lomio.llama.StopReason
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatServiceTest {
  @get:Rule val tmp = TemporaryFolder()

  private lateinit var db: LomioDatabase
  private lateinit var chats: ChatRepository
  private val engine = FakeEngine()
  private var activeModel: String? = "m1"

  @Before
  fun setUp() {
    db = LomioDatabase.inMemory(ApplicationProvider.getApplicationContext())
    chats = ChatRepository(db)
    // conversation.model_id is a foreign key: the model must exist.
    runBlocking {
      db.models().upsert(
        ModelEntity(id = "m1", source = ModelSource.LOCAL, displayName = "m1", sizeBytes = 1, state = ModelState.READY, createdAt = 1)
      )
    }
  }

  @After fun tearDown() = db.close()

  private fun TestScope.service(): ChatService {
    val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    val settings =
      SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "s.preferences_pb") })
    val models = ModelRepository(ctx, db, DownloadScheduler(ctx), GgufReader { null })
    return ChatService(engine, { activeModel }, chats, models, settings, backgroundScope)
  }

  /** Waits in real time: Room runs on its own threads, so virtual-time timeouts would misfire. */
  private suspend fun <T> real(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }

  private suspend fun ChatService.awaitIdle() = real { while (isBusy || state.value.isGenerating) delay(10) }

  @Test
  fun doubleTapSendStartsOnlyOneGeneration() = runTest {
    val s = service()
    val c = chats.create("m1")
    engine.gate = CompletableDeferred()
    assertTrue(s.send(c.id, "hi"))
    assertFalse("second send while busy must be rejected", s.send(c.id, "hi"))
    real { s.state.first { it.isGenerating } }
    assertFalse(s.send(c.id, "again"))
    engine.gate!!.complete(Unit)
    s.awaitIdle()
    assertEquals(1, engine.chatCalls)
    val msgs = chats.messages(c.id)
    assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), msgs.map { it.role })
    assertEquals("Hello", msgs[1].content)
    assertEquals(MessageStatus.COMPLETE, msgs[1].status)
  }

  @Test
  fun regenerateKeepsTheOldReplyAsAVersion() = runTest {
    val s = service()
    val c = chats.create("m1")
    s.send(c.id, "hi")
    s.awaitIdle()
    val first = chats.messages(c.id).last()
    assertTrue(s.regenerate(c.id))
    s.awaitIdle()
    val shown = chats.observeMessages(c.id).first()
    assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), shown.map { it.role })
    assertEquals(1 to 2, shown[1].versionIndex to shown[1].versionCount)
    assertEquals(3, chats.allMessages(c.id).size)
    // The model only saw the question, not the old reply.
    assertEquals(listOf("user"), engine.lastMessages.filter { it.role != "system" }.map { it.role })
    // ‹ goes back to the first reply.
    chats.stepVersion(c.id, shown[1].id, -1)
    assertEquals(first.id, chats.messages(c.id).last().id)
  }

  @Test
  fun editAddsAVersionOfTheQuestionAndAnswersIt() = runTest {
    val s = service()
    val c = chats.create("m1")
    s.send(c.id, "first question")
    s.awaitIdle()
    val question = chats.messages(c.id).first()
    assertTrue(s.edit(c.id, question.id, "second question"))
    s.awaitIdle()
    val shown = chats.observeMessages(c.id).first()
    assertEquals(listOf("second question", "Hello"), shown.map { it.content })
    assertEquals(1 to 2, shown[0].versionIndex to shown[0].versionCount)
    assertEquals(listOf("second question"), engine.lastMessages.filter { it.role == "user" }.map { it.content })
    assertEquals(4, chats.allMessages(c.id).size)
  }

  @Test
  fun editWithoutModelKeepsHistory() = runTest {
    val s = service()
    val c = chats.create(null)
    val u = chats.addMessage(c.id, MessageRole.USER, "question")
    chats.addMessage(c.id, MessageRole.ASSISTANT, "answer")
    activeModel = null
    s.edit(c.id, u.id, "edited")
    s.awaitIdle()
    assertEquals(listOf("question", "answer"), chats.messages(c.id).map { it.content })
    assertEquals("Load a model first", s.state.value.error)
    assertEquals(0, engine.chatCalls)
  }

  @Test
  fun longChatsAreTrimmedToTheContext() = runTest {
    val s = service()
    engine.state.value = EngineState.Loaded("/m", dev.lomio.llama.ModelInfo("x", null, 0, 0, 0, 1024, 0, null, false))
    val c = chats.create("m1")
    repeat(60) {
      chats.addMessage(c.id, MessageRole.USER, "question $it " + "q".repeat(300))
      chats.addMessage(c.id, MessageRole.ASSISTANT, "answer $it " + "a".repeat(300))
    }
    s.send(c.id, "latest")
    s.awaitIdle()
    val sent = engine.lastMessages
    assertEquals("system", sent.first().role)
    assertEquals("latest", sent.last().content)
    assertTrue(sent.size < 121)
    assertTrue(sent.sumOf(ContextWindow::estimateTokens) <= 1024 - ContextWindow.reserveFor(1024, -1))
  }

  @Test
  fun errorStopReasonIsStoredAsError() = runTest {
    val s = service()
    engine.stopReason = StopReason.ERROR
    val c = chats.create("m1")
    s.send(c.id, "hi")
    s.awaitIdle()
    assertEquals(MessageStatus.ERROR, chats.messages(c.id).last().status)
  }

  @Test
  fun engineFailureSettlesStateAndMessage() = runTest {
    val s = service()
    engine.failWith = IllegalStateException("native boom")
    val c = chats.create("m1")
    s.send(c.id, "hi")
    s.awaitIdle()
    val last = chats.messages(c.id).last()
    assertEquals(MessageStatus.ERROR, last.status)
    assertEquals("native boom", s.state.value.error)
    assertFalse(s.state.value.isGenerating)
  }
}
