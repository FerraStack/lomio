package dev.lomio.data

import dev.lomio.data.db.ModelEntity
import dev.lomio.data.db.ModelSource
import dev.lomio.data.db.ModelState
import dev.lomio.domain.Storage
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageRepositoryTest {
  @get:Rule val tmp = TemporaryFolder()

  private val now = 100 * Storage.DAY_MS
  private lateinit var models: File
  private lateinit var cache: File
  private lateinit var db: File
  private lateinit var repo: StorageRepository

  private fun file(dir: File, name: String, size: Int, old: Boolean = true): File =
    File(dir, name).apply {
      parentFile!!.mkdirs()
      writeBytes(ByteArray(size))
      if (old) setLastModified(now - Storage.LEFTOVER_GRACE_MS - 1_000) else setLastModified(now)
    }

  private fun model(id: String, path: String, state: ModelState = ModelState.READY) =
    ModelEntity(id = id, source = ModelSource.HF, displayName = id, filePath = path, sizeBytes = 0, state = state, createdAt = 0)

  @Before
  fun setUp() {
    models = tmp.newFolder("models")
    cache = tmp.newFolder("cache")
    db = tmp.newFile("lomio.db").apply { writeBytes(ByteArray(300)) }
    repo = StorageRepository({ models }, { listOf(cache) }, { listOf(db, File(db.path + "-wal")) }, clock = { now })
  }

  @Test
  fun measuresModelsPartialsLeftoversCacheAndChats() = runTest {
    val ready = file(models, "hf_a_b/ready.gguf", 1_000)
    file(models, "hf_c_d/half.gguf.part", 400)
    file(models, "hf_old/orphan.gguf", 700)
    file(models, "local/importing.gguf.part", 50, old = false)
    file(cache, "share/chat.md", 25)
    val rows =
      listOf(
        model("ready", ready.path),
        model("half", File(models, "hf_c_d/half.gguf").path, ModelState.PAUSED),
        model("gone", File(models, "hf_x/gone.gguf").path),
      )

    val scan = repo.scan(rows)

    assertEquals(mapOf("ready" to 1_000L, "half" to 400L, "gone" to 0L), scan.modelBytes)
    assertEquals(setOf("gone"), scan.missingFiles)
    assertEquals(listOf("orphan.gguf"), scan.leftovers.map { File(it.path).name })
    assertEquals(700L, scan.leftoverBytes)
    assertEquals(25L, scan.cacheBytes)
    assertEquals(300L, scan.chatsBytes)
    assertEquals(1_000L + 400 + 700 + 25 + 300, scan.totalBytes)
    assertTrue(scan.volumeBytes > 0 && scan.freeBytes in 1..scan.volumeBytes)
  }

  @Test
  fun deletingLeftoversKeepsEverythingAModelOwnsAndRecentFiles() = runTest {
    val ready = file(models, "hf_a_b/ready.gguf", 1_000)
    val part = file(models, "hf_c_d/half.gguf.part", 400)
    val orphan = file(models, "hf_old/orphan.gguf", 700)
    val importing = file(models, "local/importing.gguf.part", 50, old = false)
    val rows = listOf(model("ready", ready.path), model("half", part.path.removeSuffix(".part"), ModelState.DOWNLOADING))

    assertEquals(700L, repo.deleteLeftovers(rows))

    assertFalse(orphan.exists())
    assertFalse("empty repo folder is pruned", File(models, "hf_old").exists())
    assertTrue(ready.exists() && part.exists() && importing.exists())
    assertTrue("the models folder itself stays", models.isDirectory)
  }

  @Test
  fun chatCachesAreMeasuredAndClearedOnTheirOwn() = runTest {
    val chatCaches = File(cache, "prompt-cache")
    val repo = StorageRepository({ models }, { listOf(cache) }, { emptyList() }, chatCacheDir = { chatCaches }, clock = { now })
    file(cache, "a.tmp", 10)
    file(chatCaches, "c1.lpc", 500)
    file(chatCaches, "c1.lpc.meta", 20)

    val scan = repo.scan(emptyList())
    assertEquals("temporary files exclude the chat caches", 10L, scan.cacheBytes)
    assertEquals(520L, scan.chatCacheBytes)
    assertEquals(530L, scan.totalBytes)

    assertEquals(10L, repo.clearCache())
    assertTrue("clearing temporary files keeps the chat caches", File(chatCaches, "c1.lpc").exists())
    assertEquals(520L, repo.clearChatCaches())
    assertTrue(chatCaches.isDirectory && chatCaches.list()!!.isEmpty())
  }

  @Test
  fun clearingTheCacheEmptiesItButKeepsTheFolder() = runTest {
    file(cache, "a.tmp", 10)
    file(cache, "nested/b.tmp", 20)

    assertEquals(30L, repo.clearCache())
    assertTrue(cache.isDirectory)
    assertTrue(cache.list()!!.isEmpty())
    assertEquals(0L, repo.scan(emptyList()).cacheBytes)
  }

  @Test
  fun aVisionProjectorBelongsToItsModel() = runTest {
    val weights = file(models, "hf_ggml-org_SmolVLM/SmolVLM-Q8_0.gguf", 1_000)
    val projector = file(models, "hf_ggml-org_SmolVLM/mmproj-SmolVLM-Q8_0.gguf", 300)
    val rows = listOf(model("v", weights.path).copy(mmprojFile = projector.name, mmprojPath = projector.path))

    val scan = repo.scan(rows)
    assertEquals(mapOf("v" to 1_300L), scan.modelBytes)
    assertTrue("projector is not a leftover", scan.leftovers.isEmpty())
    assertEquals(0L, repo.deleteLeftovers(rows))
    assertTrue(projector.exists())
  }
}
