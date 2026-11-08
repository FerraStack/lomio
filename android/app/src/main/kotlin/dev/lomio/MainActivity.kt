package dev.lomio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.lomio.data.settings.AppSettings
import dev.lomio.launch.LaunchAction
import dev.lomio.launch.LaunchIntents
import kotlinx.coroutines.flow.MutableStateFlow
import dev.lomio.ui.LomioRoot
import dev.lomio.ui.SettingsViewModel
import dev.lomio.ui.theme.LomioTheme

class MainActivity : ComponentActivity() {
  private val container
    get() = (application as LomioApp).container

  private val settingsVm: SettingsViewModel by viewModels {
    viewModelFactory { initializer { SettingsViewModel(container) } }
  }

  /** A shortcut or tile request not yet handled by the UI. */
  private val launch = MutableStateFlow<LaunchAction?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    // Not again after a configuration change: the request was handled the first time.
    if (savedInstanceState == null) launch.value = LaunchIntents.parse(intent)
    setContent {
      val pending by launch.collectAsStateWithLifecycle()
      val settings by settingsVm.settings.collectAsStateWithLifecycle()
      val s = settings ?: AppSettings()
      LomioTheme(themeMode = s.theme, dynamicColor = s.dynamicColor) {
        LomioRoot(container, settingsVm, launch = pending, onLaunchHandled = { launch.value = null })
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    // Handled right here; no setIntent(): getIntent() stays the launch intent (which is also
    // how ActivityScenario recognises this activity in instrumented tests).
    LaunchIntents.parse(intent)?.let { launch.value = it }
  }
}
