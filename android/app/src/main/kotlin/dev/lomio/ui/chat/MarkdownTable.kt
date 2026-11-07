package dev.lomio.ui.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.TABLE_SEPARATOR

/** Column alignment from a GFM delimiter row. */
enum class ColumnAlign { START, CENTER, END }

/** Parses a GFM delimiter row such as `|:--|:-:|--:|` into one alignment per column. */
fun parseColumnAlignments(delimiterRow: String, columns: Int): List<ColumnAlign> {
  val parts = delimiterRow.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
  return List(columns) { i ->
    val p = parts.getOrNull(i).orEmpty()
    when {
      p.length > 1 && p.startsWith(':') && p.endsWith(':') -> ColumnAlign.CENTER
      p.length > 1 && p.endsWith(':') -> ColumnAlign.END
      else -> ColumnAlign.START
    }
  }
}

/**
 * Content-sized column widths (all values in px).
 *
 * Each column starts at its widest cell, clamped to [minColumn]..[maxColumn]; longer cells wrap.
 * When that leaves the table narrower than [viewport], the spare width first goes back to the
 * clamped columns (so they wrap less), then the rest is shared in proportion to the column
 * widths so the table fills the message. A table wider than [viewport] scrolls horizontally.
 */
fun tableColumnWidths(natural: IntArray, minColumn: Int, maxColumn: Int, viewport: Int): IntArray {
  val cap = maxOf(minColumn, maxColumn)
  val w = IntArray(natural.size) { natural[it].coerceIn(minColumn, cap) }
  var spare = viewport - w.sum()
  if (spare <= 0 || w.isEmpty()) return w

  val deficits = IntArray(w.size) { maxOf(0, natural[it] - w[it]) }
  val totalDeficit = deficits.sum()
  if (totalDeficit > 0) {
    if (totalDeficit <= spare) {
      for (i in w.indices) w[i] += deficits[i]
    } else {
      share(w, deficits, spare)
    }
    spare = viewport - w.sum()
  }
  if (spare > 0) share(w, w.copyOf(), spare)
  return w
}

/** Adds [amount] to [w] in proportion to [weights]; the rounding remainder goes to the last. */
private fun share(w: IntArray, weights: IntArray, amount: Int) {
  val total = weights.sum().toLong()
  if (total == 0L) return
  var given = 0
  val last = weights.indices.last { weights[it] > 0 }
  for (i in w.indices) {
    if (weights[i] == 0) continue
    val add = if (i == last) amount - given else (amount.toLong() * weights[i] / total).toInt()
    w[i] += add
    given += add
  }
}

private val CellPaddingH = 12.dp
private val CellPaddingV = 8.dp
private val MinColumn = 48.dp
private val MaxColumn = 240.dp
private val EdgeFade = 28.dp

/**
 * A GFM table that sizes its columns to their content, wraps long cells, and scrolls
 * horizontally inside the message when it is wider than the screen, with a fade on the edge
 * that has more content (issue #7).
 */
@Composable
fun MarkdownTableView(content: String, node: ASTNode, style: TextStyle, modifier: Modifier = Modifier) {
  val rows = remember(node) { node.children.filter { it.type == HEADER || it.type == ROW } }
  val columns = remember(node) { rows.firstOrNull()?.children?.count { it.type == CELL } ?: 0 }
  if (columns == 0 || rows.isEmpty()) return
  val aligns =
    remember(node, content) {
      node.children
        .firstOrNull { it.type == TABLE_SEPARATOR }
        ?.let { parseColumnAlignments(content.substring(it.startOffset, it.endOffset), columns) }
        ?: List(columns) { ColumnAlign.START }
    }
  val colors = MaterialTheme.colorScheme
  val lineColor = colors.outlineVariant
  val headerColor = colors.surfaceContainerHigh
  val shape = RoundedCornerShape(12.dp)
  val scroll = rememberScrollState()

  BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
    val viewport = constraints.maxWidth
    Box(
      Modifier.clip(shape)
        .border(1.dp, lineColor, shape)
        .horizontalEdgeFade(scroll, EdgeFade)
        .horizontalScroll(scroll)
        .semantics { collectionInfo = CollectionInfo(rowCount = rows.size, columnCount = columns) }
    ) {
      TableGrid(columns = columns, viewport = viewport) {
        rows.forEachIndexed { r, row ->
          val header = row.type == HEADER
          val cells = row.children.filter { it.type == CELL }
          for (c in 0 until columns) {
            val align = aligns[c]
            Box(
              contentAlignment =
                when (align) {
                  ColumnAlign.START -> Alignment.TopStart
                  ColumnAlign.CENTER -> Alignment.TopCenter
                  ColumnAlign.END -> Alignment.TopEnd
                },
              modifier =
                Modifier.then(if (header) Modifier.background(headerColor) else Modifier)
                  .drawBehind {
                    if (r > 0) drawLine(lineColor, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
                  }
                  .padding(horizontal = CellPaddingH, vertical = CellPaddingV)
                  .semantics {
                    if (header) heading()
                    collectionItemInfo = CollectionItemInfo(r, 1, c, 1)
                  },
            ) {
              cells.getOrNull(c)?.let { cell ->
                MarkdownTableBasicText(
                  content = content,
                  cell = cell,
                  style =
                    style.copy(
                      fontWeight = if (header) FontWeight.SemiBold else style.fontWeight,
                      textAlign =
                        when (align) {
                          ColumnAlign.START -> TextAlign.Start
                          ColumnAlign.CENTER -> TextAlign.Center
                          ColumnAlign.END -> TextAlign.End
                        },
                    ),
                  maxLines = Int.MAX_VALUE,
                  overflow = TextOverflow.Clip,
                )
              }
            }
          }
        }
      }
    }
  }
}

/** Lays out row-major cells on a grid: content-sized columns, rows as tall as their tallest cell. */
@Composable
private fun TableGrid(columns: Int, viewport: Int, content: @Composable () -> Unit) {
  Layout(content) { measurables, _ ->
    val rowCount = measurables.size / columns
    val natural = IntArray(columns)
    for (i in measurables.indices) {
      val c = i % columns
      natural[c] = maxOf(natural[c], measurables[i].maxIntrinsicWidth(Constraints.Infinity))
    }
    val widths = tableColumnWidths(natural, MinColumn.roundToPx(), MaxColumn.roundToPx(), viewport)
    val heights = IntArray(rowCount)
    for (i in 0 until rowCount * columns) {
      val r = i / columns
      heights[r] = maxOf(heights[r], measurables[i].minIntrinsicHeight(widths[i % columns]))
    }
    val placeables =
      List(rowCount * columns) { i ->
        measurables[i].measure(Constraints.fixed(widths[i % columns], heights[i / columns]))
      }
    layout(widths.sum(), heights.sum()) {
      var y = 0
      for (r in 0 until rowCount) {
        var x = 0
        for (c in 0 until columns) {
          placeables[r * columns + c].placeRelative(x, y)
          x += widths[c]
        }
        y += heights[r]
      }
    }
  }
}

/**
 * Fades out whichever horizontal edge still has content to scroll to. The fade grows with the
 * distance left to scroll, so it appears smoothly. RTL-aware: "forward" is the left edge in RTL.
 */
private fun Modifier.horizontalEdgeFade(state: ScrollState, width: Dp): Modifier =
  graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
      drawContent()
      val w = width.toPx()
      if (w <= 0f || state.maxValue == 0) return@drawWithContent
      val back = (state.value / w).coerceIn(0f, 1f)
      val forward = ((state.maxValue - state.value) / w).coerceIn(0f, 1f)
      val rtl = layoutDirection == LayoutDirection.Rtl
      val left = if (rtl) forward else back
      val right = if (rtl) back else forward
      if (left > 0f) {
        drawRect(
          Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 1f - left), Color.Black), 0f, w),
          size = Size(w, size.height),
          blendMode = BlendMode.DstIn,
        )
      }
      if (right > 0f) {
        drawRect(
          Brush.horizontalGradient(
            listOf(Color.Black, Color.Black.copy(alpha = 1f - right)),
            size.width - w,
            size.width,
          ),
          topLeft = Offset(size.width - w, 0f),
          size = Size(w, size.height),
          blendMode = BlendMode.DstIn,
        )
      }
    }
