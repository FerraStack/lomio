package dev.lomio.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownExtendedSpans
import com.mikepenz.markdown.model.markdownInlineContent
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.CompositeASTNode
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import dev.lomio.ui.components.CodePalette
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.compose.components.markdownComponents
import dev.lomio.data.db.MessageStatus
import dev.lomio.domain.UiMessage
import dev.lomio.llama.GenerationStats
import dev.lomio.llama.StopReason
import java.util.Locale

/** User message: right-aligned tonal bubble, plain text, max ~85% width (RESEARCH.md §9.1). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessage(message: UiMessage, onCopy: () -> Unit, onEdit: () -> Unit, modifier: Modifier = Modifier) {
  var menu by remember { mutableStateOf(false) }
  BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
    val bubbleMaxWidth = maxWidth * 0.85f
    Box {
      Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
        modifier =
          Modifier.widthIn(max = bubbleMaxWidth)
            .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
            .combinedClickable(onClick = {}, onLongClick = { menu = true }),
      ) {
        Text(
          message.content,
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
      }
      DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
        DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; onCopy() })
      }
    }
  }
}

/** Assistant message: full width, no bubble, rendered Markdown (RESEARCH.md §9.1). */
@Composable
fun AssistantMessage(
  message: UiMessage,
  showStats: Boolean,
  onCopy: () -> Unit,
  onRegenerate: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val streaming = message.status == MessageStatus.STREAMING
  Column(modifier.fillMaxWidth()) {
    message.reasoning?.takeIf { it.isNotBlank() }?.let { ReasoningBlock(it, streaming) }
    if (message.content.isNotEmpty()) {
      SelectionContainer { MarkdownText(message.content, streaming = streaming) }
    } else if (streaming && message.reasoning.isNullOrBlank()) {
      TypingIndicator()
    }
    when (message.status) {
      MessageStatus.STOPPED -> StatusNote("Stopped")
      MessageStatus.ERROR -> StatusNote("Generation failed")
      else ->
        if (message.stats?.stopReason == StopReason.CONTEXT_FULL) {
          StatusNote("Cut off — context full. Start a new chat or increase the context size.")
        }
    }
    if (!streaming) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
          Icon(Icons.Outlined.ContentCopy, "Copy", Modifier.size(18.dp))
        }
        if (onRegenerate != null) {
          IconButton(onClick = onRegenerate, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Refresh, "Regenerate", Modifier.size(18.dp))
          }
        }
        if (showStats) message.stats?.let { StatsLine(it) }
      }
    }
  }
}

/**
 * Assistant Markdown.
 *
 * A reply that is [streaming] when it is first shown is parsed incrementally ([IncrementalMarkdown]):
 * each update re-parses only the unfinished last block, finished blocks are skipped by Compose,
 * and the same parser stays in use after the reply finishes, so there is no re-parse or flash at
 * the end. Replies loaded from history are parsed once, off the main thread.
 */
@Composable
fun MarkdownText(content: String, modifier: Modifier = Modifier, streaming: Boolean = false) {
  val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
  val palette = CodePalette.forDarkTheme(dark)
  val type = MaterialTheme.typography
  val colors =
    markdownColor(
      codeBackground = Color(0xFF000000.toInt() or palette.background),
      inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
  val typography =
    markdownTypography(
      // Chat-sized headings: the library defaults to display styles.
      h1 = type.titleLarge,
      h2 = type.titleMedium,
      h3 = type.titleSmall,
      h4 = type.titleSmall,
      h5 = type.labelLarge,
      h6 = type.labelLarge,
      code = type.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = Color(0xFF000000.toInt() or palette.plain)),
    )
  // The renderer's defaults are new objects on every call, and several are provided through
  // static composition locals: a new instance would recompose every block on every token.
  // Keep the first instance of each.
  val components = remember(palette) { lomioMarkdownComponents(palette) }
  val newAnnotator = markdownAnnotator()
  val annotator = remember { newAnnotator }
  val newSpans = markdownExtendedSpans()
  val extendedSpans = remember { newSpans }
  val newInline = markdownInlineContent()
  val inlineContent = remember { newInline }
  val newAnimations = markdownAnimations()
  val animations = remember { newAnimations }
  val imageTransformer = remember { NoOpImageTransformerImpl() }

  val incremental = remember { if (streaming) IncrementalMarkdown() else null }
  if (incremental != null) {
    val snapshot = remember(content) { incremental.update(content) }
    val placeholder = remember { State.Success(CompositeASTNode(MarkdownElementTypes.MARKDOWN_FILE, emptyList()), "", false) }
    Markdown(
      state = placeholder,
      colors = colors,
      typography = typography,
      modifier = modifier.fillMaxWidth(),
      imageTransformer = imageTransformer,
      annotator = annotator,
      extendedSpans = extendedSpans,
      inlineContent = inlineContent,
      components = components,
      animations = animations,
      success = { _, c, m -> IncrementalMarkdownColumn(snapshot, c, m) },
    )
  } else {
    // retainState avoids flashing back to "loading" if the content changes.
    val state = rememberMarkdownState(content, retainState = true)
    Markdown(
      markdownState = state,
      colors = colors,
      typography = typography,
      modifier = modifier.fillMaxWidth(),
      imageTransformer = imageTransformer,
      annotator = annotator,
      extendedSpans = extendedSpans,
      inlineContent = inlineContent,
      components = components,
      animations = animations,
    )
  }
}

private fun lomioMarkdownComponents(palette: CodePalette) =
  markdownComponents(
    codeFence = {
      MarkdownCodeFence(it.content, it.node, it.typography.code) { code, language, style ->
        HighlightedCode(code, language, style, palette)
      }
    },
    codeBlock = {
      MarkdownCodeBlock(it.content, it.node, it.typography.code) { code, language, style ->
        HighlightedCode(code, language, style, palette)
      }
    },
  )

@Composable
private fun ReasoningBlock(text: String, streaming: Boolean) {
  // Expanded while streaming, collapsed when done (PocketPal's ReasoningBlock).
  var expanded by rememberSaveable(streaming) { mutableStateOf(streaming) }
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainer,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
  ) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
          Modifier.fillMaxWidth()
            .clickable { expanded = !expanded }
            .semantics { contentDescription = if (expanded) "Hide reasoning" else "Show reasoning" },
      ) {
        Icon(Icons.Outlined.Lightbulb, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        Text(
          if (streaming) "Thinking…" else "Thought process",
          style = MaterialTheme.typography.labelLarge,
          modifier = Modifier.weight(1f),
        )
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
      }
      AnimatedVisibility(expanded) {
        Text(
          text,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 6.dp),
        )
      }
    }
  }
}

@Composable
private fun TypingIndicator() {
  Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    repeat(3) {
      Box(
        Modifier.size(8.dp)
          .clip(RoundedCornerShape(50))
          .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
      )
    }
  }
}

@Composable
private fun StatusNote(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.error,
    modifier = Modifier.padding(top = 4.dp),
  )
}

@Composable
private fun StatsLine(stats: GenerationStats) {
  Text(
    statsText(stats),
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.padding(start = 4.dp),
  )
}

fun statsText(s: GenerationStats): String =
  String.format(
    Locale.US,
    "%d tok · %.1f tok/s · TTFT %d ms",
    s.generatedTokens,
    s.genTokensPerSec,
    s.timeToFirstTokenMs,
  )
