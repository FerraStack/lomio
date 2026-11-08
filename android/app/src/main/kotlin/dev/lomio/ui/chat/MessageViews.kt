package dev.lomio.ui.chat

import dev.lomio.ui.assertMainThread

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.stringResource
import dev.lomio.R

/** What a message can do; lambdas are no-ops by default (screenshots, previews). */
class MessageActions(
  val onCopy: () -> Unit = {},
  val onShare: () -> Unit = {},
  val onDelete: () -> Unit = {},
  val onEdit: () -> Unit = {},
  /** Null when the reply can't be regenerated right now. */
  val onRegenerate: (() -> Unit)? = null,
  /** Previous (-1) or next (+1) version; null while generating. */
  val onStepVersion: ((Int) -> Unit)? = null,
)

/** User message: right-aligned tonal bubble, plain text, max ~85% width (RESEARCH.md §9.1). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessage(message: UiMessage, actions: MessageActions, modifier: Modifier = Modifier) {
  var menu by remember { mutableStateOf(false) }
  BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
    val bubbleMaxWidth = maxWidth * 0.85f
    Column(horizontalAlignment = Alignment.End) {
      Box {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
          // Attached images sit above the text bubble, right-aligned like it.
          message.images.forEachIndexed { i, path ->
            AttachmentImage(
              path,
              contentDescription = stringResource(R.string.message_attached_image, i + 1),
              modifier =
                Modifier.widthIn(max = minOf(bubbleMaxWidth, 240.dp))
                  .heightIn(max = 240.dp)
                  .clip(RoundedCornerShape(16.dp))
                  .combinedClickable(onClick = {}, onLongClick = { menu = true }, onLongClickLabel = stringResource(R.string.message_options)),
            )
          }
          if (message.content.isNotEmpty() || message.images.isEmpty()) {
            Surface(
              color = MaterialTheme.colorScheme.secondaryContainer,
              shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
              modifier =
                Modifier.widthIn(max = bubbleMaxWidth)
                  .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                  .combinedClickable(
                    onClick = {},
                    onLongClick = { menu = true },
                    onLongClickLabel = stringResource(R.string.message_options),
                  ),
            ) {
              Text(
                message.content,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
              )
            }
          }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
          MenuItem(stringResource(R.string.action_edit), Icons.Outlined.Edit) { menu = false; actions.onEdit() }
          MenuItem(stringResource(R.string.action_copy), Icons.Outlined.ContentCopy) { menu = false; actions.onCopy() }
          MenuItem(stringResource(R.string.action_share), Icons.Outlined.Share) { menu = false; actions.onShare() }
          MenuItem(stringResource(R.string.action_delete), Icons.Outlined.Delete, destructive = true) { menu = false; actions.onDelete() }
        }
      }
      if (message.versionCount > 1) {
        VersionSwitcher(message.versionIndex, message.versionCount, actions.onStepVersion)
      }
    }
  }
}

/** Assistant message: full width, no bubble, rendered Markdown (RESEARCH.md §9.1). */
@Composable
fun AssistantMessage(
  message: UiMessage,
  showStats: Boolean,
  actions: MessageActions,
  modifier: Modifier = Modifier,
) {
  val streaming = message.status == MessageStatus.STREAMING
  Column(modifier.fillMaxWidth()) {
    message.reasoning?.takeIf { it.isNotBlank() }?.let { ReasoningBlock(it, streaming) }
    if (message.content.isNotEmpty()) {
      SelectionContainer { MarkdownText(message.content, streaming = streaming) }
    } else if (streaming && message.reasoning.isNullOrBlank()) {
      val progress = message.promptProgress
      if (progress != null && progress < 1f) PromptProgress(progress) else TypingIndicator()
    }
    when (message.status) {
      MessageStatus.STOPPED -> StatusNote(stringResource(R.string.message_stopped))
      MessageStatus.ERROR -> StatusNote(stringResource(R.string.message_failed))
      else ->
        if (message.stats?.stopReason == StopReason.CONTEXT_FULL) {
          StatusNote(stringResource(R.string.message_context_full))
        }
    }
    if (!streaming) {
      var more by remember { mutableStateOf(false) }
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (message.versionCount > 1) {
          VersionSwitcher(message.versionIndex, message.versionCount, actions.onStepVersion)
        }
        FooterButton(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy), actions.onCopy)
        actions.onRegenerate?.let { FooterButton(Icons.Outlined.Refresh, stringResource(R.string.message_regenerate), it) }
        FooterButton(Icons.Outlined.Share, stringResource(R.string.action_share), actions.onShare)
        Box {
          FooterButton(Icons.Outlined.MoreHoriz, stringResource(R.string.message_more_actions)) { more = true }
          DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
            MenuItem(stringResource(R.string.action_delete), Icons.Outlined.Delete, destructive = true) { more = false; actions.onDelete() }
          }
        }
        if (showStats) message.stats?.let { StatsLine(it, Modifier.weight(1f, fill = false)) }
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
private fun FooterButton(icon: ImageVector, label: String, onClick: () -> Unit) {
  IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
    Icon(icon, label, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, destructive: Boolean = false, onClick: () -> Unit) {
  val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
  DropdownMenuItem(
    text = { Text(text, color = color) },
    leadingIcon = { Icon(icon, null, tint = color) },
    onClick = onClick,
  )
}

/** ‹ 2/3 › between versions of a regenerated reply or an edited question. */
@Composable
fun VersionSwitcher(index: Int, count: Int, onStep: ((Int) -> Unit)?) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    IconButton(
      onClick = { onStep?.invoke(-1) },
      enabled = onStep != null && index > 0,
      modifier = Modifier.size(32.dp),
    ) {
      Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.message_previous_version), Modifier.size(18.dp))
    }
    val versionDescription = stringResource(R.string.message_version_of, index + 1, count)
    Text(
      "${index + 1}/$count",
      style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.semantics { contentDescription = versionDescription },
    )
    IconButton(
      onClick = { onStep?.invoke(+1) },
      enabled = onStep != null && index < count - 1,
      modifier = Modifier.size(32.dp),
    ) {
      Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.message_next_version), Modifier.size(18.dp))
    }
  }
}

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
      table = type.bodyMedium,
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
    val snapshot =
      remember(content) {
        assertMainThread("Incremental Markdown parsing")
        incremental.update(content)
      }
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
    table = { MarkdownTableView(it.content, it.node, it.typography.table) },
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
  val toggleDescription = stringResource(if (expanded) R.string.message_hide_reasoning else R.string.message_show_reasoning)
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
            .semantics { contentDescription = toggleDescription },
      ) {
        Icon(Icons.Outlined.Lightbulb, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        Text(
          stringResource(if (streaming) R.string.message_thinking else R.string.message_thought_process),
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

/** Shown while a long prompt is processed, before the first token (can take seconds on a phone). */
@Composable
private fun PromptProgress(progress: Float) {
  val percent = (progress * 100).toInt()
  val description = stringResource(R.string.message_reading_a11y, percent)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    modifier =
      Modifier.padding(vertical = 8.dp).semantics(mergeDescendants = true) {
        contentDescription = description
      },
  ) {
    LinearProgressIndicator(
      progress = { progress },
      modifier = Modifier.width(96.dp).clip(RoundedCornerShape(50)),
    )
    Text(
      stringResource(R.string.message_reading, percent),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
private fun StatsLine(stats: GenerationStats, modifier: Modifier = Modifier) {
  Text(
    statsText(stats),
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier.padding(start = 4.dp),
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

/**
 * A "Summarize & continue" note: from here on the model sees this summary instead of the
 * messages above it. Collapsed to a few lines; tap to expand.
 */
@Composable
fun SummaryNote(message: UiMessage, modifier: Modifier = Modifier) {
  var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
  val streaming = message.status == MessageStatus.STREAMING
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(16.dp),
    modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { expanded = !expanded },
  ) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Summarize, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        Text(
          if (streaming) "Summarizing the conversation…" else "Conversation summarized",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.weight(1f),
        )
        if (!streaming) Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Collapse" else "Expand")
      }
      when {
        message.content.isNotEmpty() ->
          Text(
            message.content,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded || streaming) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
          )
        streaming -> LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
      }
      Text(
        when (message.status) {
          MessageStatus.ERROR -> "The summary failed; the model still sees the full chat."
          MessageStatus.STOPPED -> "Stopped; the model still sees the full chat."
          else -> "The model now sees this summary instead of the messages above."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
