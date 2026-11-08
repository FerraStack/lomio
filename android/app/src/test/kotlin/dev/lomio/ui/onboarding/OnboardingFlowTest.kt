package dev.lomio.ui.onboarding

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import dev.lomio.data.settings.ThemeMode
import dev.lomio.domain.Fit
import dev.lomio.ui.models.RECOMMENDED
import dev.lomio.ui.theme.LomioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class OnboardingFlowTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun welcomeToPickToDownload() {
    var state by mutableStateOf(
      OnboardingUiState(ramLabel = "8 GB", options = RECOMMENDED.map { StarterOption(it, Fit.FITS) }, selected = 3)
    )
    var downloaded = false
    compose.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        LomioTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
          OnboardingScreen(
            state,
            OnboardingActions(
              onNext = { state = state.copy(step = OnboardingStep.PICK) },
              onSelect = { state = state.copy(selected = it) },
              onDownload = { downloaded = true },
            ),
          )
        }
      }
    }
    compose.onNodeWithText("Get started").performClick()
    compose.waitForIdle()
    compose.onNodeWithText("Your phone has 8 GB of memory", substring = true).assertExists()
    compose.onNodeWithText(RECOMMENDED[1].title).performClick()
    assertEquals(1, state.selected)
    compose.onNodeWithText("Download · ", substring = true).performClick()
    assertTrue(downloaded)
  }
}
