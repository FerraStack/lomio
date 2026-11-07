package dev.lomio.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.lomio.LomioApp
import dev.lomio.R
import dev.lomio.data.db.ModelState
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Enqueues one unique download job per model (so a second tap doesn't start a duplicate). */
class DownloadScheduler(private val context: Context) {
  fun enqueue(modelId: String) {
    val request =
      OneTimeWorkRequestBuilder<DownloadWorker>()
        .setInputData(workDataOf(DownloadWorker.KEY_MODEL_ID to modelId))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .addTag(TAG)
        .build()
    WorkManager.getInstance(context)
      .enqueueUniqueWork(workName(modelId), ExistingWorkPolicy.KEEP, request)
  }

  fun cancel(modelId: String) {
    WorkManager.getInstance(context).cancelUniqueWork(workName(modelId))
  }

  companion object {
    const val TAG = "model-download"

    fun workName(modelId: String) = "download-$modelId"
  }
}

/**
 * Runs a model download as a user-visible foreground job (RESEARCH.md §8.1: Rust/Kotlin core
 * downloads, Android owns the lifecycle). Progress goes to the DB, which the UI observes.
 */
class DownloadWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  private val container
    get() = (applicationContext as LomioApp).container

  override suspend fun doWork(): Result {
    val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
    val dao = container.db.models()
    var model = dao.get(modelId) ?: return Result.failure()
    val repo = model.hfRepo ?: return Result.failure()
    val path = model.hfFile ?: return Result.failure()
    val dest = File(model.filePath ?: container.models.hfDestination(repo, path).absolutePath)
    val hf = container.hfClient

    // Read the token ourselves: the process may have been started by WorkManager just now,
    // before the app's async settings collector ran (gated models would 401 otherwise).
    val token = container.settings.current().hfToken

    return try {
      // Pin the download to a commit so a resume can never mix two versions of the file.
      if (model.hfRevision.isNullOrBlank() || model.hfRevision == "main") {
        val sha = hf.commitSha(repo)
        model = model.copy(hfRevision = sha)
        dao.upsert(model)
      }
      val url = hf.downloadUrl(repo, path, model.hfRevision!!)

      val partial = container.downloader.partFile(dest).length()
      val remaining = (model.sizeBytes - partial).coerceAtLeast(0)
      dest.parentFile?.mkdirs()
      val free = dest.parentFile?.usableSpace ?: Long.MAX_VALUE
      if (remaining + STORAGE_MARGIN_BYTES > free) {
        throw InsufficientStorageException(remaining + STORAGE_MARGIN_BYTES, free)
      }

      runCatching { setForeground(foregroundInfo(model.displayName, partial, model.sizeBytes)) }
      dao.updateProgress(modelId, ModelState.DOWNLOADING, partial)

      var lastDbWrite = 0L
      var lastPercent = -1
      container.downloader.download(
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
          dao.updateProgress(modelId, ModelState.DOWNLOADING, done)
        }
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        if (percent != lastPercent) {
          lastPercent = percent
          runCatching { setForeground(foregroundInfo(model.displayName, done, total)) }
        }
      }
      val ready =
        container.models.withMetadata(
          model.copy(state = ModelState.READY, bytesDone = dest.length(), filePath = dest.absolutePath, errorMessage = null),
          dest,
        )
      dao.upsert(ready)
      Result.success()
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
      if (transient && runAttemptCount < MAX_WORK_ATTEMPTS) {
        // Network dropped for longer than the downloader's own retries: let WorkManager
        // back off and resume when connectivity returns.
        settle(modelId, dest, ModelState.DOWNLOADING, "Waiting for network… (${e.message})")
        Result.retry()
      } else {
        settle(modelId, dest, ModelState.ERROR, userMessage(e))
        Result.failure()
      }
    }
  }

  /** Best-effort state write that never throws (the DB itself may be the problem, e.g. disk full). */
  private suspend fun settle(modelId: String, dest: File, state: ModelState, message: String?) {
    withContext(NonCancellable) {
      runCatching {
        val partial = container.downloader.partFile(dest).length()
        container.db.models().updateProgress(modelId, state, partial, message)
      }
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

  override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("model", 0, 0)

  private fun foregroundInfo(name: String, done: Long, total: Long): ForegroundInfo {
    val nm = applicationContext.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW)
      )
    }
    val percent = if (total > 0) (done * 100 / total).toInt() else 0
    val notification =
      NotificationCompat.Builder(applicationContext, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_download)
        .setContentTitle("Downloading $name")
        .setContentText(if (total > 0) "$percent%" else "Starting…")
        .setProgress(100, percent, total <= 0)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(
          0,
          "Pause",
          WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        )
        .build()
    val notificationId = id.hashCode()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(notificationId, notification)
    }
  }

  companion object {
    const val KEY_MODEL_ID = "model_id"
    private const val MAX_WORK_ATTEMPTS = 8
    /** Keep this much free space beyond the file itself. */
    const val STORAGE_MARGIN_BYTES = 300L * 1024 * 1024
    private const val CHANNEL = "downloads"
  }
}
