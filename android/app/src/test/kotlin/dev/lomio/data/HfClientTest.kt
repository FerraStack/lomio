package dev.lomio.data

import dev.lomio.data.hf.GgufNames
import dev.lomio.data.hf.HfClient
import dev.lomio.data.hf.HfSort
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HfClientTest {
  private val server = MockWebServer()

  @Before fun setUp() = server.start()

  @After fun tearDown() = server.close()

  private fun client(token: String? = null) =
    HfClient(OkHttpClient(), server.url("/").toString().removeSuffix("/"), token = { token })

  @Test
  fun searchBuildsQueryAndParses() = runTest {
    server.enqueue(
      MockResponse.Builder()
        .body(
          """[{"id":"unsloth/Qwen3-1.7B-GGUF","downloads":1234,"likes":56,"gated":false,"lastModified":"2026-10-01"},
              {"id":"meta/gated-GGUF","author":"meta","gated":"manual"}]"""
        )
        .build()
    )
    val results = client().search("qwen", HfSort.DOWNLOADS, limit = 5)
    val req = server.takeRequest()
    assertEquals("/api/models", req.url.encodedPath)
    assertEquals("qwen", req.url.queryParameter("search"))
    assertEquals("gguf", req.url.queryParameter("filter"))
    assertEquals("downloads", req.url.queryParameter("sort"))
    assertEquals("5", req.url.queryParameter("limit"))
    assertEquals(2, results.size)
    assertEquals("unsloth", results[0].author)
    assertEquals(1234, results[0].downloads)
    assertFalse(results[0].gated)
    assertTrue(results[1].gated)
  }

  @Test
  fun filesKeepsOnlyGgufWithLfsHashes() = runTest {
    server.enqueue(
      MockResponse.Builder()
        .body(
          """[{"type":"file","path":"README.md","size":10},
              {"type":"directory","path":"sub"},
              {"type":"file","path":"Qwen3-1.7B-Q8_0.gguf","size":134,"lfs":{"oid":"abc","size":2000,"pointerSize":134}},
              {"type":"file","path":"Qwen3-1.7B-Q4_K_M.gguf","size":134,"lfs":{"oid":"def","size":1000,"pointerSize":134}}]"""
        )
        .build()
    )
    val files = client().ggufFiles("unsloth/Qwen3-1.7B-GGUF")
    assertEquals("/api/models/unsloth/Qwen3-1.7B-GGUF/tree/main", server.takeRequest().url.encodedPath)
    assertEquals(listOf("Qwen3-1.7B-Q4_K_M.gguf", "Qwen3-1.7B-Q8_0.gguf"), files.map { it.path })
    assertEquals(1000, files[0].sizeBytes)
    assertEquals("def", files[0].sha256)
    assertEquals("Q4_K_M", files[0].quant)
  }

  @Test
  fun tokenOnlySentToHuggingFace() {
    val c = HfClient(OkHttpClient(), token = { "hf_secret" })
    assertEquals(mapOf("Authorization" to "Bearer hf_secret"), c.authHeaders("https://huggingface.co/a/b/resolve/main/x.gguf"))
    assertTrue(c.authHeaders("https://hf-mirror.com/a/b/resolve/main/x.gguf").isEmpty())
    assertTrue(c.authHeaders("http://huggingface.co/x").isEmpty())
    assertTrue(HfClient.isOfficialHost("https://cdn.huggingface.co/x".toHttpUrl()))
    assertFalse(HfClient.isOfficialHost("https://huggingface.co.evil.com/x".toHttpUrl()))
  }

  @Test
  fun downloadUrl() {
    val c = HfClient(OkHttpClient())
    assertEquals(
      "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
      c.downloadUrl("unsloth/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_K_M.gguf"),
    )
  }

  @Test
  fun ggufNames() {
    assertEquals("Q4_K_M", GgufNames.quant("Qwen3-1.7B-Q4_K_M.gguf"))
    assertEquals("Q4_0", GgufNames.quant("gemma-3-1b-it-q4_0.gguf"))
    assertEquals("IQ4_XS", GgufNames.quant("Llama-3.2-3B-Instruct-IQ4_XS.gguf"))
    assertEquals("BF16", GgufNames.quant("model.BF16.gguf"))
    assertNull(GgufNames.quant("mmproj-model.gguf"))
    assertEquals(1 to 3, GgufNames.splitPart("big-Q4_K_M-00001-of-00003.gguf"))
    assertNull(GgufNames.splitPart("small.gguf"))
    assertEquals("Qwen3 1.7B", GgufNames.displayName("Qwen3-1.7B-Q4_K_M.gguf"))
  }
}
