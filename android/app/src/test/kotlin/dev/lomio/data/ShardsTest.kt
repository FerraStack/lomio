package dev.lomio.data

import dev.lomio.data.hf.GgufNames
import dev.lomio.data.hf.HfFile
import dev.lomio.data.hf.Shards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShardsTest {
  private fun f(path: String, size: Long) = HfFile("org/repo", path, size, "sha-$path")

  @Test
  fun partsOfASplitModelBecomeOneEntry() {
    // ggml-org/models (tinyllamas/split) layout, listed out of order next to a single file.
    val files =
      listOf(
        f("tinyllamas/split/stories15M-q8_0-00002-of-00003.gguf", 10_381_216),
        f("tinyllamas/split/stories15M-q8_0-00001-of-00003.gguf", 10_517_152),
        f("tinyllamas/stories15M-q4_0.gguf", 19_077_344),
        f("tinyllamas/split/stories15M-q8_0-00003-of-00003.gguf", 5_773_312),
      )
    val grouped = Shards.group(files)
    assertEquals(2, grouped.size)
    val split = grouped.single { it.isSplit }
    assertEquals("tinyllamas/split/stories15M-q8_0-00001-of-00003.gguf", split.path)
    assertEquals(10_517_152L + 10_381_216 + 5_773_312, split.sizeBytes)
    assertEquals(listOf(1, 2, 3), split.parts.map { it.splitPart!!.first })
    assertEquals("sha-tinyllamas/split/stories15M-q8_0-00001-of-00003.gguf", split.sha256)
    assertFalse(grouped.single { !it.isSplit }.isIncompleteSplit)
  }

  @Test
  fun aSplitModelWithMissingPartsIsMarkedIncomplete() {
    val grouped = Shards.group(listOf(f("big-Q4_K_M-00001-of-00003.gguf", 100), f("big-Q4_K_M-00003-of-00003.gguf", 100)))
    val only = grouped.single()
    assertFalse(only.isSplit)
    assertTrue(only.isIncompleteSplit)
    assertEquals(100L, only.sizeBytes)
  }

  @Test
  fun differentQuantsOfTheSameModelStaySeparate() {
    val grouped =
      Shards.group(
        listOf(
          f("Qwen3-32B-Q4_K_M-00001-of-00002.gguf", 10),
          f("Qwen3-32B-Q4_K_M-00002-of-00002.gguf", 10),
          f("Qwen3-32B-Q8_0-00001-of-00002.gguf", 20),
          f("Qwen3-32B-Q8_0-00002-of-00002.gguf", 20),
        )
      )
    assertEquals(listOf(20L, 40L), grouped.map { it.sizeBytes })
    assertTrue(grouped.all { it.isSplit && it.parts.size == 2 })
  }

  @Test
  fun namesDropThePartSuffix() {
    assertEquals("Qwen3-32B-Q4_K_M", GgufNames.splitBase("Qwen3-32B-Q4_K_M-00001-of-00003.gguf"))
    assertEquals("Qwen3 32B", GgufNames.displayName("Qwen3-32B-Q4_K_M-00001-of-00003.gguf"))
    assertEquals("Q4_K_M", GgufNames.quant("Qwen3-32B-Q4_K_M-00001-of-00003.gguf"))
  }
}
