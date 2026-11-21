package dev.lomio.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportNameTest {
  @Test
  fun keepsNormalGgufName() {
    assertEquals("Qwen3-4B-Q4_K_M.gguf", ModelRepository.sanitizeImportName("Qwen3-4B-Q4_K_M.gguf"))
    assertEquals("MODEL.GGUF", ModelRepository.sanitizeImportName("MODEL.GGUF"))
  }

  @Test
  fun stripsPathTraversalAndSeparators() {
    assertEquals("evil.gguf", ModelRepository.sanitizeImportName("../../evil.gguf"))
    assertEquals("evil.gguf", ModelRepository.sanitizeImportName("..\\..\\evil.gguf"))
    assertEquals("foo.bar.gguf", ModelRepository.sanitizeImportName("foo..bar.gguf"))
  }

  @Test
  fun fallsBackOnBlankOrDegenerateNames() {
    assertEquals("model.gguf", ModelRepository.sanitizeImportName(null))
    assertEquals("model.gguf", ModelRepository.sanitizeImportName("   "))
    assertEquals("model.gguf", ModelRepository.sanitizeImportName(".."))
    assertEquals("model.gguf", ModelRepository.sanitizeImportName(".gguf"))
    assertEquals("model.gguf", ModelRepository.sanitizeImportName("/"))
  }

  @Test
  fun preservesNonGgufExtensionSoCallerRejectsIt() {
    assertEquals("notes.txt", ModelRepository.sanitizeImportName("../notes.txt"))
  }

  @Test
  fun stripsControlChars() {
    assertEquals("good.gguf", ModelRepository.sanitizeImportName("go\u0000od\n.gguf"))
  }
}
