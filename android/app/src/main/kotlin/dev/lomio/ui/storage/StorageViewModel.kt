package dev.lomio.ui.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lomio.AppContainer
import dev.lomio.data.StorageScan
import dev.lomio.data.db.ModelEntity
import dev.lomio.data.db.ModelState
import dev.lomio.domain.Storage
import dev.lomio.ui.components.Format
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pure mapping from the database rows and a disk scan to what the screen shows. */
fun storageUiState(models: List<ModelEntity>, activeId: String?, scan: StorageScan, now: Long): StorageUiState =
  StorageUiState(
    loading = false,
    volumeBytes = scan.volumeBytes,
    freeBytes = scan.freeBytes,
    models =
      models
        .map { m ->
          val ready = m.state == ModelState.READY
          val missing = m.id in scan.missingFiles
          StorageModelRow(
            id = m.id,
            name = m.displayName,
            quant = m.quant,
            bytes = scan.modelBytes[m.id] ?: 0L,
            lastUsed = Storage.lastUsed(m.lastUsedAt, now),
            loaded = m.id == activeId,
            partial = !ready,
            missing = missing,
            unused = !missing && Storage.isUnused(m.lastUsedAt, m.createdAt, now, ready, loaded = m.id == activeId),
          )
        }
        .sortedWith(compareByDescending<StorageModelRow> { it.bytes }.thenBy { it.name.lowercase() }),
    chatsBytes = scan.chatsBytes,
    cacheBytes = scan.cacheBytes,
    leftoverBytes = scan.leftoverBytes,
    leftoverCount = scan.leftovers.size,
  )

@OptIn(ExperimentalCoroutinesApi::class)
class StorageViewModel(private val c: AppContainer, private val clock: () -> Long = System::currentTimeMillis) : ViewModel() {
  /** Bumped after a clean-up the database doesn't see (cache, leftovers). */
  private val rescan = MutableStateFlow(0)
  private val busy = MutableStateFlow(false)
  private val message = MutableStateFlow<String?>(null)

  private val scanned =
    combine(c.models.observeModels(), c.modelManager.activeModelId, rescan) { models, active, _ -> models to active }
      .mapLatest { (models, active) ->
        runCatching { storageUiState(models, active, c.storage.scan(models), clock()) }
          .getOrElse { e -> StorageUiState(loading = false, message = "Couldn't measure storage: ${e.message}") }
      }

  val state: StateFlow<StorageUiState> =
    combine(scanned, busy, message) { s, b, m -> s.copy(busy = b, message = m ?: s.message) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageUiState())

  fun delete(ids: List<String>) = work {
    val rows = state.value.models.filter { it.id in ids }
    for (id in ids) {
      if (c.modelManager.activeModelId.value == id) c.modelManager.unload()
      c.models.delete(id)
    }
    val freed = rows.sumOf { it.bytes }
    if (rows.size == 1) "Deleted ${rows[0].name} · freed ${Format.bytes(freed)}"
    else "Deleted ${rows.size} models · freed ${Format.bytes(freed)}"
  }

  fun clearCache() = work { "Cleared ${Format.bytes(c.storage.clearCache())} of temporary files" }

  fun removeLeftovers() = work {
    val freed = c.storage.deleteLeftovers(c.models.observeModels().first())
    "Removed leftover files · freed ${Format.bytes(freed)}"
  }

  fun messageShown() {
    message.value = null
  }

  private fun work(block: suspend () -> String) =
    viewModelScope.launch {
      if (busy.value) return@launch
      busy.value = true
      message.value = runCatching { block() }.getOrElse { "Clean-up failed: ${it.message}" }
      rescan.update { it + 1 }
      busy.value = false
    }
}
