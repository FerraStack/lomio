package dev.lomio.engine

import dev.lomio.data.settings.AppSettings

/**
 * User-facing messages from the engine layer, which has no Android resources. The app fills
 * them from string resources (AppContainer); the English defaults keep plain JVM tests simple.
 */
data class EngineText(
  val loadModelFirst: String = "Load a model first",
  val generationStopped: String = "Generation stopped",
  val generationFailed: String = "Generation failed",
  val modelNotFound: String = "Model not found",
  /** Takes the model's name. */
  val notDownloaded: (String) -> String = { "$it is not downloaded" },
  val loadFailed: String = "Failed to load model",
  /** The global system prompt while the user has never edited it (localized default). */
  val defaultSystemPrompt: String = AppSettings.DEFAULT_SYSTEM_PROMPT,
  val gpuCrashNotice: String = "The app closed while loading a model on the GPU, so GPU acceleration was turned off.",
)
