package dev.lomio.ui.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lomio.AppContainer
import dev.lomio.data.ModelRepository
import dev.lomio.data.db.ModelSource
import dev.lomio.data.hf.HfFile
import dev.lomio.data.hf.HfSort
import dev.lomio.domain.MemoryFit
import dev.lomio.domain.ModelShape
import dev.lomio.engine.LoadOutcome
import dev.lomio.llama.EngineState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Our own small starter list (RESEARCH.md §2.2: PocketPal's device-rules repo has no license).
 * Q4_0 for ≥ 3B models (repacked for ARM dotprod/i8mm), Q4_K_M below. Sizes and sha256 are the
 * Hugging Face LFS values; the download is verified against them.
 */
val RECOMMENDED =
  listOf(
    RecommendedModel(
      "ggml-org/gemma-3-1b-it-GGUF", "gemma-3-1b-it-Q4_K_M.gguf", "Gemma 3 1B",
      "Small and fast; good for 4–6 GB phones.", "Q4_K_M", 806_058_240,
      "8ccc5cd1f1b3602548715ae25a66ed73fd5dc68a210412eea643eb20eb75a135",
    ),
    RecommendedModel(
      "unsloth/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_K_M.gguf", "Qwen3 1.7B",
      "Reasoning model with a Think switch.", "Q4_K_M", 1_107_409_472,
      "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897",
    ),
    RecommendedModel(
      "bartowski/Llama-3.2-3B-Instruct-GGUF", "Llama-3.2-3B-Instruct-Q4_0.gguf", "Llama 3.2 3B",
      "Solid all-rounder for 8 GB phones.", "Q4_0", 1_921_909_280,
      "4d491003bf6b470e4d04233824c5c939b76d6c81974ba17a3ff207343e1d8e85",
    ),
    RecommendedModel(
      "unsloth/gemma-3-4b-it-GGUF", "gemma-3-4b-it-Q4_0.gguf", "Gemma 3 4B",
      "Best quality here; needs 8 GB+ RAM.", "Q4_0", 2_370_065_536,
      "a3aa2db653fc95923d76e5d9039861d56f10dfb3c906afe6bcf8f72340ef2cbf",
    ),
  )

fun RecommendedModel.toHfFile() = HfFile(repo, file, sizeBytes, sha256)

@OptIn(FlowPreview::class)
class ModelsViewModel(private val c: AppContainer) : ViewModel() {
  private val message = MutableStateFlow<String?>(null)

  val state: StateFlow<ModelsUiState> =
    combine(c.models.observeModels(), c.modelManager.activeModelId, c.modelManager.engineState, message) {
        models, activeId, engine, msg ->
        val loadingPath = (engine as? EngineState.Loading)?.path
        val mem = c.memory.current()
        ModelsUiState(
          models =
            models.map { m ->
              ModelCardState(
                id = m.id,
                name = m.displayName,
                subtitle = if (m.source == ModelSource.HF) m.hfRepo.orEmpty() else "Imported file",
                quant = m.quant,
                sizeBytes = m.sizeBytes,
                params = m.nParams,
                contextLength = m.nCtxTrain,
                arch = m.arch,
                layers = m.nLayers,
                filePath = m.filePath,
                state = m.state,
                bytesDone = m.bytesDone,
                error = m.errorMessage,
                loaded = m.id == activeId,
                loading = loadingPath != null && loadingPath == m.filePath,
                fit =
                  MemoryFit.verdict(
                    MemoryFit.estimate(ModelRepository.shapeOf(m), DEFAULT_CTX),
                    mem.availableBytes,
                    mem.totalBytes,
                  ),
              )
            },
          recommended =
            RECOMMENDED.map { r ->
              RecommendedCardState(
                model = r,
                fit =
                  MemoryFit.verdict(
                    MemoryFit.estimate(ModelShape(r.sizeBytes, null, null, null, null), DEFAULT_CTX),
                    mem.availableBytes,
                    mem.totalBytes,
                  ),
                added = models.any { it.hfRepo == r.repo && it.hfFile == r.file },
              )
            },
          message = msg,
        )
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelsUiState())

  fun load(id: String) =
    viewModelScope.launch {
      when (val r = c.modelManager.load(id)) {
        is LoadOutcome.Loaded -> message.value = "Loaded ${r.info.description}"
        is LoadOutcome.Failed -> message.value = r.message
        is LoadOutcome.NeedsConfirmation -> Unit // app-wide confirmation dialog
      }
    }

  fun unload() = viewModelScope.launch { c.modelManager.unload() }

  fun delete(id: String) =
    viewModelScope.launch {
      if (c.modelManager.activeModelId.value == id) c.modelManager.unload()
      c.models.delete(id)
    }

  fun pause(id: String) = viewModelScope.launch { c.models.pause(id) }

  fun resume(id: String) = viewModelScope.launch { c.models.resume(id) }

  fun import(uri: Uri) =
    viewModelScope.launch {
      message.value = "Importing…"
      message.value =
        runCatching { c.models.importLocal(uri) }
          .fold({ "Imported ${it.displayName}" }, { "Import failed: ${it.message}" })
    }

  fun messageShown() {
    message.value = null
  }

  // --- Hugging Face search ---

  private val _hf = MutableStateFlow(HfUiState())
  val hf: StateFlow<HfUiState> = _hf.asStateFlow()
  private var searchJob: Job? = null

  fun startSearch() {
    if (_hf.value.results.isEmpty()) search(debounce = false)
  }

  fun setQuery(q: String) {
    _hf.update { it.copy(query = q) }
    search(debounce = true)
  }

  fun setSort(sort: HfSort) {
    _hf.update { it.copy(sort = sort) }
    search(debounce = false)
  }

  private fun search(debounce: Boolean) {
    searchJob?.cancel()
    searchJob =
      viewModelScope.launch {
        if (debounce) delay(350)
        _hf.update { it.copy(loading = true, error = null) }
        val s = _hf.value
        runCatching { c.hfClient.search(s.query, s.sort) }
          .onSuccess { r -> _hf.update { it.copy(loading = false, results = r) } }
          .onFailure { e -> _hf.update { it.copy(loading = false, error = e.message ?: "Search failed") } }
      }
  }

  fun openRepo(repo: String) {
    _hf.update { it.copy(repo = repo, files = emptyList(), filesLoading = true, error = null) }
    viewModelScope.launch {
      runCatching { c.hfClient.ggufFiles(repo) }
        .onSuccess { files ->
          val mem = c.memory.current()
          val existing = c.models.all().mapNotNull { m -> m.hfFile?.let { m.hfRepo to it } }.toSet()
          _hf.update {
            it.copy(
              filesLoading = false,
              files =
                files.map { f ->
                  // Without the header we only know the size: estimate with weights + overhead.
                  val fit =
                    MemoryFit.verdict(
                      MemoryFit.estimate(ModelShape(f.sizeBytes, null, null, null, null), DEFAULT_CTX),
                      mem.availableBytes,
                      mem.totalBytes,
                    )
                  HfFileRow(f, fit, (f.repo to f.path) in existing)
                },
            )
          }
        }
        .onFailure { e -> _hf.update { it.copy(filesLoading = false, error = e.message ?: "Failed to list files") } }
    }
  }

  /** Back inside the HF screen: repo → search. Returns false when the screen should close. */
  fun hfBack(): Boolean {
    if (_hf.value.repo == null) return false
    _hf.update { it.copy(repo = null, files = emptyList(), error = null) }
    return true
  }

  fun downloadRecommended(r: RecommendedModel) = download(r.toHfFile())

  fun download(file: HfFile) =
    viewModelScope.launch {
      runCatching { c.models.download(file) }
        .onSuccess {
          _hf.update { s -> s.copy(files = s.files.map { if (it.file == file) it.copy(alreadyAdded = true) else it }) }
          message.value = "Downloading ${file.fileName}"
        }
        .onFailure { message.value = it.message ?: "Download failed" }
    }

  companion object {
    const val DEFAULT_CTX = 4096
  }
}
