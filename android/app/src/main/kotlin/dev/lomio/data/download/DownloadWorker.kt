package dev.lomio.data.download

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import androidx.annotation.RequiresApi
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
import dev.lomio.data.db.ModelState
import java.util.concurrent.TimeUnit

/**
 * Starts model downloads. On Android 14+ a download the user starts runs as a user-initiated
 * data transfer job: no foreground-service start from the background, and no 6 h/day dataSync
 * cap (issue #4). Older versions, or a start the system refuses, use WorkManager.
 */
class DownloadScheduler(private val context: Context) {
  enum class Path { USER_INITIATED_JOB, WORK_MANAGER }

  fun enqueue(modelId: String, estimatedBytes: Long = 0): Path {
    if (Build.VERSION.SDK_INT >= 34 && scheduleUserInitiated(modelId, estimatedBytes)) return Path.USER_INITIATED_JOB
    enqueueWork(modelId)
    return Path.WORK_MANAGER
  }

  @RequiresApi(34)
  private fun scheduleUserInitiated(modelId: String, estimatedBytes: Long): Boolean {
    val js = context.getSystemService(JobScheduler::class.java) ?: return false
    val network =
      NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    val job =
      JobInfo.Builder(jobId(modelId), ComponentName(context, DownloadJobService::class.java))
        .setUserInitiated(true)
        .setRequiredNetwork(network)
        .setEstimatedNetworkBytes(estimatedBytes.coerceAtLeast(0), 0)
        .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        .setExtras(PersistableBundle().apply { putString(DownloadJobService.KEY_MODEL_ID, modelId) })
        .build()
    return try {
      js.schedule(job) == JobScheduler.RESULT_SUCCESS
    } catch (e: RuntimeException) {
      // Only allowed while the app is visible; fall back to WorkManager otherwise.
      Log.w(TAG, "user-initiated job refused, using WorkManager", e)
      false
    }
  }

  private fun enqueueWork(modelId: String) {
    val request =
      OneTimeWorkRequestBuilder<DownloadWorker>()
        .setInputData(workDataOf(DownloadWorker.KEY_MODEL_ID to modelId))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .addTag(TAG)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(workName(modelId), ExistingWorkPolicy.KEEP, request)
  }

  fun cancel(modelId: String) {
    if (Build.VERSION.SDK_INT >= 34) context.getSystemService(JobScheduler::class.java)?.cancel(jobId(modelId))
    WorkManager.getInstance(context).cancelUniqueWork(workName(modelId))
  }

  companion object {
    const val TAG = "model-download"

    fun workName(modelId: String) = "download-$modelId"

    /** Stable job id per model; the model id travels in the extras, so a collision is harmless. */
    fun jobId(modelId: String) = 0x10000 + (modelId.hashCode() and 0x7fff)

    /** Pause from the notification: handled by [DownloadActionReceiver]. */
    fun pauseIntent(context: Context, modelId: String): PendingIntent =
      PendingIntent.getBroadcast(
        context,
        jobId(modelId),
        Intent(context, DownloadActionReceiver::class.java)
          .setAction(DownloadActionReceiver.ACTION_PAUSE)
          .putExtra(DownloadActionReceiver.EXTRA_MODEL_ID, modelId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
  }
}

/** WorkManager path (Android 13 and older, or when a user-initiated job can't be scheduled). */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  private val container
    get() = (applicationContext as LomioApp).container

  override suspend fun doWork(): Result {
    val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
    var foregroundRefused = false
    val outcome =
      DownloadRunner(container).run(modelId, runAttemptCount) { name, done, total ->
        if (foregroundRefused) return@run
        try {
          setForeground(foregroundInfo(modelId, name, done, total))
        } catch (e: Exception) {
          // Android 12+ refuses to start a foreground service from the background: keep going
          // as a plain worker, and say so on the model card.
          foregroundRefused = true
          Log.w(TAG, "foreground refused", e)
          val dao = container.db.models()
          dao.get(modelId)?.let { dao.updateProgress(modelId, ModelState.DOWNLOADING, it.bytesDone, DownloadRunner.NO_NOTIFICATION) }
        }
      }
    return when (outcome) {
      DownloadOutcome.DONE -> Result.success()
      DownloadOutcome.RETRY -> Result.retry()
      DownloadOutcome.FAILED -> Result.failure()
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo =
    foregroundInfo(inputData.getString(KEY_MODEL_ID).orEmpty(), null, 0, 0)

  private fun foregroundInfo(modelId: String, name: String?, done: Long, total: Long): ForegroundInfo {
    val notification =
      DownloadNotifications.build(applicationContext, name, done, total, DownloadScheduler.pauseIntent(applicationContext, modelId))
    val notificationId = DownloadScheduler.jobId(modelId)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(notificationId, notification)
    }
  }

  companion object {
    const val KEY_MODEL_ID = "model_id"
    /** Kept for callers that size checks before enqueueing. */
    const val STORAGE_MARGIN_BYTES = DownloadRunner.STORAGE_MARGIN_BYTES
  }
}

private const val TAG = "lomio-download"
