package dev.lomio.ui.l10n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
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
import dev.lomio.domain.Storage.LastUsed
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
import dev.lomio.ui.models.RecommendedCardState
import dev.lomio.ui.settings.SettingsScreen
import dev.lomio.ui.storage.StorageActions
import dev.lomio.ui.storage.StorageModelRow
import dev.lomio.ui.storage.StorageScreen
import dev.lomio.ui.storage.StorageUiState
import dev.lomio.ui.theme.LomioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The main screens in Russian and Ukrainian (long words, plural forms), and in a right-to-left
 * layout (`ldrtl`, English strings) to check that nothing is pinned to the left.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class LocaleScreenshotTest {
  @get:Rule val compose = createComposeRule()

  private fun shoot(name: String, dark: Boolean = false, rtl: Boolean = false, content: @Composable () -> Unit) {
    compose.setContent {
      // Forced explicitly: Robolectric's `ldrtl` qualifier doesn't reach Compose's layout direction.
      val direction = if (rtl) LayoutDirection.Rtl else LocalLayoutDirection.current
      CompositionLocalProvider(LocalInspectionMode provides true, LocalLayoutDirection provides direction) {
        LomioTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) { content() }
      }
    }
    compose.waitForIdle()
    compose.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${if (dark) "dark" else "light"}.png")
  }

  private val model = PickerModel("m1", "Qwen3 1.7B", "Q4_K_M", 1_280_000_000, loaded = true)

  private fun chat(question: String, answer: String, followUp: String) =
    ChatUiState(
      conversationId = "c1",
      title = question,
      modelName = "Qwen3 1.7B",
      modelStatus = ModelStatus.LOADED,
      downloadedModels = listOf(model),
      supportsThinking = true,
      messages =
        listOf(
          UiMessage("u1", MessageRole.USER, question),
          UiMessage(
            "a1",
            MessageRole.ASSISTANT,
            answer,
            reasoning = "…",
            stats = GenerationStats(38, 0, 41, 420, 95.0, 18.3, StopReason.EOS),
          ),
          UiMessage("u2", MessageRole.USER, followUp),
          UiMessage("a2", MessageRole.ASSISTANT, "", status = MessageStatus.STOPPED),
        ),
    )

  private val ruChat =
    chat(
      "Как развернуть строку в Kotlin?",
      "Используйте **`reversed()`** из стандартной библиотеки:\n\n```kotlin\nprintln(\"Lomio\".reversed())\n```",
      "А без стандартной библиотеки?",
    )

  private val ukChat =
    chat(
      "Як розвернути рядок у Kotlin?",
      "Скористайтеся **`reversed()`** зі стандартної бібліотеки:\n\n```kotlin\nprintln(\"Lomio\".reversed())\n```",
      "А без стандартної бібліотеки?",
    )

  private val models =
    ModelsUiState(
      models =
        listOf(
          ModelCardState(
            id = "m1", name = "Qwen3 1.7B", subtitle = "unsloth/Qwen3-1.7B-GGUF", quant = "Q4_K_M",
            sizeBytes = 1_280_000_000, params = 1_720_000_000, contextLength = 40960, loaded = true, fit = Fit.FITS,
          ),
          ModelCardState(
            id = "m2", name = "Gemma 3 4B", subtitle = "unsloth/gemma-3-4b-it-GGUF", quant = "Q4_0",
            sizeBytes = 2_370_000_000, state = ModelState.PAUSED, bytesDone = 1_020_000_000, fit = Fit.TIGHT,
          ),
        ),
      recommended = listOf(RecommendedCardState(RECOMMENDED[0], Fit.FITS, added = false)),
    )

  private val storage =
    StorageUiState(
      loading = false,
      volumeBytes = 128_000_000_000,
      freeBytes = 41_300_000_000,
      models =
        listOf(
          StorageModelRow("g4", "Gemma 3 4B", "Q4_0", 2_370_065_536, LastUsed.MonthsAgo(2), unused = true),
          StorageModelRow("l3", "Llama 3.2 3B", "Q4_0", 1_921_909_280, LastUsed.DaysAgo(3), loaded = true),
          StorageModelRow("q3", "Qwen3 1.7B", "Q4_K_M", 1_107_409_472, LastUsed.WeeksAgo(5), unused = true),
        ),
      chatsBytes = 3_400_000,
      cacheBytes = 12_600_000,
      leftoverBytes = 640_000_000,
      leftoverCount = 2,
    )

  private val now = 1_700_000_000_000

  private val history =
    listOf(
      HistoryBucket.PINNED to listOf(ConversationSummary("p", "Поїздка до Львова", true, now, null)),
      HistoryBucket.TODAY to listOf(ConversationSummary("c1", "New chat", false, now, null)),
      HistoryBucket.THIS_WEEK to listOf(ConversationSummary("c2", "Що таке KV-кеш?", false, now, null)),
    )

  @Test @Config(qualifiers = "+ru") fun ruChat() = shoot("ru_chat") { ChatScreen(ruChat, ChatActions()) }

  @Test @Config(qualifiers = "+ru") fun ruChatDark() = shoot("ru_chat", dark = true) { ChatScreen(ruChat, ChatActions()) }

  @Test @Config(qualifiers = "+ru") fun ruModels() = shoot("ru_models") { ModelsScreen(models, ModelsActions()) }

  @Test @Config(qualifiers = "+ru") fun ruStorage() = shoot("ru_storage") { StorageScreen(storage, StorageActions()) }

  @Test @Config(qualifiers = "+ru") fun ruSettings() = shoot("ru_settings") { SettingsScreen(AppSettings(), {}, {}) }

  @Test @Config(qualifiers = "+uk") fun ukChat() = shoot("uk_chat") { ChatScreen(ukChat, ChatActions()) }

  @Test @Config(qualifiers = "+uk") fun ukDrawer() = shoot("uk_drawer") { DrawerContent(Destination.CHAT, "c1", "", history, DrawerActions()) }

  @Test @Config(qualifiers = "+uk") fun ukStorage() = shoot("uk_storage", dark = true) { StorageScreen(storage, StorageActions()) }

  @Test @Config(qualifiers = "+uk") fun ukSettings() = shoot("uk_settings", dark = true) { SettingsScreen(AppSettings(), {}, {}) }

  @Test fun rtlChat() = shoot("rtl_chat", rtl = true) { ChatScreen(ruChat, ChatActions()) }

  @Test fun rtlStorage() = shoot("rtl_storage", rtl = true) { StorageScreen(storage, StorageActions()) }
}
