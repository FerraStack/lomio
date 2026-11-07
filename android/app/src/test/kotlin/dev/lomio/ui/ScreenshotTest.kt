package dev.lomio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import dev.lomio.ui.models.RecommendedCardState
import dev.lomio.ui.models.HfSearchScreen
import dev.lomio.ui.models.HfUiState
import dev.lomio.ui.models.HfActions
import dev.lomio.ui.models.HfFileRow
import dev.lomio.data.hf.HfModel
import dev.lomio.data.hf.HfFile
import dev.lomio.ui.bench.BenchmarkScreen
import dev.lomio.ui.bench.BenchUiState
import dev.lomio.llama.BenchResult
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
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
      recommended =
        listOf(
          RecommendedCardState(RECOMMENDED[0], Fit.FITS, added = false),
          RecommendedCardState(RECOMMENDED[3], Fit.TIGHT, added = false),
        ),
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

  private val markdownChat =
    chat.copy(
      title = "Compare phone SoCs",
      isGenerating = false,
      messages =
        listOf(
          UiMessage("u1", MessageRole.USER, "Compare three phone chips for running local models."),
          UiMessage(
            "a1",
            MessageRole.ASSISTANT,
            """
            ## Summary
            For **local LLMs**, memory bandwidth matters more than core count.

            | Chip | RAM | Decode (3B Q4_0) |
            |---|---|---|
            | Snapdragon 8 Elite | 12–16 GB | ~18 tok/s |
            | Tensor G4 | 12–16 GB | ~11 tok/s |
            | Dimensity 9300 | 12–16 GB | ~15 tok/s |

            ### What to pick
            1. Prefer **Q4_0** for 3B+ models (ARM repacking).
            2. Keep the context at 4K unless you need more.
            3. Leave ~30% of RAM free.

            - Thermal throttling hits after a few minutes
            - `mmap` helps load time
            """.trimIndent(),
            stats = GenerationStats(120, 0, 187, 640, 88.0, 16.4, StopReason.EOS),
          ),
        ),
    )

  @Test fun chatMarkdownLight() = shoot("chat_markdown", dark = false) { ChatScreen(markdownChat, ChatActions()) }

  @Test fun chatMarkdownDark() = shoot("chat_markdown", dark = true) { ChatScreen(markdownChat, ChatActions()) }

  /**
   * A reply streamed in token-sized updates through the incremental parser (issue #2), captured
   * mid-stream inside an open code fence: earlier blocks are final, the fence is still growing.
   */
  private fun shootStreamed(dark: Boolean) {
    val reply =
      """
      Here is a small **LRU cache** in Kotlin. It keeps the most recently used entries and drops the oldest one when full.

      1. `LinkedHashMap` with `accessOrder = true` moves an entry to the end on every read.
      2. `removeEldestEntry` evicts once the size passes the limit.

      ```kotlin
      class LruCache<K, V>(private val max: Int) :
        LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<K, V>) =
          size > max
      }

      fun main() {
        val cache = LruCache<String, Int>(2)
        cache["a"] = 1
        cache["b"] = 2
      """.trimIndent()
    var content by mutableStateOf("")
    compose.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        LomioTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
          ChatScreen(
            chat.copy(
              title = "LRU cache in Kotlin",
              messages =
                listOf(
                  UiMessage("u1", MessageRole.USER, "Write a tiny LRU cache in Kotlin."),
                  UiMessage("a1", MessageRole.ASSISTANT, content, status = MessageStatus.STREAMING),
                ),
            ),
            ChatActions(),
          )
        }
      }
    }
    var i = 0
    while (i < reply.length) {
      i = minOf(reply.length, i + 24)
      content = reply.substring(0, i)
      compose.waitForIdle()
    }
    compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_streamed_${if (dark) "dark" else "light"}.png")
  }

  @Test fun chatStreamedLight() = shootStreamed(dark = false)

  @Test fun chatStreamedDark() = shootStreamed(dark = true)
  private val tableChat =
    chat.copy(
      title = "Models for an 8 GB phone",
      isGenerating = false,
      messages =
        listOf(
          UiMessage("u1", MessageRole.USER, "Which models run well on a phone with 8 GB of RAM?"),
          UiMessage(
            "a1",
            MessageRole.ASSISTANT,
            """
            Five good picks, smallest first:

            | Model | Params | Quant | Size | Best for |
            |---|---:|:---:|---:|---|
            | Qwen3 1.7B | 1.7B | Q4_K_M | 1.1 GB | Quick answers, any phone |
            | Llama 3.2 3B Instruct | 3.2B | Q4_0 | 1.9 GB | General chat |
            | Gemma 3 4B | 3.9B | Q4_0 | 2.4 GB | Writing and summaries |
            | Phi-4 mini | 3.8B | Q4_K_M | 2.5 GB | Reasoning, math |
            | Qwen3 8B | 8.2B | Q4_0 | 4.7 GB | Best quality; tight on 8 GB |

            Small tables fill the width:

            | Setting | Value |
            |---|---|
            | Context | 4096 |
            | Threads | 4 |
            """.trimIndent(),
            stats = GenerationStats(140, 0, 210, 610, 90.0, 17.1, StopReason.EOS),
          ),
        ),
    )

  @Test fun chatTableLight() = shoot("chat_table", dark = false) { ChatScreen(tableChat, ChatActions()) }

  @Test fun chatTableDark() = shoot("chat_table", dark = true) { ChatScreen(tableChat, ChatActions()) }

  /** Same chat with the wide table scrolled to its end: the fade moves to the start edge. */
  private fun shootTableScrolled(dark: Boolean) {
    compose.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        LomioTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
          ChatScreen(tableChat, ChatActions())
        }
      }
    }
    compose.waitForIdle()
    compose
      .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
      .onFirst()
      .performSemanticsAction(SemanticsActions.ScrollBy) { it(10_000f, 0f) }
    compose.waitForIdle()
    compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_table_scrolled_${if (dark) "dark" else "light"}.png")
  }

  @Test fun chatTableScrolledLight() = shootTableScrolled(dark = false)

  @Test fun chatTableScrolledDark() = shootTableScrolled(dark = true)
  /** A long pasted prompt being processed before the first token (issue #3 adds the progress). */
  private val promptProgressChat =
    chat.copy(
      title = "Summarise these notes",
      messages =
        listOf(
          UiMessage(
            "u1",
            MessageRole.USER,
            "Summarise these meeting notes in five bullet points:\n\n" +
              "We reviewed the Q3 roadmap. Downloads move to a foreground job on Android 14, the " +
              "Hugging Face token goes to the Keystore, and tablets get a two-pane layout. " +
              "Vision models are next once the projector loading is stable…",
          ),
          UiMessage("a1", MessageRole.ASSISTANT, "", status = MessageStatus.STREAMING, promptProgress = 0.42f),
        ),
    )

  @Test
  fun chatPromptProgressLight() =
    shoot("chat_prompt_progress", dark = false) { ChatScreen(promptProgressChat, ChatActions()) }

  @Test
  fun chatPromptProgressDark() =
    shoot("chat_prompt_progress", dark = true) { ChatScreen(promptProgressChat, ChatActions()) }

  private val loadFailed =
    ChatUiState(
      modelName = null,
      modelStatus = ModelStatus.FAILED,
      downloadedModels = listOf(model.copy(loaded = false)),
      lastModel = model,
      loadError = "Couldn't load Qwen3 1.7B: failed to create context (n_ctx=32768); try a smaller context",
    )

  @Test fun loadErrorLight() = shoot("load_error", dark = false) { ChatScreen(loadFailed, ChatActions()) }

  @Test fun loadErrorDark() = shoot("load_error", dark = true) { ChatScreen(loadFailed, ChatActions()) }

  @OptIn(ExperimentalRoborazziApi::class)
  private fun shootModelSheet(dark: Boolean) {
    val state = chat.copy(isGenerating = false, downloadedModels = listOf(model, PickerModel("m2", "Gemma 3 4B", "Q4_0", 2_370_000_000, false)))
    compose.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        LomioTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
          ChatScreen(state, ChatActions())
        }
      }
    }
    // Same path as a user: tap the title to open the model sheet.
    compose.onNodeWithContentDescription("Choose model").performClick()
    compose.waitForIdle()
    captureScreenRoboImage("build/outputs/roborazzi/model_sheet_${if (dark) "dark" else "light"}.png")
  }

  @Test fun modelSheetLight() = shootModelSheet(dark = false)

  @Test fun modelSheetDark() = shootModelSheet(dark = true)

  private val hf =
    HfUiState(
      query = "qwen3",
      results =
        listOf(
          HfModel("unsloth/Qwen3-1.7B-GGUF", "unsloth", 1_240_000, 310, "2026-10-02", false),
          HfModel("unsloth/Qwen3-4B-GGUF", "unsloth", 2_810_000, 512, "2026-10-02", false),
          HfModel("Qwen/Qwen3-8B-GGUF", "Qwen", 905_000, 220, "2026-09-11", false),
          HfModel("bartowski/Qwen3-14B-GGUF", "bartowski", 402_000, 140, "2026-09-20", false),
          HfModel("meta-llama/Llama-3.2-3B-Instruct-GGUF", "meta-llama", 5_100, 12, "2026-08-01", true),
        ),
    )

  @Test fun hfSearchLight() = shoot("hf_search", dark = false) { HfSearchScreen(hf, HfActions()) }

  @Test fun hfSearchDark() = shoot("hf_search", dark = true) { HfSearchScreen(hf, HfActions()) }

  private val hfRepo =
    HfUiState(
      repo = "unsloth/Qwen3-1.7B-GGUF",
      files =
        listOf(
          HfFileRow(HfFile("unsloth/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_0.gguf", 1_056_782_912, "c8"), Fit.FITS, false),
          HfFileRow(HfFile("unsloth/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_K_M.gguf", 1_107_409_472, "b1"), Fit.FITS, true),
          HfFileRow(HfFile("unsloth/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q8_0.gguf", 1_834_000_000, "d4"), Fit.TIGHT, false),
          HfFileRow(HfFile("unsloth/Qwen3-1.7B-GGUF", "Qwen3-1.7B-BF16.gguf", 4_070_000_000, "e5"), Fit.WONT_FIT, false),
        ),
    )

  @Test fun hfRepoLight() = shoot("hf_repo", dark = false) { HfSearchScreen(hfRepo, HfActions()) }

  private val bench =
    BenchUiState(
      modelName = "qwen3 1.7B Q4_K - Medium",
      profile = dev.lomio.ui.bench.BenchProfile.DEFAULT,
      result = BenchResult(512, 128, 3, 168.4, 3.1, 17.6, 0.4, "qwen3 1.7B Q4_K - Medium", "CPU"),
      systemInfo = "CPU : NEON = 1 | ARM_FMA = 1 | FP16_VA = 1 | DOTPROD = 1 | MATMUL_INT8 = 1 | KLEIDIAI = 1 | REPACK = 1",
    )

  @Test fun benchmarkLight() = shoot("benchmark", dark = false) { BenchmarkScreen(bench, {}, {}, {}) }

  @Test fun benchmarkDark() = shoot("benchmark", dark = true) { BenchmarkScreen(bench, {}, {}, {}) }

  @Test
  fun drawerLight() =
    shoot("drawer", dark = false) { DrawerContent(Destination.CHAT, "c1", "", history, DrawerActions()) }

  @Test
  fun drawerDark() =
    shoot("drawer", dark = true) { DrawerContent(Destination.CHAT, "c1", "", history, DrawerActions()) }
}
