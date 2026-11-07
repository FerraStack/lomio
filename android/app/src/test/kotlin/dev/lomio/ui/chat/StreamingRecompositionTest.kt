package dev.lomio.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import dev.lomio.data.settings.ThemeMode
import dev.lomio.ui.theme.LomioTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Recomposition counter for issue #2: while a reply streams, finished blocks must not
 * recompose. Counts every Markdown block composition through [LocalBlockCompositionCounter].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class StreamingRecompositionTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun finishedBlocksAreNotRecomposedWhileStreaming() {
    val text = sampleReply(16)
    var content by mutableStateOf("")
    val perBlock = HashMap<Int, Int>()
    var total = 0
    compose.setContent {
      CompositionLocalProvider(
        LocalInspectionMode provides true,
        LocalBlockCompositionCounter provides { node ->
          total++
          perBlock[node.startOffset] = (perBlock[node.startOffset] ?: 0) + 1
        },
      ) {
        LomioTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) { MarkdownText(content, streaming = true) }
      }
    }

    // ~50 ms UI cadence: one update per 8 token-sized chunks.
    val parts = chunks(text)
    var updates = 0
    val sb = StringBuilder()
    parts.forEachIndexed { i, c ->
      sb.append(c)
      if (i % 8 == 7 || i == parts.lastIndex) {
        content = sb.toString()
        compose.waitForIdle()
        updates++
      }
    }

    val blocks = perBlock.size
    // Full re-render would be ~ updates * blocks / 2 compositions. Incremental: the open tail
    // (a block plus its line breaks) recomposes per update, every other block about once.
    val naive = updates.toLong() * blocks / 2
    val msg = "total=$total updates=$updates blocks=$blocks naive≈$naive perBlock=$perBlock"
    assertTrue(msg, total <= 4 * updates + 2 * blocks)
    assertTrue(msg, total * 5 < naive)
    // The first heading is finished after a handful of updates and never recomposes after that.
    assertTrue(msg, (perBlock[0] ?: 0) <= 6)

    // The final text is fully rendered.
    compose.onAllNodesWithText("second point 16", substring = true).fetchSemanticsNodes().let {
      assertTrue("last list item missing", it.isNotEmpty())
    }
  }
}
