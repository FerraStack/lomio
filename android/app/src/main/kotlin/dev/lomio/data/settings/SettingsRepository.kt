package dev.lomio.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.lomio.domain.GenerationSettings
import dev.lomio.domain.LoadSettings
import dev.lomio.domain.SettingsJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
  val theme: ThemeMode = ThemeMode.SYSTEM,
  val dynamicColor: Boolean = true,
  val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
  val showStats: Boolean = true,
  val keepScreenOn: Boolean = true,
  val autoLoadLastModel: Boolean = true,
  /** Unload the model after this many minutes in the background; 0 = never. */
  val unloadAfterMinutes: Int = 10,
  val lastModelId: String? = null,
  // TODO(M2): move the token to Android Keystore-backed storage (RESEARCH.md §7).
  val hfToken: String = "",
  val generation: GenerationSettings = GenerationSettings.DEFAULTS,
  val load: LoadSettings = LoadSettings(nCtx = 4096, nThreads = 0, nBatch = 512, useMmap = true),
) {
  companion object {
    const val DEFAULT_SYSTEM_PROMPT = "You are a helpful, concise assistant."
  }
}

class SettingsRepository(private val store: DataStore<Preferences>) {
  val settings: Flow<AppSettings> = store.data.map(::fromPrefs)

  suspend fun current(): AppSettings = settings.first()

  suspend fun update(transform: (AppSettings) -> AppSettings) {
    store.edit { prefs ->
      val next = transform(fromPrefs(prefs))
      prefs[THEME] = next.theme.name
      prefs[DYNAMIC_COLOR] = next.dynamicColor
      prefs[SYSTEM_PROMPT] = next.systemPrompt
      prefs[SHOW_STATS] = next.showStats
      prefs[KEEP_SCREEN_ON] = next.keepScreenOn
      prefs[AUTO_LOAD] = next.autoLoadLastModel
      prefs[UNLOAD_AFTER] = next.unloadAfterMinutes
      if (next.lastModelId != null) prefs[LAST_MODEL] = next.lastModelId else prefs.remove(LAST_MODEL)
      prefs[HF_TOKEN] = next.hfToken
      prefs[GENERATION] = SettingsJson.encodeGen(next.generation)!!
      prefs[LOAD] = SettingsJson.encodeLoad(next.load)!!
    }
  }

  private fun fromPrefs(p: Preferences): AppSettings {
    val d = AppSettings()
    return AppSettings(
      theme = p[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
      dynamicColor = p[DYNAMIC_COLOR] ?: d.dynamicColor,
      systemPrompt = p[SYSTEM_PROMPT] ?: d.systemPrompt,
      showStats = p[SHOW_STATS] ?: d.showStats,
      keepScreenOn = p[KEEP_SCREEN_ON] ?: d.keepScreenOn,
      autoLoadLastModel = p[AUTO_LOAD] ?: d.autoLoadLastModel,
      unloadAfterMinutes = p[UNLOAD_AFTER] ?: d.unloadAfterMinutes,
      lastModelId = p[LAST_MODEL],
      hfToken = p[HF_TOKEN] ?: d.hfToken,
      generation = d.generation.overlay(SettingsJson.decodeGen(p[GENERATION])),
      load = d.load.overlay(SettingsJson.decodeLoad(p[LOAD])),
    )
  }

  private companion object {
    val THEME = stringPreferencesKey("theme")
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
    val SHOW_STATS = booleanPreferencesKey("show_stats")
    val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    val AUTO_LOAD = booleanPreferencesKey("auto_load_last_model")
    val UNLOAD_AFTER = intPreferencesKey("unload_after_minutes")
    val LAST_MODEL = stringPreferencesKey("last_model_id")
    val HF_TOKEN = stringPreferencesKey("hf_token")
    val GENERATION = stringPreferencesKey("generation_json")
    val LOAD = stringPreferencesKey("load_json")
  }
}
