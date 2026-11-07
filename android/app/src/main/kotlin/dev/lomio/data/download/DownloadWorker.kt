package dev.lomio.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
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
    val model = dao.get(modelId) ?: return Result.failure()
    val repo = model.hfRepo ?: return Result.failure()
    val path = model.hfFile ?: return Result.failure()
    val dest = File(model.filePath ?: container.models.hfDestination(repo, path).absolutePath)
    val hf = container.hfClient
    val url = hf.downloadUrl(repo, path, model.hfRevision ?: "main")

    runCatching { setForeground(foregroundInfo(model.displayName, 0, 0)) }
    dao.updateProgress(modelId, ModelState.DOWNLOADING, model.bytesDone)

    var lastDbWrite = 0L
    var lastPercent = -1
    return try {
      container.downloader.download(
        url = url,
        dest = dest,
        expectedSize = model.sizeBytes.takeIf { it > 0 },
        expectedSha256 = model.sha256,
        headers = hf.authHeaders(url),
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
          model.copy(
            state = ModelState.READY,
            bytesDone = dest.length(),
            filePath = dest.absolutePath,
            errorMessage = null,
          ),
          dest,
        )
      dao.upsert(ready)
      Result.success()
    } catch (e: CancellationException) {
      withContext(NonCancellable) {
        val partial = container.downloader.partFile(dest).length()
        dao.get(modelId)?.let { dao.updateProgress(modelId, ModelState.PAUSED, partial) }
      }
      throw e
    } catch (e: Exception) {
      val partial = container.downloader.partFile(dest).length()
      dao.updateProgress(modelId, ModelState.ERROR, partial, e.message ?: e.javaClass.simpleName)
      Result.failure()
    }
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
    private const val CHANNEL = "downloads"
  }
}
