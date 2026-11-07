package dev.lomio.launch

import android.content.Context
import android.content.Intent
import dev.lomio.MainActivity

/** What a launcher shortcut or the Quick Settings tile asks the app to do. */
sealed interface LaunchAction {
  /** A fresh chat; [ask] also focuses the message field and opens the keyboard. */
  data class NewChat(val ask: Boolean) : LaunchAction

  data class OpenChat(val conversationId: String) : LaunchAction
}

/** Intents for [LaunchAction]s, and the parsing of them in [MainActivity]. */
object LaunchIntents {
  const val ACTION_NEW_CHAT = "dev.lomio.action.NEW_CHAT"
  const val ACTION_OPEN_CHAT = "dev.lomio.action.OPEN_CHAT"
  const val EXTRA_CONVERSATION_ID = "dev.lomio.extra.CONVERSATION_ID"
  const val EXTRA_ASK = "dev.lomio.extra.ASK"

  /** Reuse the running activity (onNewIntent) instead of stacking a second one. */
  private const val FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

  fun newChat(context: Context, ask: Boolean = false): Intent =
    Intent(context, MainActivity::class.java).setAction(ACTION_NEW_CHAT).putExtra(EXTRA_ASK, ask).addFlags(FLAGS)

  fun openChat(context: Context, conversationId: String): Intent =
    Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_CHAT).putExtra(EXTRA_CONVERSATION_ID, conversationId).addFlags(FLAGS)

  fun parse(action: String?, conversationId: String?, ask: Boolean): LaunchAction? =
    when (action) {
      ACTION_NEW_CHAT -> LaunchAction.NewChat(ask)
      ACTION_OPEN_CHAT -> conversationId?.takeIf { it.isNotBlank() }?.let { LaunchAction.OpenChat(it) }
      else -> null
    }

  fun parse(intent: Intent?): LaunchAction? =
    intent?.let { parse(it.action, it.getStringExtra(EXTRA_CONVERSATION_ID), it.getBooleanExtra(EXTRA_ASK, false)) }
}
