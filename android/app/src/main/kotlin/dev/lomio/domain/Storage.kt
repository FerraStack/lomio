package dev.lomio.domain

/** Pure rules behind the Storage screen: what counts as unused, leftover, and how to say "last used". */
object Storage {
  const val DAY_MS = 24L * 60 * 60 * 1000

  /** A model nobody has loaded for this long is offered for clean-up. */
  const val UNUSED_AFTER_DAYS = 30

  /**
   * Files this recent are never called leftovers: an import copies into `models/local/*.part`
   * before its database row exists.
   */
  const val LEFTOVER_GRACE_MS = 10L * 60 * 1000

  /**
   * Unused = fully downloaded, not loaded right now, and not loaded for [UNUSED_AFTER_DAYS]. A
   * model that was never loaded counts from when it was added, so a fresh download isn't flagged.
   */
  fun isUnused(lastUsedAt: Long?, createdAt: Long, now: Long, ready: Boolean, loaded: Boolean): Boolean =
    ready && !loaded && now - (lastUsedAt ?: createdAt) >= UNUSED_AFTER_DAYS * DAY_MS

  data class FileEntry(val path: String, val sizeBytes: Long, val lastModified: Long)

  /**
   * Files under the models directory that no model row points at (directly or as its `.part`):
   * left by a crash mid-delete, a cancelled import, or a database reset. Recent files are skipped.
   */
  fun leftovers(files: List<FileEntry>, referencedPaths: Collection<String>, now: Long): List<FileEntry> {
    val owned = referencedPaths.flatMap { listOf(it, "$it.part") }.toSet()
    return files.filter { it.path !in owned && now - it.lastModified >= LEFTOVER_GRACE_MS }
  }

  sealed interface LastUsed {
    data object Never : LastUsed

    data object Today : LastUsed

    data object Yesterday : LastUsed

    data class DaysAgo(val days: Int) : LastUsed

    data class WeeksAgo(val weeks: Int) : LastUsed

    data class MonthsAgo(val months: Int) : LastUsed
  }

  /** Calendar-free buckets: "today" is the last 24 hours, which is what people mean here. */
  fun lastUsed(lastUsedAt: Long?, now: Long): LastUsed {
    if (lastUsedAt == null) return LastUsed.Never
    val days = ((now - lastUsedAt).coerceAtLeast(0) / DAY_MS).toInt()
    return when {
      days < 1 -> LastUsed.Today
      days < 2 -> LastUsed.Yesterday
      days < 7 -> LastUsed.DaysAgo(days)
      days < 30 -> LastUsed.WeeksAgo(days / 7)
      else -> LastUsed.MonthsAgo((days / 30).coerceAtLeast(1))
    }
  }

  fun lastUsedLabel(l: LastUsed): String =
    when (l) {
      LastUsed.Never -> "Never loaded"
      LastUsed.Today -> "Used today"
      LastUsed.Yesterday -> "Used yesterday"
      is LastUsed.DaysAgo -> "Used ${l.days} days ago"
      is LastUsed.WeeksAgo -> if (l.weeks == 1) "Used last week" else "Used ${l.weeks} weeks ago"
      is LastUsed.MonthsAgo -> if (l.months == 1) "Used a month ago" else "Used ${l.months} months ago"
    }
}
