package dev.lomio

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
import dev.lomio.ui.LomioRoot
import dev.lomio.ui.SettingsViewModel
import dev.lomio.ui.theme.LomioTheme

class MainActivity : ComponentActivity() {
  private val container
    get() = (application as LomioApp).container

  private val settingsVm: SettingsViewModel by viewModels {
    viewModelFactory { initializer { SettingsViewModel(container) } }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    setContent {
      val settings by settingsVm.settings.collectAsStateWithLifecycle()
      val s = settings ?: AppSettings()
      LomioTheme(themeMode = s.theme, dynamicColor = s.dynamicColor) {
        LomioRoot(container, settingsVm)
      }
    }
  }
}
