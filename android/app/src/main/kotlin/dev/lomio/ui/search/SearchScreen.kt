package dev.lomio.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lomio.data.db.HIT_END
import dev.lomio.data.db.HIT_START
import dev.lomio.data.db.MessageRole
import java.text.DateFormat
import java.util.Date

data class SearchResult(
  val messageId: String,
  val conversationId: String,
  val chatTitle: String,
  val role: MessageRole,
  val createdAt: Long,
  /** FTS snippet with matches between [HIT_START] and [HIT_END]. */
  val snippet: String,
)

data class SearchUiState(val query: String = "", val results: List<SearchResult> = emptyList(), val searched: Boolean = false)

class SearchActions(
  val onBack: () -> Unit = {},
  val onQuery: (String) -> Unit = {},
  val onOpen: (SearchResult) -> Unit = {},
)

/** Turns the FTS snippet markers into bold, tinted spans. */
fun highlightSnippet(snippet: String, hit: SpanStyle): AnnotatedString = buildAnnotatedString {
  var i = 0
  while (i < snippet.length) {
    val start = snippet.indexOf(HIT_START, i)
    if (start < 0) {
      append(snippet.substring(i))
      break
    }
    append(snippet.substring(i, start))
    val end = snippet.indexOf(HIT_END, start + 1).let { if (it < 0) snippet.length else it }
    pushStyle(hit)
    append(snippet.substring(start + 1, end))
    pop()
    i = end + 1
  }
}

/** Full-text search over every message (all chats, all versions). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(state: SearchUiState, actions: SearchActions, modifier: Modifier = Modifier) {
  val focus = remember { FocusRequester() }
  val inspection = LocalInspectionMode.current
  LaunchedEffect(Unit) { if (!inspection) runCatching { focus.requestFocus() } }
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        title = {
          TextField(
            value = state.query,
            onValueChange = actions.onQuery,
            placeholder = { Text("Search messages") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            trailingIcon = {
              if (state.query.isNotEmpty()) {
                IconButton(onClick = { actions.onQuery("") }) { Icon(Icons.Outlined.Close, "Clear search") }
              }
            },
            colors =
              TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
              ),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
          )
        },
      )
    },
  ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
      when {
        state.query.isBlank() -> Empty("Search all your chats", "Find a message by any word in it, in every chat.")
        state.searched && state.results.isEmpty() -> Empty("No messages match “${state.query.trim()}”", "Try fewer or different words.")
        else ->
          LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "count") {
              Text(
                if (state.results.size == 1) "1 message" else "${state.results.size} messages",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
              )
            }
            items(state.results, key = { it.messageId }) { r ->
              ResultRow(r, onClick = { actions.onOpen(r) })
              HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            }
          }
      }
    }
  }
}

@Composable
private fun ResultRow(r: SearchResult, onClick: () -> Unit) {
  val hit = SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
  Column(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        r.chatTitle,
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Text(
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(r.createdAt)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp),
      )
    }
    Text(
      buildAnnotatedString {
        withRole(r.role)
        append(highlightSnippet(r.snippet, hit))
      },
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 3,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

private fun AnnotatedString.Builder.withRole(role: MessageRole) {
  pushStyle(SpanStyle(fontWeight = FontWeight.Medium))
  append(if (role == MessageRole.USER) "You: " else "Reply: ")
  pop()
}

@Composable
private fun Empty(title: String, body: String) {
  Column(
    Modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(Icons.Outlined.Search, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
    Text(
      body,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 4.dp),
    )
  }
}
