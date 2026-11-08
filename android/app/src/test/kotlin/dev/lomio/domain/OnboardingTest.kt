package dev.lomio.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingTest {
  private val sizes = listOf(806L, 1_107L, 1_921L, 2_370L)

  @Test
  fun picksTheLargestModelThatFits() {
    assertEquals(2, Onboarding.recommend(sizes, listOf(Fit.FITS, Fit.FITS, Fit.FITS, Fit.TIGHT)))
    assertEquals(3, Onboarding.recommend(sizes, List(4) { Fit.FITS }))
  }

  @Test
  fun fallsBackToTheSmallestTightThenTheSmallest() {
    assertEquals(1, Onboarding.recommend(sizes, listOf(Fit.WONT_FIT, Fit.TIGHT, Fit.TIGHT, Fit.WONT_FIT)))
    assertEquals(0, Onboarding.recommend(sizes, List(4) { Fit.WONT_FIT }))
    assertEquals(0, Onboarding.recommend(sizes, List(4) { null }))
    assertEquals(-1, Onboarding.recommend(emptyList(), emptyList()))
  }

  @Test
  fun ramIsShownInMarketingSizes() {
    val gib = 1024L * 1024 * 1024
    assertEquals("8 GB", Onboarding.ramLabel((7.6 * gib).toLong()))
    assertEquals("12 GB", Onboarding.ramLabel((11.2 * gib).toLong()))
    assertEquals("6 GB", Onboarding.ramLabel((5.5 * gib).toLong()))
    assertEquals("4 GB", Onboarding.ramLabel((3.7 * gib).toLong()))
  }
}
