package dev.lomio

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lomio.data.db.MessageRole
import dev.lomio.launch.LaunchAction
import dev.lomio.launch.LaunchIntents
import dev.lomio.launch.Shortcuts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launcher shortcuts and the "Ask Lomio" Quick Settings tile on a real device: the dynamic
 * shortcut follows the latest chat and opens it; the tile (added and clicked through
 * `cmd statusbar`) opens a new chat with the message field focused.
 */
@RunWith(AndroidJUnit4::class)
class ShortcutsEndToEndTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app
    get() = instrumentation.targetContext.applicationContext as LomioApp

  private fun shell(cmd: String): String =
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).use {
      it.readBytes().decodeToString()
    }

  private fun screenshot(name: String) {
    shell("mkdir -p ${EndToEndChatTest.SHOT_DIR}")
    shell("screencap -p ${EndToEndChatTest.SHOT_DIR}/$name.png")
  }

  private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

  private val composer
    get() = compose.onNode(hasSetTextAction() and hasText("Message"))

  @Test
  fun shortcutsAndTileOpenTheRightChat() {
    val c = app.container
    val chat = runBlocking {
      c.chats.create(modelId = null, title = TITLE).also { conv ->
        c.chats.addMessage(conv.id, MessageRole.USER, QUESTION)
      }
    }

    // The dynamic shortcut follows the newest chat and opens it.
    compose.waitUntil(10_000) {
      ShortcutManagerCompat.getDynamicShortcuts(app).any { it.id == Shortcuts.LAST_CHAT_ID && LaunchIntents.parse(it.intent) == LaunchAction.OpenChat(chat.id) }
    }
    val shortcut = ShortcutManagerCompat.getDynamicShortcuts(app).single { it.id == Shortcuts.LAST_CHAT_ID }
    assertEquals("Trip to Lviv", shortcut.shortLabel.toString())
    app.startActivity(shortcut.intent)
    // (The drawer, composed even when closed, lists titles; the message is only in the chat.)
    compose.waitUntil(10_000) { shown(QUESTION) }

    // The static "New chat" shortcut's intent (action only, like the launcher sends it).
    app.startActivity(LaunchIntents.newChat(app).setAction(LaunchIntents.ACTION_NEW_CHAT))
    compose.waitUntil(10_000) { !shown(QUESTION) }

    // Quick Settings tile: add it, show it, click it.
    val tile = "${app.packageName}/dev.lomio.launch.AskTileService"
    shell("cmd statusbar add-tile $tile")
    shell("cmd statusbar expand-settings")
    Thread.sleep(2_000)
    screenshot("e2e_8_qs_tile")
    shell("cmd statusbar collapse")
    // Back to the old chat first, so the tile visibly switches to a new one.
    app.startActivity(LaunchIntents.openChat(app, chat.id))
    compose.waitUntil(10_000) { shown(QUESTION) }
    shell("cmd statusbar click-tile $tile")
    compose.waitUntil(15_000) { !shown(QUESTION) }
    compose.waitUntil(5_000) { runCatching { composer.assertIsFocused() }.isSuccess }
    Thread.sleep(1_000) // let the keyboard finish sliding in
    screenshot("e2e_7_ask_lomio")
    shell("cmd statusbar remove-tile $tile")

    runBlocking { c.chats.delete(chat.id) }
  }

  companion object {
    const val TITLE = "Trip to Lviv"
    const val QUESTION = "Where should we eat in Lviv?"
  }
}
