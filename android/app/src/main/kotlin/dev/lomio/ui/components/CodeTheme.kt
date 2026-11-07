package dev.lomio.ui.components

import dev.snipme.highlights.model.SyntaxTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Colors for fenced code blocks. Fixed per light/dark (not taken from dynamic color) so every
 * token class is guaranteed WCAG AA contrast (≥ 4.5:1) against [background]; see CodeThemeTest.
 *
 * The highlighter's built-in themes follow `isSystemInDarkTheme()`, which is wrong when the app
 * theme is overridden, and their punctuation colors vanish on our dark code background.
 */
data class CodePalette(
  val background: Int,
  val plain: Int,
  val keyword: Int,
  val string: Int,
  val literal: Int,
  val comment: Int,
  val metadata: Int,
  val punctuation: Int,
  val mark: Int,
) {
  /** Every color that text in a code block can have. */
  val tokenColors: Map<String, Int>
    get() =
      mapOf(
        "plain" to plain,
        "keyword" to keyword,
        "string" to string,
        "literal" to literal,
        "comment" to comment,
        "metadata" to metadata,
        "punctuation" to punctuation,
        "mark" to mark,
      )

  fun syntaxTheme(): SyntaxTheme =
    SyntaxTheme(
      key = if (Contrast.luminance(background) < 0.5) "lomio-dark" else "lomio-light",
      code = plain,
      keyword = keyword,
      string = string,
      literal = literal,
      comment = comment,
      metadata = metadata,
      multilineComment = comment,
      punctuation = punctuation,
      mark = mark,
    )

  companion object {
    val LIGHT =
      CodePalette(
        background = 0xEDF1F1,
        plain = 0x1B1F1F,
        keyword = 0x9A3412,
        string = 0x1B6B2F,
        literal = 0x1F4FA8,
        comment = 0x5B6466,
        metadata = 0x7A4A00,
        punctuation = 0x3A4244,
        mark = 0x8B1D6B,
      )

    val DARK =
      CodePalette(
        background = 0x1A2122,
        plain = 0xE1E6E6,
        keyword = 0xFFB37A,
        string = 0x9AD8A4,
        literal = 0x9EC2FF,
        comment = 0x9AA5A7,
        metadata = 0xE5C07B,
        punctuation = 0xC5CDCE,
        mark = 0xF5A3D6,
      )

    fun forDarkTheme(dark: Boolean) = if (dark) DARK else LIGHT
  }
}

/** WCAG 2.x relative luminance and contrast ratio for 0xRRGGBB colors. */
object Contrast {
  fun luminance(rgb: Int): Double {
    fun channel(shift: Int): Double {
      val c = ((rgb shr shift) and 0xFF) / 255.0
      return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
  }

  fun ratio(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
  }
}
