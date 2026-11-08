package dev.lomio.domain

/** First-run model choice: the biggest starter model that comfortably fits this phone. */
object Onboarding {
  /**
   * Index of the option to preselect: the largest that [Fit.FITS]; else the smallest that is
   * [Fit.TIGHT]; else the smallest overall. [sizes] and [fits] are parallel lists.
   */
  fun recommend(sizes: List<Long>, fits: List<Fit?>): Int {
    if (sizes.isEmpty()) return -1
    val idx = sizes.indices
    idx.filter { fits[it] == Fit.FITS }.maxByOrNull { sizes[it] }?.let { return it }
    idx.filter { fits[it] == Fit.TIGHT }.minByOrNull { sizes[it] }?.let { return it }
    return idx.minBy { sizes[it] }
  }

  /** "8 GB" for 7.6 GiB of reported RAM: phones are sold by marketing sizes. */
  fun ramLabel(totalBytes: Long): String {
    val gib = totalBytes / (1024.0 * 1024 * 1024)
    val marketing = listOf(2, 3, 4, 6, 8, 12, 16, 24, 32).firstOrNull { gib <= it + 0.25 } ?: Math.round(gib).toInt()
    return "$marketing GB"
  }
}
