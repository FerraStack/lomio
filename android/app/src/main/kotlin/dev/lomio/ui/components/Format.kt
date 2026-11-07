package dev.lomio.ui.components

import java.util.Locale

object Format {
  fun bytes(b: Long): String {
    if (b < 0) return "?"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = 0
    while (v >= 1000 && i < units.lastIndex) {
      v /= 1000
      i++
    }
    return if (i == 0) "$b B" else String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.2f %s", v, units[i]).replace(".00 ", " ")
  }

  fun params(n: Long?): String? {
    if (n == null || n <= 0) return null
    return when {
      n >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", n / 1e9)
      n >= 1_000_000 -> String.format(Locale.US, "%.0fM", n / 1e6)
      else -> n.toString()
    }
  }

  fun context(n: Int?): String? {
    if (n == null || n <= 0) return null
    return if (n >= 1024) "${n / 1024}K" else n.toString()
  }

  fun count(n: Long): String =
    when {
      n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1e6)
      n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1e3)
      else -> n.toString()
    }
}
