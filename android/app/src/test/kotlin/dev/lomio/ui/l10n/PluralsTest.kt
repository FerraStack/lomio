package dev.lomio.ui.l10n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lomio.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Slavic plural rules pick the right form (1, 2–4, 5–20, 21, 22, 25…). */
@RunWith(AndroidJUnit4::class)
class PluralsTest {
  private val res
    get() = ApplicationProvider.getApplicationContext<Context>().resources

  private fun days(n: Int) = res.getQuantityString(R.plurals.last_used_days, n, n)

  @Test
  @Config(qualifiers = "ru")
  fun russian() {
    assertEquals("Использовалась 1 день назад", days(1))
    assertEquals("Использовалась 3 дня назад", days(3))
    assertEquals("Использовалась 5 дней назад", days(5))
    assertEquals("Использовалась 11 дней назад", days(11))
    assertEquals("Использовалась 21 день назад", days(21))
    assertEquals("Использовалась 22 дня назад", days(22))
  }

  @Test
  @Config(qualifiers = "uk")
  fun ukrainian() {
    assertEquals("Використовувалася 1 день тому", days(1))
    assertEquals("Використовувалася 4 дні тому", days(4))
    assertEquals("Використовувалася 6 днів тому", days(6))
    assertEquals("Використовувалася 21 день тому", days(21))
    assertEquals("2 файли від незавершених завантажень чи імпорту", res.getQuantityString(R.plurals.storage_leftovers_sub, 2, 2))
  }

  @Test
  fun englishStaysTheDefault() {
    assertEquals("Used 1 day ago", days(1))
    assertEquals("Used 3 days ago", days(3))
    assertEquals("Used last week", res.getQuantityString(R.plurals.last_used_weeks, 1, 1))
  }
}
