package dev.lomio

import android.util.Log
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lomio.data.ModelFiles
import dev.lomio.data.db.MessageRole
import dev.lomio.data.db.ModelState
import dev.lomio.data.hf.HfFile
import dev.lomio.data.hf.Shards
import dev.lomio.domain.GenerationSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A split GGUF end to end, over the network: the three parts of llama.cpp's own split test
 * model (ggml-org/models, tinyllamas/split/stories15M-q8_0-0000N-of-00003.gguf, 26 MB) are
 * grouped into one model, downloaded by the real download job (each part resumable and
 * checked against its sha256), shown as one model, loaded through the first part, and answer.
 */
@RunWith(AndroidJUnit4::class)
class SplitModelTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  private val app
    get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LomioApp

  private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

  @Test
  fun downloadsAllPartsAndLoadsThroughTheFirst() {
    val c = app.container
    val parts =
      listOf(
        Triple("00001", 10_517_152L, "8a3a74042ae05dda34985dff2d49adf2ee3f9d0fd73b03fa6cc4307c924e4040"),
        Triple("00002", 10_381_216L, "4a70d35fdd275b054c29979da9c5d27e7045f011fdf11f736a652a03b1ffb355"),
        Triple("00003", 5_773_312L, "b2d72a3315df3e2834be32adbbc737ca02cc443125e26bbc2cfe8695ec3814bc"),
      ).map { (n, size, sha) -> HfFile(REPO, "tinyllamas/split/stories15M-q8_0-$n-of-00003.gguf", size, sha, REVISION) }
    // Listed out of order, as a repo tree can be.
    val model = Shards.group(parts.reversed()).single()
    assertTrue(model.isSplit)

    runBlocking {
      c.settings.update {
        it.copy(
          systemPrompt = "",
          generation = GenerationSettings.DEFAULTS.copy(maxTokens = 48, seed = 42),
          load = it.load.copy(nCtx = 256, nThreads = 2),
        )
      }
    }
    val id = runBlocking { c.models.download(model) }.id
    val t0 = System.currentTimeMillis()
    compose.waitUntil(600_000) { runBlocking { c.models.get(id) }?.state.let { it == ModelState.READY || it == ModelState.ERROR } }
    val ready = runBlocking { c.models.get(id) }!!
    Log.i(TAG, "split download finished in ${System.currentTimeMillis() - t0} ms: ${ready.state} ${ready.errorMessage}")
    assertNull(ready.errorMessage)
    assertEquals(ModelState.READY, ready.state)
    val files = ModelFiles.weights(ready)
    assertEquals(parts.map { it.fileName }, files.map { it.name })
    assertEquals(parts.map { it.sizeBytes }, files.map { it.length() })
    // One model on the Storage screen, sized as all its parts.
    val scan = runBlocking { c.storage.scan(c.models.all()) }
    assertEquals(model.sizeBytes, scan.modelBytes[id])
    assertTrue("a part was counted as a leftover", scan.leftovers.none { l -> files.any { it.canonicalPath == l.path } })

    // The Models screen shows it as one card.
    compose.onAllNodesWithContentDescription("Open menu").onFirst().performClick()
    compose.waitUntil(5_000) { shown("Models") }
    compose.onAllNodesWithText("Models").onFirst().performClick()
    compose.waitUntil(10_000) { shown(ready.displayName) }
    E2eShots.capture("e2e_split_1_models")

    // Load it from the chat's model sheet and get a reply.
    compose.onAllNodesWithContentDescription("Open menu").onFirst().performClick()
    compose.waitUntil(5_000) { shown("Chat") }
    compose.onAllNodesWithText("Chat").onFirst().performClick()
    compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Choose model").fetchSemanticsNodes().isNotEmpty() }
    compose.onNodeWithContentDescription("Choose model").performClick()
    compose.waitUntil(10_000) { shown(ready.displayName) }
    compose.onAllNodesWithText(ready.displayName).onFirst().performClick()
    compose.waitUntil(120_000) { c.modelManager.activeModelId.value == id }
    compose.onNode(hasSetTextAction() and hasText("Message")).performTextInput("Once upon a time")
    compose.onNodeWithContentDescription("Send").performClick()
    compose.waitUntil(120_000) { shown("tok/s") }
    E2eShots.capture("e2e_split_2_reply")

    val conversationId = runBlocking { c.chats.observeHistory("").first() }.first().id
    val reply = runBlocking { c.chats.messages(conversationId) }.last()
    assertEquals(MessageRole.ASSISTANT, reply.role)
    assertTrue("empty reply", reply.content.isNotBlank())
    runBlocking {
      c.modelManager.unload()
      c.settings.update { it.copy(lastModelId = null) }
    }
  }

  companion object {
    const val TAG = "lomio-e2e-split"
    /** ggml-org/models was renamed; the API redirects, the pinned commit is in the new repo. */
    const val REPO = "ggml-org/models-moved"
    const val REVISION = "499bc8821c6b12b4e53c5bffcb21ec206f212d81"
  }
}
