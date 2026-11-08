package dev.lomio.ui.components

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
  @Test
  fun englishUnitsWithADecimalPoint() {
    assertEquals("2.37 GB", Format.bytes(2_370_065_536, Locale.US))
    assertEquals("806 MB", Format.bytes(806_058_240, Locale.US))
    assertEquals("2 GB", Format.bytes(2_000_000_000, Locale.US))
    assertEquals("512 B", Format.bytes(512, Locale.US))
  }

  @Test
  fun russianAndUkrainianUnitsWithADecimalComma() {
    assertEquals("2,37 ГБ", Format.bytes(2_370_065_536, Locale.forLanguageTag("ru")))
    assertEquals("806 МБ", Format.bytes(806_058_240, Locale.forLanguageTag("uk")))
    assertEquals("2 ГБ", Format.bytes(2_000_000_000, Locale.forLanguageTag("uk")))
    assertEquals("512 Б", Format.bytes(512, Locale.forLanguageTag("ru")))
  }
}
