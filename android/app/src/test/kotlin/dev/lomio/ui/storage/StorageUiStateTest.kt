package dev.lomio.ui.storage

import dev.lomio.data.StorageScan
import dev.lomio.data.db.ModelEntity
import dev.lomio.data.db.ModelSource
import dev.lomio.data.db.ModelState
import dev.lomio.domain.Storage
import dev.lomio.domain.Storage.DAY_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageUiStateTest {
  private val now = 400 * DAY_MS

  private fun model(id: String, lastUsedDaysAgo: Int?, state: ModelState = ModelState.READY) =
    ModelEntity(
      id = id,
      source = ModelSource.HF,
      displayName = id.uppercase(),
      filePath = "/m/$id.gguf",
      sizeBytes = 0,
      state = state,
      createdAt = now - 300 * DAY_MS,
      lastUsedAt = lastUsedDaysAgo?.let { now - it * DAY_MS },
    )

  private val scan =
    StorageScan(
      volumeBytes = 100_000,
      freeBytes = 40_000,
      modelBytes = mapOf("small" to 100L, "big" to 5_000L, "active" to 2_000L, "partial" to 300L, "gone" to 0L),
      missingFiles = setOf("gone"),
      leftovers = listOf(Storage.FileEntry("/m/x", 70, 0)),
      cacheBytes = 20,
      chatsBytes = 10,
    )

  @Test
  fun biggestFirstWithUnusedLoadedAndPartialFlags() {
    val ui =
      storageUiState(
        listOf(
          model("small", lastUsedDaysAgo = 1),
          model("big", lastUsedDaysAgo = 90),
          model("active", lastUsedDaysAgo = 200),
          model("partial", lastUsedDaysAgo = null, state = ModelState.PAUSED),
          model("gone", lastUsedDaysAgo = 120),
        ),
        activeId = "active",
        scan = scan,
        now = now,
      )

    assertEquals(listOf("big", "active", "partial", "small", "gone"), ui.models.map { it.id })
    assertEquals(listOf("big"), ui.unused.map { it.id })
    assertTrue(ui.models.first { it.id == "active" }.loaded)
    assertTrue(ui.models.first { it.id == "partial" }.partial)
    val gone = ui.models.first { it.id == "gone" }
    assertTrue(gone.missing)
    assertFalse("a missing file isn't offered for clean-up", gone.unused)
    assertEquals(Storage.LastUsed.Yesterday, ui.models.first { it.id == "small" }.lastUsed)

    assertEquals(7_400L, ui.modelsBytes)
    assertEquals(100L, ui.otherBytes)
    assertEquals(7_500L, ui.lomioBytes)
    assertEquals(1, ui.leftoverCount)
    assertFalse(ui.loading)
    assertTrue("40 kB free is below the low-space line", ui.lowSpace)
    assertFalse(ui.copy(volumeBytes = 64_000_000_000, freeBytes = 20_000_000_000).lowSpace)
  }
}
