package dev.lomio.domain

/** Shape of a model needed for the memory estimate (from the GGUF header). */
data class ModelShape(
  val fileSizeBytes: Long,
  val nLayers: Int?,
  val nEmbd: Int?,
  val nHead: Int?,
  val nHeadKv: Int?,
  val keyLength: Int? = null,
  val valueLength: Int? = null,
)

data class MemoryEstimate(val weightsBytes: Long, val kvCacheBytes: Long, val overheadBytes: Long) {
  val totalBytes: Long
    get() = weightsBytes + kvCacheBytes + overheadBytes
}

enum class Fit { FITS, TIGHT, WONT_FIT }

/**
 * Pre-load memory check (RESEARCH.md §7, product decision 1): weights + KV cache at the chosen
 * context + a compute-buffer allowance, compared against *available* RAM while leaving headroom
 * for Android's low-memory killer. PocketPal's #1 bug class is OOM on load.
 */
object MemoryFit {
  /** f16 K/V cache. */
  const val KV_BYTES_PER_ELEMENT = 2

  /** Fixed allowance for compute buffers, the runtime and the app itself. */
  const val BASE_OVERHEAD_BYTES = 256L * 1024 * 1024

  /** Fraction of total RAM we never plan to use (LMK headroom; PocketPal uses 25–35%). */
  const val TOTAL_RAM_HEADROOM = 0.30

  fun kvCacheBytes(shape: ModelShape, nCtx: Int): Long {
    val layers = shape.nLayers ?: return 0
    val nHead = shape.nHead ?: return 0
    val nEmbd = shape.nEmbd ?: return 0
    if (nHead == 0) return 0
    val nHeadKv = shape.nHeadKv ?: nHead
    val headDim = nEmbd / nHead
    val k = shape.keyLength ?: headDim
    val v = shape.valueLength ?: headDim
    return layers.toLong() * nCtx * nHeadKv * (k + v) * KV_BYTES_PER_ELEMENT
  }

  fun estimate(shape: ModelShape, nCtx: Int): MemoryEstimate {
    val weights = shape.fileSizeBytes
    // Compute buffers grow with the model; 5% of the weights is a conservative rule of thumb.
    val overhead = BASE_OVERHEAD_BYTES + weights / 20
    return MemoryEstimate(weights, kvCacheBytes(shape, nCtx), overhead)
  }

  /**
   * @param availableBytes `ActivityManager.MemoryInfo.availMem`
   * @param totalBytes `ActivityManager.MemoryInfo.totalMem`
   */
  fun verdict(estimate: MemoryEstimate, availableBytes: Long, totalBytes: Long): Fit {
    val need = estimate.totalBytes
    val budget = minOf(availableBytes.toDouble(), totalBytes * (1 - TOTAL_RAM_HEADROOM))
    return when {
      need <= budget * 0.85 -> Fit.FITS
      need <= availableBytes -> Fit.TIGHT
      else -> Fit.WONT_FIT
    }
  }

  /** Largest context (multiple of 256, ≥ 512) that still [Fit.FITS], or null if none does. */
  fun maxFittingContext(shape: ModelShape, availableBytes: Long, totalBytes: Long, limit: Int): Int? {
    var best: Int? = null
    var ctx = 512
    while (ctx <= limit) {
      if (verdict(estimate(shape, ctx), availableBytes, totalBytes) == Fit.FITS) best = ctx else break
      ctx += 256
    }
    return best
  }
}
