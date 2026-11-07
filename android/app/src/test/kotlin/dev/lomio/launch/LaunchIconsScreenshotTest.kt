package dev.lomio.launch

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import dev.lomio.R
import dev.lomio.data.settings.ThemeMode
import dev.lomio.ui.theme.LomioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The launcher-shortcut and Quick Settings tile icons, drawn from the real resources inside a
 * mock of the launcher's long-press menu and a QS tile (the system draws the real ones).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class LaunchIconsScreenshotTest {
  @get:Rule val compose = createComposeRule()

  @Composable
  private fun ShortcutRow(fg: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
      // Launchers mask adaptive shortcut icons to a circle.
      Box(Modifier.size(40.dp).clip(CircleShape).background(colorResource(R.color.shortcut_background)), contentAlignment = Alignment.Center) {
        Image(painterResource(fg), null, Modifier.size(60.dp))
      }
      Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
    }
  }

  private fun shoot(dark: Boolean) {
    compose.setContent {
      LomioTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
          Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("Launcher long-press", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.width(300.dp)) {
              Column(Modifier.padding(vertical = 8.dp)) {
                ShortcutRow(R.drawable.ic_shortcut_chat_fg, "Trip to Lviv")
                ShortcutRow(R.drawable.ic_shortcut_new_chat_fg, "New chat")
              }
            }
            Text("Quick Settings tile", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.width(220.dp)) {
              Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_tile_ask), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
                Column(Modifier.padding(start = 12.dp)) {
                  Text("Ask Lomio", style = MaterialTheme.typography.titleSmall)
                  Text("Llama 3.2 3B", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }
            }
          }
        }
      }
    }
    compose.waitForIdle()
    compose.onRoot().captureRoboImage("build/outputs/roborazzi/launch_icons_${if (dark) "dark" else "light"}.png")
  }

  @Test fun light() = shoot(dark = false)

  @Test fun dark() = shoot(dark = true)
}
