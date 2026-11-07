package dev.lomio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFitTest {
  private val gib = 1024L * 1024 * 1024

  // Llama-3.2-3B-like shape: 28 layers, 3072 embd, 24 heads, 8 KV heads (head dim 128).
  private val llama3b = ModelShape(fileSizeBytes = 2 * gib, nLayers = 28, nEmbd = 3072, nHead = 24, nHeadKv = 8)

  @Test
  fun kvCacheMatchesHandComputation() {
    // 28 layers * 4096 ctx * 8 kv heads * (128 + 128) * 2 bytes = 469,762,048 bytes (448 MiB)
    assertEquals(469_762_048L, MemoryFit.kvCacheBytes(llama3b, 4096))
  }

  @Test
  fun explicitKeyValueLengthsWin() {
    val shape = llama3b.copy(keyLength = 256, valueLength = 128)
    assertEquals(28L * 1024 * 8 * (256 + 128) * 2, MemoryFit.kvCacheBytes(shape, 1024))
  }

  @Test
  fun missingShapeMeansNoKvEstimate() {
    assertEquals(0, MemoryFit.kvCacheBytes(ModelShape(gib, null, null, null, null), 4096))
  }

  @Test
  fun estimateAddsOverhead() {
    val e = MemoryFit.estimate(llama3b, 4096)
    assertEquals(2 * gib, e.weightsBytes)
    assertEquals(MemoryFit.BASE_OVERHEAD_BYTES + 2 * gib / 20, e.overheadBytes)
    assertEquals(e.weightsBytes + e.kvCacheBytes + e.overheadBytes, e.totalBytes)
  }

  @Test
  fun verdicts() {
    val e = MemoryFit.estimate(llama3b, 4096) // ~3.0 GiB
    assertEquals(Fit.FITS, MemoryFit.verdict(e, availableBytes = 6 * gib, totalBytes = 12 * gib))
    // Plenty available but phone total too small for 30% headroom -> tight, not fits.
    assertEquals(Fit.TIGHT, MemoryFit.verdict(e, availableBytes = 3500L * 1024 * 1024, totalBytes = 4 * gib))
    assertEquals(Fit.WONT_FIT, MemoryFit.verdict(e, availableBytes = 2 * gib, totalBytes = 4 * gib))
  }

  @Test
  fun maxFittingContextShrinksWithMemory() {
    val big = MemoryFit.maxFittingContext(llama3b, 8 * gib, 12 * gib, 32768)
    val small = MemoryFit.maxFittingContext(llama3b, 3500L * 1024 * 1024, 6 * gib, 32768)
    assertEquals(32768, big)
    assertTrue(small!! in 512 until 32768)
    assertNull(MemoryFit.maxFittingContext(llama3b, gib, 2 * gib, 32768))
  }
}
