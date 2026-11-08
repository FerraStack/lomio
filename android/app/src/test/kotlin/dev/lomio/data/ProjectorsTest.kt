package dev.lomio.data

import dev.lomio.data.hf.HfFile
import dev.lomio.data.hf.Projectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectorsTest {
  private fun f(path: String, size: Long) = HfFile("org/repo", path, size, null)

  @Test
  fun textOnlyRepoHasNoProjector() {
    assertNull(Projectors.pick(listOf(f("Qwen3-1.7B-Q4_K_M.gguf", 1_100), f("Qwen3-1.7B-Q8_0.gguf", 1_800))))
  }

  @Test
  fun prefersQ8ThenF16() {
    // ggml-org/SmolVLM-256M-Instruct-GGUF layout.
    val files =
      listOf(
        f("SmolVLM-256M-Instruct-Q8_0.gguf", 175_054_528),
        f("SmolVLM-256M-Instruct-f16.gguf", 327_809_728),
        f("mmproj-SmolVLM-256M-Instruct-Q8_0.gguf", 103_769_856),
        f("mmproj-SmolVLM-256M-Instruct-f16.gguf", 190_031_616),
      )
    assertEquals("mmproj-SmolVLM-256M-Instruct-Q8_0.gguf", Projectors.pick(files)?.path)
    // unsloth/gemma-3-4b-it-GGUF layout: no Q8_0 projector.
    val gemma = listOf(f("gemma-3-4b-it-Q4_0.gguf", 2_370), f("mmproj-F32.gguf", 1_673), f("mmproj-BF16.gguf", 851), f("mmproj-F16.gguf", 851))
    assertEquals("mmproj-F16.gguf", Projectors.pick(gemma)?.path)
  }

  @Test
  fun unknownNamingPicksTheSmallestNonF32() {
    val files = listOf(f("model-Q4_K_M.gguf", 900), f("mmproj-model-f32.gguf", 600), f("mmproj-model.gguf", 300), f("mmproj-x.gguf", 200))
    assertEquals("mmproj-x.gguf", Projectors.pick(files)?.path)
    assertEquals("mmproj-model-f32.gguf", Projectors.pick(listOf(f("mmproj-model-f32.gguf", 600)))?.path)
  }

  @Test
  fun projectorQuantFromName() {
    assertEquals("F16", Projectors.projectorQuant("mmproj-model-f16.gguf"))
    assertEquals("BF16", Projectors.projectorQuant("mmproj-BF16.gguf"))
    assertEquals("Q8_0", Projectors.projectorQuant("mmproj-SmolVLM-256M-Instruct-Q8_0.gguf"))
    assertNull(Projectors.projectorQuant("mmproj-model.gguf"))
  }
}
