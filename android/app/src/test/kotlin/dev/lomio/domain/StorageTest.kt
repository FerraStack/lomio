package dev.lomio.domain

import dev.lomio.domain.Storage.DAY_MS
import dev.lomio.domain.Storage.FileEntry
import dev.lomio.domain.Storage.LastUsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageTest {
  private val now = 1_000 * DAY_MS

  @Test
  fun unusedNeedsThirtyDaysWithoutALoad() {
    assertTrue(Storage.isUnused(lastUsedAt = now - 30 * DAY_MS, createdAt = 0, now = now, ready = true, loaded = false))
    assertFalse(Storage.isUnused(lastUsedAt = now - 29 * DAY_MS, createdAt = 0, now = now, ready = true, loaded = false))
  }

  @Test
  fun neverLoadedCountsFromWhenItWasAdded() {
    assertFalse(Storage.isUnused(lastUsedAt = null, createdAt = now - 2 * DAY_MS, now = now, ready = true, loaded = false))
    assertTrue(Storage.isUnused(lastUsedAt = null, createdAt = now - 45 * DAY_MS, now = now, ready = true, loaded = false))
  }

  @Test
  fun loadedOrUnfinishedModelsAreNeverUnused() {
    assertFalse(Storage.isUnused(lastUsedAt = 0, createdAt = 0, now = now, ready = true, loaded = true))
    assertFalse(Storage.isUnused(lastUsedAt = 0, createdAt = 0, now = now, ready = false, loaded = false))
  }

  @Test
  fun leftoversSkipReferencedFilesTheirPartsAndRecentFiles() {
    val old = now - Storage.LEFTOVER_GRACE_MS
    val files =
      listOf(
        FileEntry("/m/a.gguf", 10, old),
        FileEntry("/m/b.gguf.part", 20, old),
        FileEntry("/m/orphan.gguf", 30, old),
        FileEntry("/m/local/importing.gguf.part", 40, now - 1_000),
      )
    val left = Storage.leftovers(files, referencedPaths = listOf("/m/a.gguf", "/m/b.gguf"), now = now)
    assertEquals(listOf("/m/orphan.gguf"), left.map { it.path })
  }

  @Test
  fun lastUsedBuckets() {
    assertEquals(LastUsed.Never, Storage.lastUsed(null, now))
    assertEquals(LastUsed.Today, Storage.lastUsed(now - DAY_MS / 2, now))
    assertEquals(LastUsed.Yesterday, Storage.lastUsed(now - DAY_MS - 1, now))
    assertEquals(LastUsed.DaysAgo(3), Storage.lastUsed(now - 3 * DAY_MS, now))
    assertEquals(LastUsed.WeeksAgo(2), Storage.lastUsed(now - 15 * DAY_MS, now))
    assertEquals(LastUsed.MonthsAgo(2), Storage.lastUsed(now - 61 * DAY_MS, now))
    // A clock that went backwards is "today", not negative.
    assertEquals(LastUsed.Today, Storage.lastUsed(now + DAY_MS, now))
  }

  @Test
  fun lastUsedLabelsReadNaturally() {
    assertEquals("Used last week", Storage.lastUsedLabel(LastUsed.WeeksAgo(1)))
    assertEquals("Used 3 weeks ago", Storage.lastUsedLabel(LastUsed.WeeksAgo(3)))
    assertEquals("Used a month ago", Storage.lastUsedLabel(LastUsed.MonthsAgo(1)))
    assertEquals("Never loaded", Storage.lastUsedLabel(LastUsed.Never))
  }
}
