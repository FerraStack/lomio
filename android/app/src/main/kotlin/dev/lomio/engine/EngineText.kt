package dev.lomio.engine

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
)
