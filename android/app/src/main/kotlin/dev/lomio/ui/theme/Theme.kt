package dev.lomio.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.lomio.data.settings.ThemeMode

// One accent color (RESEARCH.md §9): a calm teal.
private val Light =
  lightColorScheme(
    primary = Color(0xFF00696E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF0F5),
    onPrimaryContainer = Color(0xFF002022),
    secondaryContainer = Color(0xFFCCE8E9),
    onSecondaryContainer = Color(0xFF051F21),
    background = Color(0xFFF8FAFA),
    onBackground = Color(0xFF191C1C),
    surface = Color(0xFFF8FAFA),
    onSurface = Color(0xFF191C1C),
    surfaceContainer = Color(0xFFECEEEE),
    surfaceContainerHigh = Color(0xFFE6E9E9),
    surfaceContainerLow = Color(0xFFF2F4F4),
  )

private val Dark =
  darkColorScheme(
    primary = Color(0xFF80D4D9),
    onPrimary = Color(0xFF003739),
    primaryContainer = Color(0xFF004F53),
    onPrimaryContainer = Color(0xFF9CF0F5),
    secondaryContainer = Color(0xFF324B4D),
    onSecondaryContainer = Color(0xFFCCE8E9),
    background = Color(0xFF0F1415),
    onBackground = Color(0xFFDEE4E4),
    surface = Color(0xFF0F1415),
    onSurface = Color(0xFFDEE4E4),
    surfaceContainer = Color(0xFF1B2121),
    surfaceContainerHigh = Color(0xFF252B2B),
    surfaceContainerLow = Color(0xFF171D1D),
  )

@Composable
fun LomioTheme(
  themeMode: ThemeMode = ThemeMode.SYSTEM,
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val dark =
    when (themeMode) {
      ThemeMode.SYSTEM -> isSystemInDarkTheme()
      ThemeMode.LIGHT -> false
      ThemeMode.DARK -> true
    }
  val colors =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
      }
      dark -> Dark
      else -> Light
    }
  MaterialTheme(colorScheme = colors, content = content)
}
