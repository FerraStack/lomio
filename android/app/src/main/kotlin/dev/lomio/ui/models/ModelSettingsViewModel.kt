package dev.lomio.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lomio.AppContainer
import dev.lomio.domain.Fit
import dev.lomio.domain.GenerationSettings
import dev.lomio.domain.LoadSettings
import dev.lomio.domain.SettingsJson
import dev.lomio.domain.SettingsResolver
import dev.lomio.llama.ChatTemplates
import dev.lomio.ui.components.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How a stored template override maps to the three template modes. */
object TemplateChoice {
  fun modeOf(stored: String?): TemplateMode =
    when {
      stored.isNullOrBlank() -> TemplateMode.GGUF
      ChatTemplates.PRESETS.any { it.source == stored } -> TemplateMode.PRESET
      else -> TemplateMode.CUSTOM
    }

  fun presetOf(stored: String?): String = ChatTemplates.PRESETS.firstOrNull { it.source == stored }?.id ?: ChatTemplates.CHATML.id

  /** The template the engine will actually use, for the preview. */
  fun effective(mode: TemplateMode, presetId: String, custom: String, gguf: String?): String =
    when (mode) {
      TemplateMode.GGUF -> gguf?.takeIf { it.isNotBlank() } ?: ChatTemplates.CHATML.source
      TemplateMode.PRESET -> (ChatTemplates.preset(presetId) ?: ChatTemplates.CHATML).source
      TemplateMode.CUSTOM -> custom
    }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ModelSettingsViewModel(
  private val c: AppContainer,
  private val modelId: String,
  private val formatTemplate: (String) -> String = { ChatTemplates.format(it) },
) : ViewModel() {
  private val writes = Mutex()
  private val version = MutableStateFlow(0)
  /** Template mode picked in this session; null = derived from what's stored. */
  private val modeOverride = MutableStateFlow<TemplateMode?>(null)
  private val customDraft = MutableStateFlow<String?>(null)

  private val model = c.models.observeModels().map { list -> list.firstOrNull { it.id == modelId } }
  private val stored = c.models.observeSettings(modelId)

  private val base =
    combine(model, stored, c.settings.settings, c.modelManager.engineState, version) { m, s, app, _, v ->
      val gen = SettingsJson.decodeGen(s?.genJson) ?: GenerationSettings()
      val load = SettingsJson.decodeLoad(s?.loadJson) ?: LoadSettings()
      val loaded = c.modelManager.activeModelId.value == modelId
      val active = c.modelManager.activeLoadSettings
      ModelSettingsUiState(
        modelId = modelId,
        name = m?.displayName.orEmpty(),
        subtitle = listOfNotNull(m?.quant, m?.sizeBytes?.takeIf { it > 0 }?.let(Format::bytes)).joinToString(" · "),
        version = v,
        needsReload = loaded && active != null && active != SettingsResolver.load(app.load, load),
        systemPrompt = s?.systemPrompt.orEmpty(),
        globalSystemPrompt = app.systemPrompt,
        genBase = SettingsResolver.modelBase(app.generation),
        gen = gen,
        loadBase = app.load,
        load = load.copy(chatTemplate = null),
        trainedContext = m?.nCtxTrain,
        ggufHasTemplate = !m?.chatTemplate.isNullOrBlank(),
      ) to (load.chatTemplate to m?.chatTemplate)
    }

  private val withTemplate =
    combine(base, modeOverride, customDraft) { (state, templates), modeSel, draft ->
      val (storedTemplate, gguf) = templates
      val mode = modeSel ?: TemplateChoice.modeOf(storedTemplate)
      val custom = draft ?: storedTemplate.takeIf { TemplateChoice.modeOf(it) == TemplateMode.CUSTOM }.orEmpty()
      val presetId = TemplateChoice.presetOf(storedTemplate)
      state.copy(templateMode = mode, presetId = presetId, customTemplate = custom) to
        TemplateChoice.effective(mode, presetId, custom, gguf)
    }

  private val preview =
    withTemplate
      .map { it.second }
      .distinctUntilChanged()
      .debounce(250)
      .mapLatest { src ->
        if (src.isBlank()) return@mapLatest TemplatePreview.Failed("the template is empty")
        withContext(Dispatchers.Default) {
          runCatching { TemplatePreview.Rendered(formatTemplate(src)) }
            .getOrElse { TemplatePreview.Failed(it.message ?: it.toString()) }
        }
      }
      .onStart { emit(TemplatePreview.Loading) }

  private val fit =
    combine(model, base) { m, (s, _) -> m to (s.load.nCtx ?: s.loadBase.nCtx ?: 4096) }
      .distinctUntilChanged()
      .mapLatest { (m, ctx) -> m?.let { runCatching { c.modelManager.fit(it, ctx).first }.getOrNull() } }
      .onStart { emit(null as Fit?) }

  val state: StateFlow<ModelSettingsUiState> =
    combine(withTemplate, preview, fit) { (s, _), p, f -> s.copy(preview = p, contextFit = f) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelSettingsUiState(modelId = modelId))

  private fun edit(
    gen: (GenerationSettings) -> GenerationSettings = { it },
    load: (LoadSettings) -> LoadSettings = { it },
    prompt: (String?) -> String? = { it },
  ) =
    viewModelScope.launch {
      writes.withLock {
        val s = c.models.settings(modelId)
        val g = gen(SettingsJson.decodeGen(s?.genJson) ?: GenerationSettings())
        val l = load(SettingsJson.decodeLoad(s?.loadJson) ?: LoadSettings())
        c.models.saveSettings(
          modelId,
          g.takeIf { it != GenerationSettings() },
          l.takeIf { it != LoadSettings() },
          prompt(s?.systemPrompt),
        )
      }
    }

  fun rename(name: String) {
    if (name.isBlank()) return
    viewModelScope.launch { c.models.rename(modelId, name) }
  }

  fun setSystemPrompt(text: String) = edit(prompt = { text })

  fun setGeneration(g: GenerationSettings) = edit(gen = { g })

  /** [l] carries the load fields shown on screen; the stored template is kept. */
  fun setLoad(l: LoadSettings) = edit(load = { old -> l.copy(chatTemplate = old.chatTemplate) })

  fun setTemplateMode(mode: TemplateMode) {
    val current = state.value
    modeOverride.value = mode
    when (mode) {
      TemplateMode.GGUF -> edit(load = { it.copy(chatTemplate = null) })
      TemplateMode.PRESET -> setPreset(current.presetId)
      TemplateMode.CUSTOM ->
        viewModelScope.launch {
          // Start from the template in effect now, so the user edits rather than starts blank.
          val start =
            current.customTemplate.ifBlank {
              TemplateChoice.effective(current.templateMode, current.presetId, "", c.models.get(modelId)?.chatTemplate)
            }
          customDraft.value = start
          version.value++ // re-seed the editor with the prefilled text
          edit(load = { it.copy(chatTemplate = start) })
        }
    }
  }

  fun setPreset(id: String) {
    val src = (ChatTemplates.preset(id) ?: ChatTemplates.CHATML).source
    edit(load = { it.copy(chatTemplate = src) })
  }

  fun setCustomTemplate(text: String) {
    customDraft.value = text
    edit(load = { it.copy(chatTemplate = text.ifBlank { null }) })
  }

  fun reload() =
    viewModelScope.launch {
      if (c.chatService.state.value.isGenerating) c.chatService.stop()
      c.modelManager.load(modelId)
    }

  fun reset() =
    viewModelScope.launch {
      writes.withLock { c.models.saveSettings(modelId, null, null, null) }
      modeOverride.value = null
      customDraft.value = null
      version.value++
    }
}
