package dev.lomio.llama

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PromptCacheTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun PromptCache.put(key: String, bytes: Int, age: Long) {
    val f = file(key)
    f.parentFile.mkdirs()
    f.writeBytes(ByteArray(bytes))
    File(f.path + ".meta").writeBytes(ByteArray(10))
    f.setLastModified(age)
    File(f.path + ".meta").setLastModified(age)
  }

  @Test
  fun trimDropsTheLeastRecentlyUsedButNeverTheOneJustSaved() {
    val cache = PromptCache(tmp.root, maxTotalBytes = 2_500)
    cache.put("old", 1_000, 1_000)
    cache.put("mid", 1_000, 2_000)
    cache.put("new", 1_000, 3_000)
    File(tmp.root, "x.lpc.tmp").writeText("partial")
    cache.trim(keep = "old")
    assertTrue("kept: just saved", cache.exists("old"))
    assertFalse("oldest of the others goes", cache.exists("mid"))
    assertTrue(cache.exists("new"))
    assertTrue(cache.totalBytes() <= 2_500)
    assertFalse(File(tmp.root, "x.lpc.tmp").exists())
  }

  @Test
  fun retainOnlyDeletesCachesOfDeletedChats() {
    val cache = PromptCache(tmp.root)
    cache.put("a", 10, 1)
    cache.put("b", 10, 1)
    cache.retainOnly(setOf("a"))
    assertTrue(cache.exists("a"))
    assertFalse(cache.exists("b"))
    assertFalse(File(tmp.root, "b.lpc.meta").exists())
  }

  @Test
  fun keysBecomeSafeFileNames() {
    assertEquals("6f1c-2a_x__y", PromptCache.sanitize("6f1c-2a/x..y"))
    assertEquals("c1.lpc", PromptCache(tmp.root).file("c1").name)
  }
}
