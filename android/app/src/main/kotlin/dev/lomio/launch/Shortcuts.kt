package dev.lomio.launch

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.lomio.R
import dev.lomio.data.db.ConversationSummary

/** The dynamic "continue your last chat" launcher shortcut. */
object Shortcuts {
  const val LAST_CHAT_ID = "last_chat"

  /** The chat to offer: the most recently updated one, pinned or not. */
  fun lastChat(history: List<ConversationSummary>): ConversationSummary? = history.maxByOrNull { it.updatedAt }

  /** Launchers truncate short labels around 10–12 characters and long ones around 25. */
  fun labels(title: String): Pair<String, String> {
    val t = title.trim().ifEmpty { "Last chat" }
    return ellipsize(t, 12) to ellipsize(t, 25)
  }

  private fun ellipsize(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

  fun publishLastChat(context: Context, chat: ConversationSummary?) {
    if (chat == null) {
      ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(LAST_CHAT_ID))
      return
    }
    val (short, long) = labels(chat.title)
    val info =
      ShortcutInfoCompat.Builder(context, LAST_CHAT_ID)
        .setShortLabel(short)
        .setLongLabel(long)
        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_chat))
        .setIntent(LaunchIntents.openChat(context, chat.id))
        .setRank(1)
        .build()
    ShortcutManagerCompat.pushDynamicShortcut(context, info)
  }
}
