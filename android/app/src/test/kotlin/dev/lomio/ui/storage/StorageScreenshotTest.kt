package dev.lomio.ui.storage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.lomio.data.settings.ThemeMode
import dev.lomio.domain.Storage.LastUsed
import dev.lomio.ui.theme.LomioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode

/** Roborazzi screenshots of the Storage screen (same conventions as ui/ScreenshotTest). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class StorageScreenshotTest {
  @get:Rule val compose = createComposeRule()

  private fun setContent(dark: Boolean, content: @Composable () -> Unit) {
    compose.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        LomioTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) { content() }
      }
    }
    compose.waitForIdle()
  }

  private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
    setContent(dark, content)
    compose.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${if (dark) "dark" else "light"}.png")
  }

  private val full =
    StorageUiState(
      loading = false,
      volumeBytes = 128_000_000_000,
      freeBytes = 41_300_000_000,
      models =
        listOf(
          StorageModelRow("g4", "Gemma 3 4B", "Q4_0", 2_370_065_536, LastUsed.MonthsAgo(2), unused = true),
          StorageModelRow("l3", "Llama 3.2 3B", "Q4_0", 1_921_909_280, LastUsed.Today, loaded = true),
          StorageModelRow("q3", "Qwen3 1.7B", "Q4_K_M", 1_107_409_472, LastUsed.WeeksAgo(5), unused = true),
          StorageModelRow("g1", "Gemma 3 1B", "Q4_K_M", 412_000_000, partial = true),
        ),
      chatsBytes = 3_400_000,
      chatCacheBytes = 184_000_000,
      cacheBytes = 12_600_000,
      leftoverBytes = 640_000_000,
      leftoverCount = 2,
    )

  @Test fun storageLight() = shoot("storage", dark = false) { StorageScreen(full, StorageActions()) }

  @Test fun storageDark() = shoot("storage", dark = true) { StorageScreen(full, StorageActions()) }

  private val emptyLow =
    StorageUiState(loading = false, volumeBytes = 64_000_000_000, freeBytes = 1_200_000_000, chatsBytes = 98_304)

  @Test fun storageEmptyLight() = shoot("storage_empty", dark = false) { StorageScreen(emptyLow, StorageActions()) }

  @Test fun storageEmptyDark() = shoot("storage_empty", dark = true) { StorageScreen(emptyLow, StorageActions()) }

  private fun shootOther(dark: Boolean) {
    setContent(dark) { StorageScreen(full, StorageActions()) }
    compose.onNodeWithTag(STORAGE_LIST_TAG).performScrollToNode(hasText("Chat caches"))
    compose.waitForIdle()
    compose.onRoot().captureRoboImage("build/outputs/roborazzi/storage_other_${if (dark) "dark" else "light"}.png")
  }

  @Test fun storageOtherLight() = shootOther(dark = false)

  @Test fun storageOtherDark() = shootOther(dark = true)

  @OptIn(ExperimentalRoborazziApi::class)
  private fun shootReview(dark: Boolean) {
    setContent(dark) { StorageScreen(full, StorageActions()) }
    compose.onNodeWithText("Review").performClick()
    compose.waitForIdle()
    captureScreenRoboImage("build/outputs/roborazzi/storage_review_${if (dark) "dark" else "light"}.png")
  }

  @Test fun storageReviewLight() = shootReview(dark = false)

  @Test fun storageReviewDark() = shootReview(dark = true)

  @Test
  fun reviewDeletesOnlyTheTickedModels() {
    var deleted: List<String>? = null
    setContent(dark = false) { StorageScreen(full, StorageActions(onDelete = { deleted = it })) }
    compose.onNodeWithText("Review").performClick()
    // Untick Qwen (pre-ticked with Gemma 4B), then confirm.
    compose.onNodeWithText("Used 5 weeks ago").performClick()
    compose.onNodeWithText("Delete · ", substring = true).performClick()
    assertEquals(listOf("g4"), deleted)
  }

  @Test
  fun deleteAsksFirst() {
    var deleted: List<String>? = null
    setContent(dark = false) { StorageScreen(full, StorageActions(onDelete = { deleted = it })) }
    compose.onNodeWithContentDescription("Delete Llama 3.2 3B").performClick()
    compose.onNodeWithText("The model will be unloaded first", substring = true).assertExists()
    assertEquals(null, deleted)
    compose.onNodeWithText("Delete").performClick()
    assertEquals(listOf("l3"), deleted)
  }
}
