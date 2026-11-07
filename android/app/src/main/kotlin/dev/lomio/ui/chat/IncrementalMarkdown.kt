package dev.lomio.ui.chat

import dev.lomio.ui.assertMainThread

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import dev.lomio.ui.components.CodePalette
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.EmptyStreamingMarkdownFile

/**
 * Append-only incremental Markdown parser for streamed replies (issue #2).
 *
 * Each [update] feeds only the new suffix to intellij-markdown's [EmptyStreamingMarkdownFile],
 * which re-parses only the unstable tail (usually the last paragraph or the open code fence).
 * Blocks that became stable keep the same [Block] instance
 * forever, so Compose skips them on every later update. If the new content does not extend the
 * old one (the chat template parser rewrote earlier text), the parser starts over.
 *
 * Not thread-safe; used from composition only.
 */
class IncrementalMarkdown(private val flavour: MarkdownFlavourDescriptor = GFMFlavourDescriptor()) {
  /** A top-level block plus the content it was parsed from (covers its offsets). */
  class Block(val node: ASTNode, val content: String)

  /** All blocks in order; the first [stableCount] are the same instances as last time. */
  class Snapshot(val blocks: List<Block>, val stableCount: Int)

  private var file = EmptyStreamingMarkdownFile(flavour)
  private var fed = ""
  private val stable = ArrayList<Block>()

  /** Number of times the parser had to start over; for tests. */
  var resets = 0
    private set

  /** Characters handed to the parser so far, re-fed text after a reset included; for tests. */
  var charsFed = 0L
    private set

  fun update(content: String): Snapshot {
    if (!content.startsWith(fed)) {
      file = EmptyStreamingMarkdownFile(flavour)
      fed = ""
      stable.clear()
      resets++
    }
    if (content.length > fed.length) {
      file.append(content.substring(fed.length))
      charsFed += content.length - fed.length
      fed = content
    }
    val children = file.stableChildren
    while (stable.size < children.size) stable += Block(children[stable.size], content)
    val tail = file.unstableTail
    val blocks = ArrayList<Block>(stable.size + tail.size)
    blocks.addAll(stable)
    tail.forEach { blocks += Block(it, content) }
    return Snapshot(blocks, stable.size)
  }
}

/**
 * Called once per block that (re)composes; null outside tests. Lets a test prove that stable
 * blocks are not recomposed while the reply streams.
 */
val LocalBlockCompositionCounter = staticCompositionLocalOf<((ASTNode) -> Unit)?> { null }

/**
 * Renders an [IncrementalMarkdown.Snapshot]. Blocks are keyed by their start offset, so a block
 * keeps its composition (and state such as code highlighting) when it moves from the unstable
 * tail to the stable part, and stable blocks, being the same instances, are skipped.
 */
@Composable
fun IncrementalMarkdownColumn(
  snapshot: IncrementalMarkdown.Snapshot,
  components: MarkdownComponents,
  modifier: Modifier = Modifier,
) {
  Column(modifier) {
    snapshot.blocks.forEach { block -> key(block.node.startOffset) { MarkdownBlock(block, components) } }
  }
}

@Composable
private fun MarkdownBlock(block: IncrementalMarkdown.Block, components: MarkdownComponents) {
  LocalBlockCompositionCounter.current?.invoke(block.node)
  MarkdownElement(block.node, components, block.content)
}

/** Minimum time between two highlight passes of a code block that is still growing. */
private const val HighlightIntervalMs = 250L

/**
 * A fenced code block with syntax highlighting that never flickers while streaming: the last
 * highlighted text stays on screen and the newly streamed suffix is shown plain until the next
 * highlight pass, which runs off the main thread at most every [HighlightIntervalMs].
 * (The renderer's own block resets to plain text on every change and re-highlights per token.)
 */
@Composable
fun HighlightedCode(code: String, language: String?, style: TextStyle, palette: CodePalette) {
  val text =
    if (LocalInspectionMode.current) {
      // Screenshots and previews: highlight synchronously so the first frame is final.
      remember(code, language, palette) { highlight(code, language, palette) }
    } else {
      var highlighted by remember(language, palette) { mutableStateOf<AnnotatedString?>(null) }
      val currentCode by rememberUpdatedState(code)
      LaunchedEffect(language, palette) {
        // conflate + delay: at most one pass per interval, always on the newest text.
        snapshotFlow { currentCode }
          .conflate()
          .collect { c ->
            if (highlighted?.text == c) return@collect
            val result = withContext(Dispatchers.Default) { highlight(c, language, palette) }
            // Only the pure highlight runs off main; the Compose state write is back on Main.
            assertMainThread("Code highlight state")
            highlighted = result
            delay(HighlightIntervalMs)
          }
      }
      stitch(highlighted, code)
    }

  MarkdownCodeBackground(
    color = LocalMarkdownColors.current.codeBackground,
    shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize),
    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    showHeader = true,
    language = language,
    code = code,
  ) {
    MarkdownBasicText(
      text = text,
      style = style,
      modifier = Modifier.horizontalScroll(rememberScrollState()).padding(LocalMarkdownPadding.current.codeBlock),
    )
  }
}

/** The last highlight result, extended with the plain text streamed since; or plain [code]. */
fun stitch(highlighted: AnnotatedString?, code: String): AnnotatedString =
  when {
    highlighted == null -> AnnotatedString(code)
    highlighted.text == code -> highlighted
    code.startsWith(highlighted.text) ->
      buildAnnotatedString {
        append(highlighted)
        append(code.substring(highlighted.text.length))
      }
    else -> AnnotatedString(code)
  }

private fun highlight(code: String, language: String?, palette: CodePalette): AnnotatedString {
  // A fresh builder per pass: Highlights.Builder is mutable and passes may overlap.
  val builder = Highlights.Builder().theme(palette.syntaxTheme()).code(code)
  language?.let { SyntaxLanguage.getByName(it) }?.let { builder.language(it) }
  val spans = builder.build().getHighlights()
  return buildAnnotatedString {
    append(code)
    spans.forEach {
      val span =
        when (it) {
          is ColorHighlight -> SpanStyle(color = Color(it.rgb).copy(alpha = 1f))
          is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
        }
      val start = it.location.start.coerceIn(0, code.length)
      val end = it.location.end.coerceIn(start, code.length)
      if (end > start) addStyle(span, start, end)
    }
  }
}
