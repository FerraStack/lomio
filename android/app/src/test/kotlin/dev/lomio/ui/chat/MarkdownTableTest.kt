package dev.lomio.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import dev.lomio.data.settings.ThemeMode
import dev.lomio.ui.theme.LomioTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class TableLogicTest {
  @Test
  fun alignmentsFromDelimiterRow() {
    assertEquals(
      listOf(ColumnAlign.START, ColumnAlign.CENTER, ColumnAlign.END, ColumnAlign.START),
      parseColumnAlignments("|:---|:---:|---:|---|", 4),
    )
    // No outer pipes, fewer delimiters than columns.
    assertEquals(listOf(ColumnAlign.END, ColumnAlign.START), parseColumnAlignments(" --: ", 2))
  }

  @Test
  fun narrowTableStretchesToViewportInProportion() {
    val w = tableColumnWidths(intArrayOf(100, 300), minColumn = 50, maxColumn = 400, viewport = 800)
    assertEquals(800, w.sum())
    assertArrayEquals(intArrayOf(200, 600), w)
  }

  @Test
  fun cappedColumnGetsSpareWidthBackFirst() {
    // 1000 px of text in column 2 is capped at 400, but 700 px are free: it takes 600 of them.
    val w = tableColumnWidths(intArrayOf(100, 1000), minColumn = 50, maxColumn = 400, viewport = 700)
    assertArrayEquals(intArrayOf(100, 600), w)
  }

  @Test
  fun wideTableKeepsContentWidthsAndOverflows() {
    val natural = intArrayOf(120, 900, 80, 30, 200)
    val w = tableColumnWidths(natural, minColumn = 50, maxColumn = 400, viewport = 600)
    assertArrayEquals(intArrayOf(120, 400, 80, 50, 200), w)
    assertTrue(w.sum() > 600)
  }

  @Test
  fun emptyAndZeroViewportAreSafe() {
    assertEquals(0, tableColumnWidths(IntArray(0), 50, 400, 600).size)
    assertArrayEquals(intArrayOf(50), tableColumnWidths(intArrayOf(10), 50, 400, 0))
  }
}

/** The table scrolls horizontally only when it is wider than the message. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class MarkdownTableUiTest {
  @get:Rule val compose = createComposeRule()

  private fun render(md: String) {
    compose.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        LomioTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) { MarkdownText(md) }
      }
    }
    compose.waitForIdle()
  }

  private fun scrollRange() =
    compose
      .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
      .fetchSemanticsNodes()
      .single()
      .config[SemanticsProperties.HorizontalScrollAxisRange]

  @Test
  fun wideTableScrollsAndKeepsEveryCell() {
    render(
      """
      | Model | Params | Quant | Size | Notes |
      |---|---:|---|---:|---|
      | Qwen3 1.7B | 1.7B | Q4_K_M | 1.1 GB | Fast on any phone with 6 GB of RAM or more |
      | Gemma 3 4B | 3.9B | Q4_0 | 2.4 GB | Good balance |
      """.trimIndent()
    )
    assertTrue(scrollRange().maxValue() > 0f)
    // No ellipsis: the full text of the long cell is laid out.
    compose.onAllNodesWithText("Fast on any phone with 6 GB of RAM or more", substring = true).fetchSemanticsNodes().single()
  }

  @Test
  fun narrowTableDoesNotScroll() {
    render("| Key | Value |\n|---|---|\n| a | b |")
    assertEquals(0f, scrollRange().maxValue())
  }
}
