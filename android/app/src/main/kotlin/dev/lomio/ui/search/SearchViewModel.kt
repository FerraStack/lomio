package dev.lomio.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lomio.AppContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val c: AppContainer) : ViewModel() {
  private val query = MutableStateFlow("")

  private val results =
    query
      .debounce(200)
      .flatMapLatest { q ->
        c.chats.searchMessages(q).map { hits ->
          q to hits.map { SearchResult(it.messageId, it.conversationId, it.title, it.role, it.createdAt, it.snippet) }
        }
      }
      .onStart { emit("" to emptyList()) }

  val state: StateFlow<SearchUiState> =
    combine(query, results) { q, (searchedFor, hits) ->
      SearchUiState(q, if (searchedFor.isBlank()) emptyList() else hits, searched = searchedFor == q && q.isNotBlank())
    }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

  fun setQuery(q: String) {
    query.value = q
  }
}
