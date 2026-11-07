package dev.lomio.domain

import dev.lomio.data.db.ConversationSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class HistoryBucket(val label: String) {
  PINNED("Pinned"),
  TODAY("Today"),
  YESTERDAY("Yesterday"),
  THIS_WEEK("This week"),
  LAST_WEEK("Last week"),
  OLDER("Older"),
}

/** Groups drawer history like PocketPal (RESEARCH.md §9.0). Input order is kept per bucket. */
object HistoryGrouping {
  fun group(
    items: List<ConversationSummary>,
    now: Instant,
    zone: ZoneId,
  ): List<Pair<HistoryBucket, List<ConversationSummary>>> {
    val today = LocalDate.ofInstant(now, zone)
    val grouped =
      items.groupBy { item ->
        if (item.pinned) return@groupBy HistoryBucket.PINNED
        val day = LocalDate.ofInstant(Instant.ofEpochMilli(item.updatedAt), zone)
        val days = ChronoUnit.DAYS.between(day, today)
        when {
          days <= 0 -> HistoryBucket.TODAY
          days == 1L -> HistoryBucket.YESTERDAY
          days < 7 -> HistoryBucket.THIS_WEEK
          days < 14 -> HistoryBucket.LAST_WEEK
          else -> HistoryBucket.OLDER
        }
      }
    return HistoryBucket.entries.mapNotNull { b -> grouped[b]?.let { b to it } }
  }

  /** FTS4 query for a user search string: each word as a prefix term. */
  fun ftsQuery(query: String): String =
    query
      .split(Regex("\\s+"))
      .map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }
      .filter { it.isNotEmpty() }
      .joinToString(" ") { "$it*" }
}

object Titles {
  const val MAX_LENGTH = 40

  /** Auto-title from the first user message: first line, cut at a word boundary. */
  fun fromFirstMessage(text: String): String {
    val line = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return "New chat"
    if (line.length <= MAX_LENGTH) return line
    val cut = line.take(MAX_LENGTH)
    val space = cut.lastIndexOf(' ')
    return (if (space > MAX_LENGTH / 2) cut.take(space) else cut).trimEnd() + "…"
  }
}
