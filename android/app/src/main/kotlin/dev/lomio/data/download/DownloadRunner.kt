package dev.lomio.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.lomio.AppContainer
import dev.lomio.MainActivity
import dev.lomio.R
import dev.lomio.data.db.ModelState
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * At most [MAX] downloads transfer at once; the rest wait as QUEUED (issue #4). Five parallel
 * multi-GB downloads only make each one slower and keep every one of them exposed to OS limits.
 */
class DownloadGate(permits: Int = MAX) {
  private val semaphore = Semaphore(permits)

  suspend fun <T> run(block: suspend () -> T): T = semaphore.withPermit { block() }

  companion object {
    const val MAX = 2
  }
}

/** How a download attempt ended, for the job / worker to report to the OS. */
enum class DownloadOutcome { DONE, RETRY, FAILED }

/**
 * One download attempt, shared by the API 34+ user-initiated job and the WorkManager worker.
 * Progress goes to the DB (the UI observes it) and to [onProgress] for the notification.
 */
class DownloadRunner(private val c: AppContainer) {
  suspend fun run(
    modelId: String,
    attempt: Int,
    onProgress: suspend (name: String, done: Long, total: Long) -> Unit,
  ): DownloadOutcome {
    val dao = c.db.models()
    val queued = dao.get(modelId) ?: return DownloadOutcome.FAILED
    if (queued.state != ModelState.DOWNLOADING) dao.updateProgress(modelId, ModelState.QUEUED, queued.bytesDone)
    return c.downloadGate.run { transfer(modelId, attempt, onProgress) }
  }

  private suspend fun transfer(
    modelId: String,
    attempt: Int,
    onProgress: suspend (name: String, done: Long, total: Long) -> Unit,
  ): DownloadOutcome {
    val dao = c.db.models()
    var model = dao.get(modelId) ?: return DownloadOutcome.FAILED
    val repo = model.hfRepo ?: return DownloadOutcome.FAILED
    val path = model.hfFile ?: return DownloadOutcome.FAILED
    val dest = File(model.filePath ?: c.models.hfDestination(repo, path).absolutePath)
    val hf = c.hfClient
    // Read the token ourselves: the process may have been started for this job just now,
    // before the app's async settings collector ran (gated models would 401 otherwise).
    val token = c.settings.current().hfToken

    return try {
      // Pin the download to a commit so a resume can never mix two versions of the file.
      if (model.hfRevision.isNullOrBlank() || model.hfRevision == "main") {
        model = model.copy(hfRevision = hf.commitSha(repo))
        dao.upsert(model)
      }
      val url = hf.downloadUrl(repo, path, model.hfRevision!!)

      val partial = c.downloader.partFile(dest).length()
      val remaining = (model.sizeBytes - partial).coerceAtLeast(0)
      dest.parentFile?.mkdirs()
      val free = dest.parentFile?.usableSpace ?: Long.MAX_VALUE
      if (remaining + STORAGE_MARGIN_BYTES > free) {
        throw InsufficientStorageException(remaining + STORAGE_MARGIN_BYTES, free)
      }

      dao.updateProgress(modelId, ModelState.DOWNLOADING, partial, model.errorMessage?.takeIf { it == NO_NOTIFICATION })
      onProgress(model.displayName, partial, model.sizeBytes)

      var lastDbWrite = 0L
      var lastPercent = -1
      c.downloader.download(
        url = url,
        dest = dest,
        expectedSize = model.sizeBytes.takeIf { it > 0 },
        expectedSha256 = model.sha256,
        headers = hf.authHeaders(url, token),
        progressIntervalMs = 500,
      ) { done, total ->
        val now = System.currentTimeMillis()
        if (now - lastDbWrite > 1_000) {
          lastDbWrite = now
          dao.updateProgress(modelId, ModelState.DOWNLOADING, done, dao.get(modelId)?.errorMessage)
        }
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        if (percent != lastPercent) {
          lastPercent = percent
          onProgress(model.displayName, done, total)
        }
      }
      val ready =
        c.models.withMetadata(
          model.copy(state = ModelState.READY, bytesDone = dest.length(), filePath = dest.absolutePath, errorMessage = null),
          dest,
        )
      dao.upsert(ready)
      DownloadOutcome.DONE
    } catch (e: CancellationException) {
      settle(modelId, dest, ModelState.PAUSED, null)
      throw e
    } catch (e: Throwable) {
      val transient =
        e is IOException &&
          e !is ChecksumMismatchException &&
          e !is SizeMismatchException &&
          e !is InsufficientStorageException &&
          !(e is HttpStatusException && e.code in 400..499)
      if (transient && attempt < MAX_ATTEMPTS) {
        // Network dropped for longer than the downloader's own retries: back off and resume
        // when connectivity returns.
        settle(modelId, dest, ModelState.QUEUED, "Waiting for network… (${e.message})")
        DownloadOutcome.RETRY
      } else {
        settle(modelId, dest, ModelState.ERROR, userMessage(e))
        DownloadOutcome.FAILED
      }
    }
  }

  /** Best-effort state write that never throws (the DB itself may be the problem, e.g. disk full). */
  private suspend fun settle(modelId: String, dest: File, state: ModelState, message: String?) {
    withContext(NonCancellable) {
      runCatching { c.db.models().updateProgress(modelId, state, c.downloader.partFile(dest).length(), message) }
    }
  }

  private fun userMessage(e: Throwable): String =
    when {
      e is HttpStatusException && (e.code == 401 || e.code == 403) ->
        "Access denied (HTTP ${e.code}). Gated model? Add a Hugging Face token in Settings."
      e is HttpStatusException && e.code == 404 -> "File not found on Hugging Face (HTTP 404)."
      e is InsufficientStorageException -> e.message!!
      e is ChecksumMismatchException -> "Downloaded file is corrupt (checksum mismatch). Try again."
      else -> e.message ?: e.javaClass.simpleName
    }

  companion object {
    const val MAX_ATTEMPTS = 8
    /** Keep this much free space beyond the file itself. */
    const val STORAGE_MARGIN_BYTES = 300L * 1024 * 1024
    /** Shown on the card when Android refused the foreground notification (API 31–33). */
    const val NO_NOTIFICATION =
      "Running without a notification, so Android may pause it while Lomio is in the background."
  }
}

/** The ongoing download notification, shared by the job and the worker. */
object DownloadNotifications {
  private const val CHANNEL = "downloads"

  fun build(context: Context, name: String?, done: Long, total: Long, pause: PendingIntent?): Notification {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      nm.createNotificationChannel(NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW))
    }
    val percent = if (total > 0) (done * 100 / total).toInt() else 0
    val open =
      PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
      )
    return NotificationCompat.Builder(context, CHANNEL)
      .setSmallIcon(R.drawable.ic_stat_download)
      .setContentTitle(if (name != null) "Downloading $name" else "Waiting to download")
      .setContentText(if (name == null) "Starts when another download finishes" else if (total > 0) "$percent%" else "Starting…")
      .setProgress(100, percent, name == null || total <= 0)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(open)
      .apply { if (pause != null) addAction(0, "Pause", pause) }
      .build()
  }
}
