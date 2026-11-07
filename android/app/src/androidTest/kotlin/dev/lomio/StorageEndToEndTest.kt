package dev.lomio

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lomio.ui.components.Format
import dev.lomio.ui.storage.STORAGE_LIST_TAG
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Storage screen on a real device: the model's size on disk is shown, leftover files and
 * temporary files are removed for real, and deleting a model deletes its file and its row.
 * Uses the same pushed GGUF as [EndToEndChatTest]; see scripts/ci/emulator-e2e.sh.
 */
@RunWith(AndroidJUnit4::class)
class StorageEndToEndTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app
    get() = instrumentation.targetContext.applicationContext as LomioApp

  private fun shell(cmd: String): String =
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).use {
      it.readBytes().decodeToString()
    }

  private fun copyFromShell(src: String, dest: File) {
    dest.parentFile!!.mkdirs()
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $src")).use { input ->
      FileOutputStream(dest).use { input.copyTo(it) }
    }
  }

  private fun screenshot(name: String) {
    compose.waitForIdle()
    shell("mkdir -p ${EndToEndChatTest.SHOT_DIR}")
    shell("screencap -p ${EndToEndChatTest.SHOT_DIR}/$name.png")
  }

  private fun shown(text: String, substring: Boolean = true) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

  private fun scrollTo(text: String) = compose.onNodeWithTag(STORAGE_LIST_TAG).performScrollToNode(hasText(text, substring = true))

  @Test
  fun showsUsageAndCleansUp() {
    val c = app.container
    val dir = c.models.modelsDir
    val dest = File(dir, "local/storage-e2e.gguf")
    copyFromShell(EndToEndChatTest.MODEL_ON_DEVICE, dest)
    assertTrue("model was not pushed", dest.length() > 1_000_000)
    val model = runBlocking { c.models.registerLocalFile(dest, displayName = MODEL_NAME) }
    // A file no model row points at, old enough to count as a leftover.
    val orphan =
      File(dir, "hf_e2e_leftover/orphan.gguf").apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(1_500_000))
        setLastModified(System.currentTimeMillis() - 60 * 60 * 1000)
      }
    val temp = File(app.cacheDir, "e2e-temp.bin").apply { writeBytes(ByteArray(200_000)) }

    // Chat → drawer → Models → Storage, like a user.
    compose.onNodeWithContentDescription("Open menu").performClick()
    compose.waitForIdle()
    compose.onAllNodesWithText("Models").onFirst().performClick()
    compose.onNodeWithContentDescription("Storage").performClick()
    compose.waitUntil(30_000) { shown(MODEL_NAME, substring = false) }

    // The row shows the real size of the file on disk.
    compose.onNode(hasText(MODEL_NAME) and hasText(Format.bytes(dest.length()))).assertExists()
    scrollTo("Leftover files")
    compose.onNode(hasText("Leftover files") and hasText("1 file", substring = true)).assertExists()
    screenshot("e2e_4_storage")

    compose.onNode(hasText("Remove") and hasClickAction()).performClick()
    compose.waitUntil(10_000) { !orphan.exists() }
    compose.waitUntil(10_000) { !shown("Leftover files") }
    assertFalse("empty folder pruned", orphan.parentFile!!.exists())

    scrollTo("Temporary files")
    compose.onNode(hasText("Clear") and hasClickAction()).performClick()
    compose.waitUntil(10_000) { !temp.exists() }

    scrollTo(MODEL_NAME)
    compose.onNodeWithContentDescription("Delete $MODEL_NAME").performClick()
    compose.onNode(hasText("Delete") and hasClickAction()).performClick()
    compose.waitUntil(15_000) { !dest.exists() && runBlocking { c.models.get(model.id) } == null }
    // Exact match: the snackbar ("Deleted <name> · freed …") contains the name too.
    compose.waitUntil(10_000) { !shown(MODEL_NAME, substring = false) }
    assertNull(runBlocking { c.models.get(model.id) })
    screenshot("e2e_5_storage_cleaned")
  }

  companion object {
    const val MODEL_NAME = "Stories (storage e2e)"
  }
}
