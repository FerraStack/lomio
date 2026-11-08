package dev.lomio.ui

import dev.lomio.ui.settings.TokenCheckState

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lomio.AppContainer
import dev.lomio.data.db.ConversationSummary
import dev.lomio.data.settings.AppSettings
import dev.lomio.domain.HistoryBucket
import dev.lomio.domain.HistoryGrouping
import dev.lomio.llama.EngineState
import dev.lomio.llama.LlamaEngine
import dev.lomio.ui.bench.BenchProfile
import dev.lomio.ui.bench.BenchUiState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.lomio.R

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
  /** Null until DataStore has been read once. */
  val settings: StateFlow<AppSettings?> =
    c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

  fun update(transform: (AppSettings) -> AppSettings) =
    viewModelScope.launch {
      c.settings.update(transform)
      _tokenCheck.value = null
    }

  private val _tokenCheck = MutableStateFlow<TokenCheckState?>(null)
  /** Result of the last "Test token"; cleared when settings change. */
  val tokenCheck: StateFlow<TokenCheckState?> = _tokenCheck.asStateFlow()

  fun testToken() =
    viewModelScope.launch {
      val token = c.settings.current().hfToken
      if (token.isBlank()) return@launch
      _tokenCheck.value = TokenCheckState.Checking
      _tokenCheck.value = TokenCheckState.Done(c.hfClient.checkToken(token))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DrawerViewModel(private val c: AppContainer) : ViewModel() {
  private val _query = MutableStateFlow("")
  val query: StateFlow<String> = _query.asStateFlow()

  val history: StateFlow<List<Pair<HistoryBucket, List<ConversationSummary>>>> =
    _query
      .flatMapLatest { c.chats.observeHistory(it) }
      .map { HistoryGrouping.group(it, Instant.now(), ZoneId.systemDefault()) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  fun search(q: String) {
    _query.value = q
  }

  fun pin(id: String, pinned: Boolean) = viewModelScope.launch { c.chats.setPinned(id, pinned) }

  fun rename(id: String, title: String) = viewModelScope.launch { c.chats.rename(id, title) }

  fun delete(id: String) = viewModelScope.launch { c.chats.delete(id) }
}

class BenchViewModel(private val c: AppContainer) : ViewModel() {
  private val local = MutableStateFlow(BenchUiState())

  val state: StateFlow<BenchUiState> =
    combine(local, c.modelManager.engineState) { s, engine ->
        val info = (engine as? EngineState.Loaded)?.info ?: (engine as? EngineState.Generating)?.info
        s.copy(modelName = info?.description, backend = info?.backend, gpuNote = info?.gpuNote)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BenchUiState())

  init {
    viewModelScope.launch {
      val info = runCatching { (c.engine as? LlamaEngine)?.systemInfo() }.getOrNull()
      local.update { it.copy(systemInfo = info) }
    }
  }

  fun setProfile(p: BenchProfile) = local.update { it.copy(profile = p) }

  fun run() =
    viewModelScope.launch {
      val p = local.value.profile
      local.update { it.copy(running = true, error = null) }
      runCatching { c.engine.bench(p.pp, p.tg, p.reps) }
        .onSuccess { r -> local.update { it.copy(running = false, result = r) } }
        .onFailure { e -> local.update { it.copy(running = false, error = e.message ?: c.text.getString(R.string.bench_failed)) } }
    }
}
