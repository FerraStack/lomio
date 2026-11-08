package dev.lomio.launch

import android.content.Intent
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lomio.MainActivity
import dev.lomio.data.db.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Test
  fun intentsRoundTrip() {
    val ask = LaunchIntents.newChat(context, ask = true)
    assertEquals(MainActivity::class.java.name, ask.component!!.className)
    assertTrue(ask.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    assertEquals(LaunchAction.NewChat(ask = true), LaunchIntents.parse(ask))
    assertEquals(LaunchAction.NewChat(ask = false), LaunchIntents.parse(LaunchIntents.newChat(context)))
    assertEquals(LaunchAction.OpenChat("c42"), LaunchIntents.parse(LaunchIntents.openChat(context, "c42")))
  }

  @Test
  fun ordinaryLaunchesAndBrokenIntentsDoNothing() {
    assertNull(LaunchIntents.parse(Intent(Intent.ACTION_MAIN)))
    assertNull(LaunchIntents.parse(null))
    assertNull(LaunchIntents.parse(LaunchIntents.ACTION_OPEN_CHAT, conversationId = "", ask = false))
    // The static shortcut's XML intent carries only the action.
    assertEquals(LaunchAction.NewChat(ask = false), LaunchIntents.parse(Intent(LaunchIntents.ACTION_NEW_CHAT)))
  }

  private fun chat(id: String, title: String, updatedAt: Long, pinned: Boolean = false) = ConversationSummary(id, title, pinned, updatedAt, null)

  @Test
  fun lastChatIsTheMostRecentlyUpdatedEvenIfAnotherIsPinned() {
    val history = listOf(chat("p", "Pinned", 10, pinned = true), chat("a", "Newest", 30), chat("b", "Older", 20))
    assertEquals("a", Shortcuts.lastChat(history)!!.id)
    assertNull(Shortcuts.lastChat(emptyList()))
  }

  @Test
  fun labelsFitLaunchers() {
    assertEquals("Short" to "Short", Shortcuts.labels("Short"))
    val (short, long) = Shortcuts.labels("Reverse a string in Kotlin without the stdlib")
    assertTrue(short.length <= 12 && short.endsWith("…"))
    assertTrue(long.length <= 25 && long.endsWith("…"))
    assertEquals("Last chat" to "Last chat", Shortcuts.labels("  "))
  }

  @Test
  fun publishesAndRemovesTheLastChatShortcut() {
    Shortcuts.publishLastChat(context, chat("c1", "Trip to Lviv", 1))
    val s = ShortcutManagerCompat.getDynamicShortcuts(context).single { it.id == Shortcuts.LAST_CHAT_ID }
    assertEquals("Trip to Lviv", s.shortLabel.toString())
    assertEquals(LaunchAction.OpenChat("c1"), LaunchIntents.parse(s.intent))
    Shortcuts.publishLastChat(context, null)
    assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).none { it.id == Shortcuts.LAST_CHAT_ID })
  }
}
