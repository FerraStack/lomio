package dev.lomio.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lomio.AppContainer
import dev.lomio.api.ApiServer
import dev.lomio.api.ApiServerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class ApiServerViewModel(private val c: AppContainer) : ViewModel() {
  private val modelName = c.modelManager.activeModelId.mapLatest { id -> id?.let { c.models.get(it)?.displayName } }

  val state: StateFlow<ApiServerUiState> =
    combine(c.apiServer.settings.prefs, c.apiServer.status, c.apiServer.stats, modelName) { prefs, status, stats, model ->
        ApiServerUiState(
          enabled = prefs.enabled,
          status = status,
          port = prefs.port,
          lan = prefs.lan,
          apiKey = prefs.apiKey,
          requireKeyLocal = prefs.requireKeyLocal,
          lanAddress = if (prefs.lan) ApiServerController.lanAddress() else null,
          modelName = model,
          served = stats.served,
          active = stats.active,
        )
      }
      .flowOn(Dispatchers.Default)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ApiServerUiState())

  fun setEnabled(on: Boolean) = c.apiServer.setEnabled(on)

  fun setPort(port: Int) = c.apiServer.change { it.copy(port = port) }

  fun setLan(on: Boolean) =
    c.apiServer.change { it.copy(lan = on, apiKey = it.apiKey.ifBlank { if (on) ApiServer.newApiKey() else "" }) }

  fun setRequireKeyLocal(on: Boolean) =
    c.apiServer.change { it.copy(requireKeyLocal = on, apiKey = it.apiKey.ifBlank { if (on) ApiServer.newApiKey() else "" }) }

  fun newKey() = c.apiServer.change { it.copy(apiKey = ApiServer.newApiKey()) }
}
