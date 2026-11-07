package dev.lomio.ui

import dev.lomio.ui.components.CodePalette
import dev.lomio.ui.components.Contrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeThemeTest {
  @Test
  fun everyTokenColorHasAaContrastInBothThemes() {
    for ((name, palette) in listOf("light" to CodePalette.LIGHT, "dark" to CodePalette.DARK)) {
      for ((token, color) in palette.tokenColors) {
        val ratio = Contrast.ratio(color, palette.background)
        assertTrue("$name/$token contrast %.2f < 4.5".format(ratio), ratio >= 4.5)
      }
    }
  }

  @Test
  fun syntaxThemeUsesOnlyPaletteColors() {
    for (palette in listOf(CodePalette.LIGHT, CodePalette.DARK)) {
      val t = palette.syntaxTheme()
      // Every color class the highlighter can emit must come from the checked palette.
      val emitted = listOf(t.code, t.keyword, t.string, t.literal, t.comment, t.metadata, t.multilineComment, t.punctuation, t.mark)
      emitted.forEach { assertTrue(it in palette.tokenColors.values) }
    }
    assertTrue(CodePalette.DARK.syntaxTheme().key.endsWith("dark"))
    assertTrue(CodePalette.LIGHT.syntaxTheme().key.endsWith("light"))
  }

  @Test
  fun contrastMath() {
    assertEquals(21.0, Contrast.ratio(0x000000, 0xFFFFFF), 0.01)
    assertEquals(1.0, Contrast.ratio(0x777777, 0x777777), 0.001)
  }
}
