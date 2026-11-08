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
 *
 * Resume safety: the response ETag is kept next to the part file (`.part.etag`) and sent back
 * as `If-Range`, so if the file changed upstream the server answers 200 and we start over
 * instead of appending bytes of a different file. Callers should also pin the URL to a commit.
 *
 * Transient errors are retried [maxRetries] times; the counter resets whenever an attempt made
 * progress, so a long download on a flaky network doesn't fail after a few drops.
 */
class ResumableDownloader(
  private val http: OkHttpClient,
  private val maxRetries: Int = 3,
  private val retryDelayMs: Long = 2_000,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun partFile(dest: File) = File(dest.path + ".part")

  private fun etagFile(dest: File) = File(dest.path + ".part.etag")

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
        val before = if (part.exists()) part.length() else 0L
        try {
          fetch(url, part, etagFile(dest), expectedSize, headers, progressIntervalMs, onProgress)
          break
        } catch (e: CancellationException) {
          throw e
        } catch (e: HttpStatusException) {
          // 4xx are permanent (auth, not found); 5xx may be transient.
          if (e.code in 400..499 || ++attempt > maxRetries) throw e
          delay(retryDelayMs * attempt)
        } catch (e: IOException) {
          if (part.exists() && part.length() > before) attempt = 0 // made progress: not stuck
          if (++attempt > maxRetries) throw e
          delay(retryDelayMs * attempt)
        }
      }
      verify(part, expectedSize, expectedSha256)
      if (dest.exists()) dest.delete()
      if (!part.renameTo(dest)) throw IOException("could not move ${part.name} into place")
      etagFile(dest).delete()
      dest
    }

  private suspend fun fetch(
    url: String,
    part: File,
    etagFile: File,
    expectedSize: Long?,
    headers: Map<String, String>,
    progressIntervalMs: Long,
    onProgress: suspend (Long, Long) -> Unit,
  ) {
    var offset = if (part.exists()) part.length() else 0L
    val etag = etagFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    if ((expectedSize != null && offset > expectedSize) || (offset > 0 && etag == null && expectedSize == null)) {
      // Too big, or unknown provenance with nothing to validate against: start over.
      part.delete()
      offset = 0
    }
    if (expectedSize != null && offset == expectedSize) return

    val request =
      Request.Builder()
        .url(url)
        .apply {
          headers.forEach { (k, v) -> header(k, v) }
          if (offset > 0) {
            header("Range", "bytes=$offset-")
            if (etag != null) header("If-Range", etag)
          }
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
      if (!append) offset = 0 // server ignored Range or the file changed (200): start over
      // Remember the validator of what we're writing, for If-Range on the next resume.
      val newEtag = resp.header("ETag")?.takeUnless { it.startsWith("W/") }
      if (!append) {
        if (newEtag != null) etagFile.writeText(newEtag) else etagFile.delete()
      }
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

class InsufficientStorageException(val needed: Long, val free: Long) :
  IOException("Not enough storage: needs ${needed / 1_000_000} MB, ${free / 1_000_000} MB free")
