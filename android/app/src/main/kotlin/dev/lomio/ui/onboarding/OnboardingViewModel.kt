package dev.lomio.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lomio.AppContainer
import dev.lomio.data.db.ModelState
import dev.lomio.domain.MemoryFit
import dev.lomio.domain.ModelShape
import dev.lomio.domain.Onboarding
import dev.lomio.engine.LoadOutcome
import dev.lomio.ui.models.RECOMMENDED
import dev.lomio.ui.models.toHfFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OnboardingViewModel(private val c: AppContainer) : ViewModel() {
  private val mem = c.memory.current()
  private val options =
    RECOMMENDED.map { r ->
      StarterOption(
        r,
        MemoryFit.verdict(MemoryFit.estimate(ModelShape(r.sizeBytes, null, null, null, null), 4096), mem.availableBytes, mem.totalBytes),
      )
    }
  private val step = MutableStateFlow(OnboardingStep.WELCOME)
  private val selected = MutableStateFlow(Onboarding.recommend(options.map { it.model.sizeBytes }, options.map { it.fit }))
  private val modelId = MutableStateFlow<String?>(null)

  /** True once a download was started here: onboarding then stays up although a model exists. */
  val started: StateFlow<Boolean>
    get() = _started
  private val _started = MutableStateFlow(false)
  private val startError = MutableStateFlow<String?>(null)
  private val loading = MutableStateFlow(false)

  val state: StateFlow<OnboardingUiState> =
    combine(step, selected, combine(modelId, c.models.observeModels(), startError, ::Triple), loading) { st, sel, (id, models, err), busy ->
      val m = id?.let { mid -> models.firstOrNull { it.id == mid } }
      val download =
        when {
          err != null -> StarterDownload.Failed(err)
          m == null -> null
          m.state == ModelState.READY -> StarterDownload.Ready
          m.state == ModelState.ERROR -> StarterDownload.Failed(m.errorMessage ?: "Download failed")
          else -> StarterDownload.Running(m.bytesDone, m.sizeBytes, m.errorMessage)
        }
      OnboardingUiState(st, Onboarding.ramLabel(mem.totalBytes), options, sel, download, busy)
    }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState(ramLabel = Onboarding.ramLabel(mem.totalBytes), options = options))

  fun next() {
    step.value = OnboardingStep.entries[(step.value.ordinal + 1).coerceAtMost(OnboardingStep.entries.lastIndex)]
  }

  fun back() {
    step.value = OnboardingStep.entries[(step.value.ordinal - 1).coerceAtLeast(0)]
  }

  fun select(i: Int) {
    selected.value = i
  }

  fun download() =
    viewModelScope.launch {
      val pick = options.getOrNull(selected.value) ?: return@launch
      startError.value = null
      step.value = OnboardingStep.DOWNLOAD
      runCatching { c.models.download(pick.model.toHfFile()) }
        .onSuccess {
          modelId.value = it.id
          _started.value = true
        }
        .onFailure { startError.value = it.message ?: "Couldn't start the download" }
    }

  fun retry() =
    viewModelScope.launch {
      val id = modelId.value
      if (id == null) download() else {
        startError.value = null
        c.models.resume(id)
      }
    }

  /** Loads the downloaded model and finishes onboarding. */
  fun startChat(onDone: () -> Unit) =
    viewModelScope.launch {
      val id = modelId.value ?: return@launch
      loading.value = true
      val r = c.modelManager.load(id)
      loading.value = false
      if (r is LoadOutcome.Failed) startError.value = r.message
      else finish(onDone)
    }

  fun finish(onDone: () -> Unit) =
    viewModelScope.launch {
      c.settings.update { it.copy(onboardingDone = true) }
      onDone()
    }
}
