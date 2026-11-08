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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import dev.lomio.data.AttachmentStore
import org.junit.rules.TemporaryFolder
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.lomio.ui.settings.SettingsScreen
import dev.lomio.ui.settings.GpuControls
import dev.lomio.domain.GpuSupport
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import dev.lomio.ui.theme.LomioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import dev.lomio.ui.search.SearchActions
import dev.lomio.ui.search.SearchResult
import dev.lomio.ui.search.SearchScreen
import dev.lomio.ui.search.SearchUiState
import androidx.compose.ui.test.performScrollTo
import dev.lomio.domain.GenerationSettings
import dev.lomio.domain.LoadSettings
import dev.lomio.ui.models.ModelSettingsActions
import dev.lomio.ui.models.ModelSettingsScreen
import dev.lomio.ui.models.ModelSettingsUiState
import dev.lomio.ui.models.TemplateMode
import dev.lomio.ui.models.TemplatePreview
import dev.lomio.data.hf.TokenCheck
import dev.lomio.ui.settings.TokenCheckState

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
  @get:Rule val tmp = TemporaryFolder()

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
            id = "v1",
            name = "SmolVLM 256M Instruct",
            subtitle = "ggml-org/SmolVLM-256M-Instruct-GGUF",
            quant = "Q8_0",
            sizeBytes = 278_824_384,
            params = 256_000_000,
            contextLength = 8192,
            fit = Fit.FITS,
            vision = true,
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

  /** A regenerated reply (2 versions) under an edited question (2 versions). */
  private val versionsChat =
    chat.copy(
      title = "Name ideas for a bakery",
      isGenerating = false,
      messages =
        listOf(
          UiMessage("u1", MessageRole.USER, "Give me three names for a small bakery in Lviv.", versionIndex = 1, versionCount = 2),
          UiMessage(
            "a1",
            MessageRole.ASSISTANT,
            "1. **Lviv Crumb** — warm and local\n2. **Rynok Rye** — after the market square\n3. **Morning Kifli** — friendly and simple",
            stats = GenerationStats(36, 0, 48, 380, 90.0, 17.9, StopReason.EOS),
            versionIndex = 1,
            versionCount = 2,
          ),
        ),
    )

  @Test fun chatVersionsLight() = shoot("chat_versions", dark = false) { ChatScreen(versionsChat, ChatActions()) }

  @Test fun chatVersionsDark() = shoot("chat_versions", dark = true) { ChatScreen(versionsChat, ChatActions()) }

  @OptIn(ExperimentalRoborazziApi::class)
  private fun shootMessageMenu(dark: Boolean) {
    compose.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        LomioTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
          ChatScreen(versionsChat, ChatActions())
        }
      }
    }
    compose.onNodeWithText("Give me three names for a small bakery in Lviv.").performTouchInput { longClick() }
    compose.waitForIdle()
    captureScreenRoboImage("build/outputs/roborazzi/chat_message_menu_${if (dark) "dark" else "light"}.png")
  }

  @Test fun chatMessageMenuLight() = shootMessageMenu(dark = false)

  @Test fun chatMessageMenuDark() = shootMessageMenu(dark = true)

  /** Edit from the long-press menu: the composer shows what is being edited. */
  @Test
  fun chatEditingLight() {
    compose.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        LomioTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) { ChatScreen(versionsChat, ChatActions()) }
      }
    }
    compose.onNodeWithText("Give me three names for a small bakery in Lviv.").performTouchInput { longClick() }
    compose.onNodeWithText("Edit").performClick()
    compose.waitForIdle()
    compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_editing_light.png")
  }

  @OptIn(ExperimentalRoborazziApi::class)
  private fun shootDeleteDialog(dark: Boolean) {
    compose.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        LomioTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
          ChatScreen(versionsChat, ChatActions())
        }
      }
    }
    compose.onNodeWithContentDescription("More actions").performClick()
    compose.onNodeWithText("Delete").performClick()
    compose.waitForIdle()
    captureScreenRoboImage("build/outputs/roborazzi/chat_delete_reply_${if (dark) "dark" else "light"}.png")
  }

  @Test fun chatDeleteReplyLight() = shootDeleteDialog(dark = false)

  @Test fun chatDeleteReplyDark() = shootDeleteDialog(dark = true)

  private val searchState =
    SearchUiState(
      query = "kyoto",
      searched = true,
      results =
        listOf(
          SearchResult("m1", "p", "Trip plan: Kyoto", MessageRole.USER, now, "Plan three days in \uE000Kyoto\uE001 in April with a day trip to Nara"),
          SearchResult(
            "m2",
            "p",
            "Trip plan: Kyoto",
            MessageRole.ASSISTANT,
            now - 60_000,
            "…Day 1: Fushimi Inari early, then Gion. In \uE000Kyoto\uE001 the buses get crowded, so buy a…",
          ),
          SearchResult("m3", "c9", "Japanese rail passes", MessageRole.ASSISTANT, now - 86_400_000L * 9, "…Tokyo to \uE000Kyoto\uE001 on the Nozomi takes about 2 h 15 min…"),
        ),
    )

  @Test fun searchResultsLight() = shoot("search_results", dark = false) { SearchScreen(searchState, SearchActions()) }

  @Test fun searchResultsDark() = shoot("search_results", dark = true) { SearchScreen(searchState, SearchActions()) }

  @Test
  fun searchEmptyLight() =
    shoot("search_empty", dark = false) { SearchScreen(SearchUiState(query = "risotto", searched = true), SearchActions()) }

  @Test
  fun drawerSearchLight() =
    shoot("drawer_search", dark = false) {
      DrawerContent(Destination.CHAT, "c1", "kyoto", history.take(1), DrawerActions())
    }

  private val modelSettings =
    ModelSettingsUiState(
      modelId = "m1",
      name = "Qwen3 1.7B",
      subtitle = "Q4_K_M · 1.1 GB",
      needsReload = true,
      globalSystemPrompt = "You are a helpful assistant.",
      genBase = dev.lomio.domain.SettingsResolver.modelBase(GenerationSettings(temperature = 0.7f)),
      gen = GenerationSettings(temperature = 0.6f, topK = 20),
      load = LoadSettings(nCtx = 8192),
      trainedContext = 40960,
      contextFit = Fit.FITS,
      templateMode = TemplateMode.PRESET,
      presetId = "chatml",
      preview =
        TemplatePreview.Rendered(
          "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n<|im_start|>user\nHello!<|im_end|>\n" +
            "<|im_start|>assistant\nHi! How can I help?<|im_end|>\n<|im_start|>user\nWhat is 2 + 2?<|im_end|>\n" +
            "<|im_start|>assistant\n"
        ),
    )

  @Test fun modelSettingsLight() = shoot("model_settings", dark = false) { ModelSettingsScreen(modelSettings, ModelSettingsActions()) }

  @Test fun modelSettingsDark() = shoot("model_settings", dark = true) { ModelSettingsScreen(modelSettings, ModelSettingsActions()) }

  /** Scrolled to the chat template section: a custom template that fails to render. */
  private fun shootTemplate(dark: Boolean) {
    val state =
      modelSettings.copy(
        needsReload = false,
        templateMode = TemplateMode.CUSTOM,
        customTemplate = "{%- for message in messages -%}\n{{ '<|' + message.role + '|>' + message.content }}\n{%- endfor %",
        preview = TemplatePreview.Failed("expected '%}' at line 3"),
      )
    compose.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        LomioTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
          ModelSettingsScreen(state, ModelSettingsActions())
        }
      }
    }
    compose.onNodeWithText("Reset to defaults").performScrollTo()
    compose.waitForIdle()
    compose.onRoot().captureRoboImage("build/outputs/roborazzi/model_settings_template_${if (dark) "dark" else "light"}.png")
  }

  @Test fun modelSettingsTemplateLight() = shootTemplate(dark = false)

  @Test fun modelSettingsTemplateDark() = shootTemplate(dark = true)

  @OptIn(ExperimentalRoborazziApi::class)
  private fun shootGenSheet(dark: Boolean) {
    val state =
      chat.copy(
        isGenerating = false,
        genBase = dev.lomio.domain.SettingsResolver.chatBase(GenerationSettings(), GenerationSettings(temperature = 0.6f)),
        gen = GenerationSettings(temperature = 1.1f, maxTokens = 512),
      )
    compose.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        LomioTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
          ChatScreen(state, ChatActions())
        }
      }
    }
    compose.onNodeWithContentDescription("More").performClick()
    compose.onNodeWithText("Generation settings (this chat)").performClick()
    compose.waitForIdle()
    captureScreenRoboImage("build/outputs/roborazzi/chat_gen_sheet_${if (dark) "dark" else "light"}.png")
  }

  @Test fun chatGenSheetLight() = shootGenSheet(dark = false)

  @Test fun chatGenSheetDark() = shootGenSheet(dark = true)

  private val downloads =
    ModelsUiState(
      models =
        listOf(
          ModelCardState(
            id = "d1", name = "gemma 3 4b it", subtitle = "unsloth/gemma-3-4b-it-GGUF", quant = "Q4_0",
            sizeBytes = 2_370_000_000, state = ModelState.DOWNLOADING, bytesDone = 1_420_000_000, fit = Fit.FITS,
          ),
          ModelCardState(
            id = "d2", name = "Llama 3.2 3B Instruct", subtitle = "bartowski/Llama-3.2-3B-Instruct-GGUF", quant = "Q4_0",
            sizeBytes = 1_921_909_280, state = ModelState.DOWNLOADING, bytesDone = 310_000_000, fit = Fit.FITS,
            error = dev.lomio.data.download.DownloadRunner.NO_NOTIFICATION,
          ),
          ModelCardState(
            id = "d3", name = "Qwen3 8B", subtitle = "Qwen/Qwen3-8B-GGUF", quant = "Q4_K_M",
            sizeBytes = 5_030_000_000, state = ModelState.QUEUED, fit = Fit.TIGHT,
          ),
          ModelCardState(
            id = "d4", name = "Phi-4 mini", subtitle = "unsloth/Phi-4-mini-instruct-GGUF", quant = "Q4_K_M",
            sizeBytes = 2_490_000_000, state = ModelState.PAUSED, bytesDone = 600_000_000, fit = Fit.FITS,
          ),
        ),
    )

  @Test fun modelsDownloadsLight() = shoot("models_downloads", dark = false) { ModelsScreen(downloads, ModelsActions()) }

  @Test fun modelsDownloadsDark() = shoot("models_downloads", dark = true) { ModelsScreen(downloads, ModelsActions()) }

  private fun shootToken(dark: Boolean, check: TokenCheckState) {
    compose.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        LomioTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
          SettingsScreen(AppSettings(hfToken = "hf_0123456789abcdef"), {}, {}, tokenCheck = check)
        }
      }
    }
    compose.onNodeWithText("Test token").performScrollTo()
    compose.waitForIdle()
    compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings_token_${if (dark) "dark" else "light"}.png")
  }

  @Test fun settingsTokenLight() = shootToken(false, TokenCheckState.Done(TokenCheck.Valid("lomio-dev")))

  @Test fun settingsTokenDark() = shootToken(true, TokenCheckState.Done(TokenCheck.Rejected(401)))

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

  private val smolRepo = "ggml-org/SmolVLM-256M-Instruct-GGUF"
  private val hfVisionRepo =
    HfUiState(
      repo = smolRepo,
      projector = HfFile(smolRepo, "mmproj-SmolVLM-256M-Instruct-Q8_0.gguf", 103_769_856, "7e"),
      files =
        listOf(
          HfFileRow(HfFile(smolRepo, "mmproj-SmolVLM-256M-Instruct-Q8_0.gguf", 103_769_856, "7e"), null, false),
          HfFileRow(HfFile(smolRepo, "SmolVLM-256M-Instruct-Q8_0.gguf", 175_054_528, "2a"), Fit.FITS, false, vision = true),
          HfFileRow(HfFile(smolRepo, "mmproj-SmolVLM-256M-Instruct-f16.gguf", 190_031_616, "08"), null, false),
          HfFileRow(HfFile(smolRepo, "SmolVLM-256M-Instruct-f16.gguf", 327_809_728, "2f"), Fit.FITS, false, vision = true),
        ),
    )

  @Test fun hfRepoVisionLight() = shoot("hf_repo_vision", dark = false) { HfSearchScreen(hfVisionRepo, HfActions()) }

  @Test fun hfRepoVisionDark() = shoot("hf_repo_vision", dark = true) { HfSearchScreen(hfVisionRepo, HfActions()) }

  /** A small drawn scene (sky, sun, hill, house) saved through the real attachment store. */
  private fun sceneImage(): String {
    val w = 800
    val h = 600
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(AndroidColor.rgb(120, 180, 235))
    paint.color = AndroidColor.rgb(250, 205, 60)
    canvas.drawCircle(640f, 130f, 70f, paint)
    paint.color = AndroidColor.rgb(90, 160, 80)
    canvas.drawOval(-200f, 380f, 1000f, 900f, paint)
    paint.color = AndroidColor.rgb(200, 70, 60)
    canvas.drawRect(260f, 300f, 460f, 460f, paint)
    paint.color = AndroidColor.rgb(110, 60, 40)
    canvas.drawPath(Path().apply { moveTo(240f, 300f); lineTo(360f, 210f); lineTo(480f, 300f); close() }, paint)
    val store = AttachmentStore(tmp.newFolder("attachments"))
    return store.file(store.save(bmp)).absolutePath
  }

  private fun visionChat(image: String) =
    ChatUiState(
      conversationId = "c2",
      title = "What is in this picture?",
      modelName = "SmolVLM 256M Instruct",
      modelStatus = ModelStatus.LOADED,
      downloadedModels = listOf(model.copy(id = "v1", name = "SmolVLM 256M Instruct", vision = true)),
      supportsVision = true,
      attachments = listOf(image),
      messages =
        listOf(
          UiMessage("u1", MessageRole.USER, "What is in this picture?", images = listOf(image)),
          UiMessage(
            "a1",
            MessageRole.ASSISTANT,
            "A small red house with a brown roof on a green hill, under a blue sky with a yellow sun.",
            stats = GenerationStats(112, 0, 24, 2100, 61.0, 21.4, StopReason.EOS),
          ),
        ),
    )

  @Test fun chatVisionLight() = sceneImage().let { img -> shoot("chat_vision", dark = false) { ChatScreen(visionChat(img), ChatActions()) } }

  @Test fun chatVisionDark() = sceneImage().let { img -> shoot("chat_vision", dark = true) { ChatScreen(visionChat(img), ChatActions()) } }

  private val bench =
    BenchUiState(
      modelName = "qwen3 1.7B Q4_K - Medium",
      profile = dev.lomio.ui.bench.BenchProfile.DEFAULT,
      result = BenchResult(512, 128, 3, 168.4, 3.1, 17.6, 0.4, "qwen3 1.7B Q4_K - Medium", "CPU"),
      systemInfo = "CPU : NEON = 1 | ARM_FMA = 1 | FP16_VA = 1 | DOTPROD = 1 | MATMUL_INT8 = 1 | KLEIDIAI = 1 | REPACK = 1",
    )

  @Test fun benchmarkLight() = shoot("benchmark", dark = false) { BenchmarkScreen(bench, {}, {}, {}) }

  @Test fun benchmarkDark() = shoot("benchmark", dark = true) { BenchmarkScreen(bench, {}, {}, {}) }

  /** GPU on: where the model runs is shown above the results (here: the OpenCL backend). */
  private val benchGpu =
    bench.copy(
      backend = "GPU (OpenCL): QUALCOMM Adreno(TM) 750 · 28/28 layers",
      result = bench.result!!.copy(backend = "GPU (OpenCL): QUALCOMM Adreno(TM) 750 · 28/28 layers"),
    )

  @Test fun benchmarkGpuLight() = shoot("benchmark_gpu", dark = false) { BenchmarkScreen(benchGpu, {}, {}, {}) }

  /** GPU on but unusable (e.g. the x86_64 emulator): the CPU fallback and its reason. */
  private val benchFallback =
    bench.copy(
      backend = "CPU",
      gpuNote = "this build has no GPU backend for this CPU architecture",
      result = bench.result!!.copy(backend = "CPU"),
    )

  @Test fun benchmarkGpuFallbackLight() = shoot("benchmark_gpu_fallback", dark = false) { BenchmarkScreen(benchFallback, {}, {}, {}) }

  private fun gpuSettings(enabled: Boolean, gpu: GpuSupport) =
    @Composable {
      androidx.compose.material3.Surface {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) {
          GpuControls(AppSettings(gpuEnabled = enabled, gpuLayers = 99), gpu) {}
        }
      }
    }

  @Test
  fun settingsGpuLight() =
    shoot("settings_gpu", dark = false) { gpuSettings(true, GpuSupport(true, "Adreno GPU with OpenCL"))() }

  @Test
  fun settingsGpuDark() =
    shoot("settings_gpu", dark = true) { gpuSettings(true, GpuSupport(true, "Adreno GPU with OpenCL"))() }

  @Test
  fun settingsGpuUnavailableLight() =
    shoot("settings_gpu_unavailable", dark = false) {
      gpuSettings(false, GpuSupport.evaluate("arm64-v8a", "Google", "tensor", hasOpenClLibrary = false))()
    }

  @Test
  fun drawerLight() =
    shoot("drawer", dark = false) { DrawerContent(Destination.CHAT, "c1", "", history, DrawerActions()) }

  @Test
  fun drawerDark() =
    shoot("drawer", dark = true) { DrawerContent(Destination.CHAT, "c1", "", history, DrawerActions()) }
}
