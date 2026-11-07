package dev.lomio.ui

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
import dev.lomio.data.db.MessageStatus
import dev.lomio.data.db.ModelState
import dev.lomio.data.settings.AppSettings
import dev.lomio.data.settings.ThemeMode
import dev.lomio.domain.Fit
import dev.lomio.domain.HistoryBucket
import dev.lomio.domain.UiMessage
import dev.lomio.llama.GenerationStats
import dev.lomio.llama.StopReason
import dev.lomio.ui.chat.ChatActions
import dev.lomio.ui.chat.ChatScreen
import dev.lomio.ui.chat.ChatUiState
import dev.lomio.ui.chat.ModelStatus
import dev.lomio.ui.chat.PickerModel
import dev.lomio.ui.drawer.Destination
import dev.lomio.ui.drawer.DrawerActions
import dev.lomio.ui.drawer.DrawerContent
import dev.lomio.ui.models.ModelCardState
import dev.lomio.ui.models.ModelsActions
import dev.lomio.ui.models.ModelsScreen
import dev.lomio.ui.models.ModelsUiState
import dev.lomio.ui.models.RECOMMENDED
import dev.lomio.ui.settings.SettingsScreen
import dev.lomio.ui.theme.LomioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi screenshots of the main screens with fake state (no native library, no DB).
 * CI records them with `-Proborazzi.test.record=true` (set in gradle.properties), uploads
 * them as an artifact and posts them on the PR.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class ScreenshotTest {
  @get:Rule val compose = createComposeRule()

  private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
    compose.setContent {
      // Inspection mode makes the markdown renderer and code highlighter work synchronously.
      CompositionLocalProvider(LocalInspectionMode provides true) {
        LomioTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) { content() }
      }
    }
    compose.waitForIdle()
    compose.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${if (dark) "dark" else "light"}.png")
  }

  private val model = PickerModel("m1", "Qwen3 1.7B", "Q4_K_M", 1_280_000_000, loaded = true)

  private val chat =
    ChatUiState(
      conversationId = "c1",
      title = "Reverse a string in Kotlin",
      modelName = "Qwen3 1.7B",
      modelStatus = ModelStatus.LOADED,
      downloadedModels = listOf(model),
      supportsThinking = true,
      isGenerating = true,
      messages =
        listOf(
          UiMessage("u1", MessageRole.USER, "How do I reverse a string in Kotlin?"),
          UiMessage(
            "a1",
            MessageRole.ASSISTANT,
            "Use the standard library's **`reversed()`**:\n\n```kotlin\nval s = \"Lomio\"\nprintln(s.reversed()) // oimoL\n```\n\nIt works on any `CharSequence`.",
            reasoning = "The user wants the idiomatic way; reversed() exists on CharSequence.",
            stats = GenerationStats(38, 0, 41, 420, 95.0, 18.3, StopReason.EOS),
          ),
          UiMessage("u2", MessageRole.USER, "And without the standard library?"),
          UiMessage(
            "a2",
            MessageRole.ASSISTANT,
            "Walk the string from the end:\n\n- build a `StringBuilder`\n- append characters in reverse\n\n```kotlin\nfun rev(s: String): String {\n  val sb = StringBuilder()\n  for (i in s.indices.reversed()) sb.append(s[i])",
            status = MessageStatus.STREAMING,
          ),
        ),
    )

  @Test fun chatLight() = shoot("chat", dark = false) { ChatScreen(chat, ChatActions()) }

  @Test fun chatDark() = shoot("chat", dark = true) { ChatScreen(chat, ChatActions()) }

  @Test
  fun chatEmptyLight() =
    shoot("chat_empty", dark = false) {
      ChatScreen(ChatUiState(downloadedModels = listOf(model.copy(loaded = false)), lastModel = model), ChatActions())
    }

  private val models =
    ModelsUiState(
      models =
        listOf(
          ModelCardState(
            id = "m1",
            name = "Qwen3 1.7B",
            subtitle = "unsloth/Qwen3-1.7B-GGUF",
            quant = "Q4_K_M",
            sizeBytes = 1_280_000_000,
            params = 1_720_000_000,
            contextLength = 40960,
            loaded = true,
            fit = Fit.FITS,
          ),
          ModelCardState(
            id = "m2",
            name = "gemma 3 4b it",
            subtitle = "unsloth/gemma-3-4b-it-GGUF",
            quant = "Q4_0",
            sizeBytes = 2_370_000_000,
            state = ModelState.DOWNLOADING,
            bytesDone = 1_020_000_000,
            fit = Fit.TIGHT,
          ),
        ),
      recommended = RECOMMENDED.take(2),
    )

  @Test fun modelsLight() = shoot("models", dark = false) { ModelsScreen(models, ModelsActions()) }

  @Test fun modelsDark() = shoot("models", dark = true) { ModelsScreen(models, ModelsActions()) }

  @Test fun settingsLight() = shoot("settings", dark = false) { SettingsScreen(AppSettings(), {}, {}) }

  @Test fun settingsDark() = shoot("settings", dark = true) { SettingsScreen(AppSettings(), {}, {}) }

  private val now = 1_794_000_000_000L
  private val history =
    listOf(
      HistoryBucket.PINNED to listOf(ConversationSummary("p", "Trip plan: Kyoto", true, now, null)),
      HistoryBucket.TODAY to
        listOf(
          ConversationSummary("c1", "Reverse a string in Kotlin", false, now, null),
          ConversationSummary("c2", "Explain KV cache", false, now, null),
        ),
      HistoryBucket.YESTERDAY to listOf(ConversationSummary("c3", "Risotto without wine", false, now, null)),
    )

  @Test
  fun drawerLight() =
    shoot("drawer", dark = false) { DrawerContent(Destination.CHAT, "c1", "", history, DrawerActions()) }

  @Test
  fun drawerDark() =
    shoot("drawer", dark = true) { DrawerContent(Destination.CHAT, "c1", "", history, DrawerActions()) }
}
