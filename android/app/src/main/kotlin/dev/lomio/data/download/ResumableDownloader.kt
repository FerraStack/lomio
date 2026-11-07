package dev.lomio.data.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class ChecksumMismatchException(expected: String, actual: String) :
  IOException("sha256 mismatch: expected $expected, got $actual")

class SizeMismatchException(expected: Long, actual: Long) :
  IOException("size mismatch: expected $expected bytes, got $actual")

class HttpStatusException(val code: Int) : IOException("HTTP $code")

/**
 * Resumable, verified file download (RESEARCH.md §8.1): writes to `<dest>.part`, resumes with
 * an HTTP `Range` request, verifies size + sha256 and atomically renames on success.
 *
 * Cancelling the calling coroutine leaves the `.part` file in place, so cancel = pause.
 */
class ResumableDownloader(
  private val http: OkHttpClient,
  private val maxRetries: Int = 3,
  private val retryDelayMs: Long = 2_000,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun partFile(dest: File) = File(dest.path + ".part")

  /**
   * @param onProgress called at most every [progressIntervalMs] with (bytesDone, totalBytes);
   *   totalBytes is -1 when unknown.
   */
  suspend fun download(
    url: String,
    dest: File,
    expectedSize: Long? = null,
    expectedSha256: String? = null,
    headers: Map<String, String> = emptyMap(),
    progressIntervalMs: Long = 250,
    onProgress: suspend (done: Long, total: Long) -> Unit = { _, _ -> },
  ): File =
    withContext(Dispatchers.IO) {
      dest.parentFile?.mkdirs()
      val part = partFile(dest)
      var attempt = 0
      while (true) {
        try {
          fetch(url, part, expectedSize, headers, progressIntervalMs, onProgress)
          break
        } catch (e: CancellationException) {
          throw e
        } catch (e: HttpStatusException) {
          // 4xx are permanent (auth, not found); 5xx may be transient.
          if (e.code in 400..499 || ++attempt > maxRetries) throw e
          delay(retryDelayMs * attempt)
        } catch (e: IOException) {
          if (++attempt > maxRetries) throw e
          delay(retryDelayMs * attempt)
        }
      }
      verify(part, expectedSize, expectedSha256)
      if (dest.exists()) dest.delete()
      if (!part.renameTo(dest)) throw IOException("could not move ${part.name} into place")
      dest
    }

  private suspend fun fetch(
    url: String,
    part: File,
    expectedSize: Long?,
    headers: Map<String, String>,
    progressIntervalMs: Long,
    onProgress: suspend (Long, Long) -> Unit,
  ) {
    var offset = if (part.exists()) part.length() else 0L
    if (expectedSize != null && offset > expectedSize) {
      part.delete()
      offset = 0
    }
    if (expectedSize != null && offset == expectedSize) return

    val request =
      Request.Builder()
        .url(url)
        .apply {
          headers.forEach { (k, v) -> header(k, v) }
          if (offset > 0) header("Range", "bytes=$offset-")
        }
        .build()

    http.newCall(request).execute().use { resp ->
      when {
        resp.code == 416 && offset > 0 -> {
          // Our partial file is already complete (or the server lost it): let verify decide,
          // and start over if the size is wrong.
          if (expectedSize == null || offset == expectedSize) return
          part.delete()
          throw IOException("range not satisfiable, restarting")
        }
        !resp.isSuccessful -> throw HttpStatusException(resp.code)
      }
      val append = offset > 0 && resp.code == 206
      if (!append) offset = 0 // server ignored Range (200): start from scratch
      val total =
        when {
          expectedSize != null -> expectedSize
          resp.body.contentLength() >= 0 -> offset + resp.body.contentLength()
          else -> -1
        }
      var done = offset
      var lastReport = 0L
      onProgress(done, total)
      FileOutputStream(part, append).use { out ->
        resp.body.byteStream().use { input ->
          val buf = ByteArray(BUFFER_SIZE)
          while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            val now = clock()
            if (now - lastReport >= progressIntervalMs) {
              lastReport = now
              onProgress(done, total)
            }
          }
        }
        out.fd.sync()
      }
      onProgress(done, total)
      // Connection dropped early: an IOException makes the retry loop resume from here.
      if (expectedSize != null && done < expectedSize) {
        throw IOException("stream ended at $done of $expectedSize bytes")
      }
    }
  }

  private fun verify(part: File, expectedSize: Long?, expectedSha256: String?) {
    if (expectedSize != null && part.length() != expectedSize) {
      val actual = part.length()
      if (actual > expectedSize) part.delete()
      throw SizeMismatchException(expectedSize, actual)
    }
    if (expectedSha256 != null) {
      val actual = sha256(part)
      if (!actual.equals(expectedSha256, ignoreCase = true)) {
        part.delete()
        throw ChecksumMismatchException(expectedSha256, actual)
      }
    }
  }

  companion object {
    private const val BUFFER_SIZE = 256 * 1024

    fun sha256(file: File): String {
      val md = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buf = ByteArray(BUFFER_SIZE)
        while (true) {
          val n = input.read(buf)
          if (n < 0) break
          md.update(buf, 0, n)
        }
      }
      return md.digest().joinToString("") { "%02x".format(it) }
    }
  }
}
