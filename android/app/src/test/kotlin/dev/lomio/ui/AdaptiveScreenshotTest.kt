package dev.lomio.ui

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import dev.lomio.data.db.ConversationSummary
import dev.lomio.data.db.MessageRole
import dev.lomio.data.settings.ThemeMode
import dev.lomio.domain.HistoryBucket
import dev.lomio.domain.UiMessage
import dev.lomio.llama.GenerationStats
import dev.lomio.llama.StopReason
import dev.lomio.ui.chat.ChatActions
import dev.lomio.ui.chat.ChatScreen
import dev.lomio.ui.chat.ChatUiState
import dev.lomio.ui.chat.ModelStatus
import dev.lomio.ui.chat.PickerModel
import dev.lomio.ui.components.AdaptiveDrawerScaffold
import dev.lomio.ui.components.usePermanentDrawer
import dev.lomio.ui.drawer.Destination
import dev.lomio.ui.drawer.DrawerActions
import dev.lomio.ui.drawer.DrawerContent
import dev.lomio.ui.settings.SettingsScreen
import dev.lomio.data.settings.AppSettings
import dev.lomio.ui.theme.LomioTheme
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tablet / foldable / landscape layouts (RESEARCH.md §9.0): permanent drawer on expanded windows. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class AdaptiveScreenshotTest {
  @get:Rule val compose = createComposeRule()

  private val now = 1_794_000_000_000L
  private val history =
    listOf(
      HistoryBucket.PINNED to listOf(ConversationSummary("p", "Trip plan: Kyoto", true, now, null)),
      HistoryBucket.TODAY to
        listOf(
          ConversationSummary("c1", "Compare phone SoCs", false, now, null),
          ConversationSummary("c2", "Explain KV cache", false, now, null),
        ),
      HistoryBucket.YESTERDAY to listOf(ConversationSummary("c3", "Risotto without wine", false, now, null)),
    )

  private val chat =
    ChatUiState(
      conversationId = "c1",
      title = "Compare phone SoCs",
      modelName = "Qwen3 1.7B",
      modelStatus = ModelStatus.LOADED,
      downloadedModels = listOf(PickerModel("m1", "Qwen3 1.7B", "Q4_K_M", 1_280_000_000, loaded = true)),
      messages =
        listOf(
          UiMessage("u1", MessageRole.USER, "Compare three phone chips for running local models."),
          UiMessage(
            "a1",
            MessageRole.ASSISTANT,
            "For **local LLMs**, memory bandwidth matters more than core count.\n\n" +
              "| Chip | RAM | Decode (3B Q4_0) |\n|---|---|---|\n| Snapdragon 8 Elite | 12–16 GB | ~18 tok/s |\n" +
              "| Tensor G4 | 12–16 GB | ~11 tok/s |\n| Dimensity 9300 | 12–16 GB | ~15 tok/s |\n\n" +
              "1. Prefer **Q4_0** for 3B+ models.\n2. Keep the context at 4K unless you need more.",
            stats = GenerationStats(120, 0, 187, 640, 88.0, 16.4, StopReason.EOS),
          ),
        ),
    )

  private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
    compose.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        LomioTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
          AdaptiveDrawerScaffold(
            drawerState = rememberDrawerState(DrawerValue.Closed),
            gesturesEnabled = true,
            drawerContent = { DrawerContent(Destination.CHAT, "c1", "", history, DrawerActions()) },
            content = content,
          )
        }
      }
    }
    compose.waitForIdle()
    compose.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${if (dark) "dark" else "light"}.png")
  }

  @Test
  @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
  fun tabletChatLight() = shoot("tablet_chat", dark = false) { ChatScreen(chat, ChatActions()) }

  @Test
  @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
  fun tabletChatDark() = shoot("tablet_chat", dark = true) { ChatScreen(chat, ChatActions()) }

  @Test
  @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
  fun tabletSettingsLight() = shoot("tablet_settings", dark = false) { SettingsScreen(AppSettings(), {}, {}) }

  /** Landscape phone: wide but short, so the drawer stays modal; messages keep a readable width. */
  @Test
  @Config(qualifiers = "+land")
  fun phoneLandscapeLight() = shoot("phone_landscape", dark = false) { ChatScreen(chat, ChatActions()) }

  @Test
  @Config(qualifiers = "+land")
  fun phoneLandscapeDark() = shoot("phone_landscape", dark = true) { ChatScreen(chat, ChatActions()) }

  @Test
  fun breakpoints() {
    assertTrue(usePermanentDrawer(1280.dp, 800.dp)) // tablet landscape
    assertTrue(usePermanentDrawer(841.dp, 673.dp)) // unfolded foldable
    assertFalse(usePermanentDrawer(914.dp, 411.dp)) // phone landscape
    assertFalse(usePermanentDrawer(411.dp, 914.dp)) // phone portrait
    assertFalse(usePermanentDrawer(800.dp, 1280.dp)) // tablet portrait: medium width
  }
}
