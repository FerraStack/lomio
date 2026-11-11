package dev.lomio.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.lomio.R
import dev.lomio.domain.HistoryBucket
import dev.lomio.data.settings.AppSettings

/** Title stored for a chat that has no messages yet (see ChatRepository/ChatService). */
const val DEFAULT_CHAT_TITLE = "New chat"

/** A chat's title for display: the stored default is shown in the user's language. */
@Composable
fun chatTitle(title: String): String = if (title == DEFAULT_CHAT_TITLE) stringResource(R.string.chat_new) else title

/**
 * The global system prompt for display: while the user has never edited it, the stored English
 * default is shown (and used, see EngineText.defaultSystemPrompt) in the app's language.
 */
@Composable
fun systemPromptText(stored: String): String =
  if (stored == AppSettings.DEFAULT_SYSTEM_PROMPT) stringResource(R.string.default_system_prompt) else stored

@Composable
fun HistoryBucket.title(): String =
  stringResource(
    when (this) {
      HistoryBucket.PINNED -> R.string.history_pinned
      HistoryBucket.TODAY -> R.string.history_today
      HistoryBucket.YESTERDAY -> R.string.history_yesterday
      HistoryBucket.THIS_WEEK -> R.string.history_this_week
      HistoryBucket.LAST_WEEK -> R.string.history_last_week
      HistoryBucket.OLDER -> R.string.history_older
    }
  )
