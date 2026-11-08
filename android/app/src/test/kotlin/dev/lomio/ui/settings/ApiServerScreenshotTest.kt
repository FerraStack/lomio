package dev.lomio.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import dev.lomio.api.ApiServerStatus
import dev.lomio.data.settings.ThemeMode
import dev.lomio.ui.theme.LomioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Local API server section of Settings in its main states (light and dark). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class ApiServerScreenshotTest {
  @get:Rule val compose = createComposeRule()

  private fun shoot(name: String, dark: Boolean, state: ApiServerUiState, actions: ApiServerActions = ApiServerActions()) {
    compose.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        LomioTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
          Surface(Modifier.fillMaxSize()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) { ApiServerSection(state, actions) }
          }
        }
      }
    }
    compose.waitForIdle()
    compose.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${if (dark) "dark" else "light"}.png")
  }

  private val running =
    ApiServerUiState(enabled = true, status = ApiServerStatus.Running(8080, lan = false), modelName = "Llama 3.2 3B", served = 3)

  private val lan =
    running.copy(
      status = ApiServerStatus.Running(8080, lan = true),
      lan = true,
      apiKey = "lomio-Qm3xT9vK2pL8wN4rZ7yB1cD6fH0jS5aE",
      lanAddress = "192.168.1.23",
      modelName = null,
      served = 0,
    )

  private val failed = ApiServerUiState(enabled = true, status = ApiServerStatus.Failed("Port 8080 is already in use. Pick another port."))

  @Test fun runningLight() = shoot("api_server", dark = false, running)

  @Test fun runningDark() = shoot("api_server", dark = true, running)

  @Test fun lanLight() = shoot("api_server_lan", dark = false, lan)

  @Test fun lanDark() = shoot("api_server_lan", dark = true, lan)

  @Test fun failedLight() = shoot("api_server_failed", dark = false, failed)

  @Test fun failedDark() = shoot("api_server_failed", dark = true, failed)

  @Test fun offLight() = shoot("api_server_off", dark = false, ApiServerUiState())

  private val localKey = running.copy(requireKeyLocal = true, apiKey = "lomio-Qm3xT9vK2pL8wN4rZ7yB1cD6fH0jS5aE")

  @Test fun localKeyLight() = shoot("api_server_local_key", dark = false, localKey)

  @Test fun localKeyDark() = shoot("api_server_local_key", dark = true, localKey)

  @Test
  fun theWholeRowTogglesTheServer() {
    var enabled: Boolean? = null
    shoot("api_server_off", dark = true, ApiServerUiState(), ApiServerActions(onEnabled = { enabled = it }))
    compose.onNode(hasText("OpenAI-compatible server") and isToggleable()).performClick()
    assertEquals(true, enabled)
  }
}
