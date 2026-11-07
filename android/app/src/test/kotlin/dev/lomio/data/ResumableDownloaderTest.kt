package dev.lomio.data

import dev.lomio.data.download.ChecksumMismatchException
import dev.lomio.data.download.HttpStatusException
import dev.lomio.data.download.ResumableDownloader
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableDownloaderTest {
  @get:Rule val tmp = TemporaryFolder()

  private val server = MockWebServer()
  private val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }
  private val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
  private val downloader = ResumableDownloader(OkHttpClient(), maxRetries = 2, retryDelayMs = 1)
  private val ranges = mutableListOf<String?>()

  private val ifRanges = mutableListOf<String?>()

  /**
   * Serves [payload] honoring `Range: bytes=N-` and `If-Range` (ETag [etag]). The first
   * [failFirst] requests get a 503; the first [dropFirst] responses are cut off halfway.
   */
  private fun rangeServer(failFirst: Int = 0, ignoreRange: Boolean = false, dropFirst: Int = 0, etag: String = "\"v1\"") {
    val count = AtomicInteger()
    val drops = AtomicInteger()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val range = request.headers["Range"]
          synchronized(ranges) {
            ranges += range
            ifRanges += request.headers["If-Range"]
          }
          if (count.getAndIncrement() < failFirst) return MockResponse.Builder().code(503).build()
          val ifRange = request.headers["If-Range"]
          val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
          val drop = drops.getAndIncrement() < dropFirst
          fun MockResponse.Builder.maybeDrop() =
            apply { if (drop) onResponseBody(SocketEffect.CloseSocket()) }
          return if (start == null || ignoreRange || (ifRange != null && ifRange != etag)) {
            MockResponse.Builder().code(200).addHeader("ETag", etag).body(Buffer().write(payload)).maybeDrop().build()
          } else if (start >= payload.size) {
            MockResponse.Builder().code(416).build()
          } else {
            MockResponse.Builder()
              .code(206)
              .addHeader("ETag", etag)
              .addHeader("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
              .body(Buffer().write(payload, start, payload.size - start))
              .maybeDrop()
              .build()
          }
        }
      }
  }

  @Before fun setUp() = server.start()

  @After fun tearDown() = server.close()

  private fun dest() = File(tmp.root, "models/m.gguf")

  @Test
  fun freshDownloadVerifiesAndRenames() = runTest {
    rangeServer()
    var lastProgress = 0L to 0L
    val f =
      downloader.download(server.url("/m.gguf").toString(), dest(), payload.size.toLong(), sha) { d, t ->
        lastProgress = d to t
      }
    assertArrayEquals(payload, f.readBytes())
    assertFalse(downloader.partFile(dest()).exists())
    assertEquals(payload.size.toLong() to payload.size.toLong(), lastProgress)
    assertEquals(listOf<String?>(null), ranges)
  }

  @Test
  fun resumesFromPartialFile() = runTest {
    rangeServer()
    val part = downloader.partFile(dest()).apply { parentFile!!.mkdirs() }
    part.writeBytes(payload.copyOf(100_000))
    val f = downloader.download(server.url("/m.gguf").toString(), dest(), payload.size.toLong(), sha)
    assertArrayEquals(payload, f.readBytes())
    assertEquals(listOf<String?>("bytes=100000-"), ranges)
  }

  @Test
  fun restartsWhenServerIgnoresRange() = runTest {
    rangeServer(ignoreRange = true)
    val part = downloader.partFile(dest()).apply { parentFile!!.mkdirs() }
    part.writeBytes(payload.copyOf(100_000))
    val f = downloader.download(server.url("/m.gguf").toString(), dest(), payload.size.toLong(), sha)
    assertArrayEquals(payload, f.readBytes())
  }

  @Test
  fun completePartFileIsOnlyVerified() = runTest {
    rangeServer()
    val part = downloader.partFile(dest()).apply { parentFile!!.mkdirs() }
    part.writeBytes(payload)
    downloader.download(server.url("/m.gguf").toString(), dest(), payload.size.toLong(), sha)
    assertTrue(ranges.isEmpty())
    assertTrue(dest().exists())
  }

  @Test
  fun retriesTransientErrors() = runTest {
    rangeServer(failFirst = 2)
    val f = downloader.download(server.url("/m.gguf").toString(), dest(), payload.size.toLong(), sha)
    assertArrayEquals(payload, f.readBytes())
    assertEquals(3, ranges.size)
  }

  @Test
  fun flakyConnectionResumesAndRetryBudgetResetsOnProgress() = runTest {
    // Four mid-body disconnects with maxRetries = 2: only works because each attempt made progress.
    rangeServer(dropFirst = 4)
    val f = downloader.download(server.url("/m.gguf").toString(), dest(), payload.size.toLong(), sha)
    assertArrayEquals(payload, f.readBytes())
    assertEquals(5, ranges.size)
    assertTrue(ranges.drop(1).all { it != null && it.startsWith("bytes=") })
  }

  @Test
  fun resumeSendsIfRangeWithTheStoredEtag() = runTest {
    rangeServer(dropFirst = 1)
    downloader.download(server.url("/m.gguf").toString(), dest(), payload.size.toLong(), sha)
    assertEquals(listOf(null, "\"v1\""), ifRanges)
    assertFalse(File(dest().path + ".part.etag").exists())
  }

  @Test
  fun changedUpstreamFileRestartsInsteadOfAppending() = runTest {
    // A part file from an older version (ETag v0) must not be appended to.
    val part = downloader.partFile(dest()).apply { parentFile!!.mkdirs() }
    part.writeBytes(ByteArray(100_000) { 7 })
    File(dest().path + ".part.etag").writeText("\"v0\"")
    rangeServer(etag = "\"v1\"")
    val f = downloader.download(server.url("/m.gguf").toString(), dest(), payload.size.toLong(), sha)
    assertArrayEquals(payload, f.readBytes())
    assertEquals(listOf<String?>("\"v0\""), ifRanges)
  }

  @Test
  fun clientErrorsAreNotRetried() = runTest {
    server.enqueue(MockResponse.Builder().code(401).build())
    try {
      downloader.download(server.url("/m.gguf").toString(), dest(), payload.size.toLong(), sha)
      fail("expected HttpStatusException")
    } catch (e: HttpStatusException) {
      assertEquals(401, e.code)
    }
    assertEquals(1, server.requestCount)
  }

  @Test
  fun checksumMismatchDeletesPartFile() = runTest {
    rangeServer()
    try {
      downloader.download(server.url("/m.gguf").toString(), dest(), payload.size.toLong(), "00".repeat(32))
      fail("expected ChecksumMismatchException")
    } catch (_: ChecksumMismatchException) {}
    assertFalse(downloader.partFile(dest()).exists())
    assertFalse(dest().exists())
  }

  @Test
  fun sha256Helper() {
    val f = tmp.newFile().apply { writeBytes(payload) }
    assertEquals(sha, ResumableDownloader.sha256(f))
  }
}
