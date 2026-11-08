package dev.lomio.data.download

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.RequiresApi
import dev.lomio.LomioApp
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Android 14+ user-initiated data transfer job for one model download. The notification is
 * attached within the first second (required for user-initiated jobs) and detached when done.
 */
@RequiresApi(34)
class DownloadJobService : JobService() {
  private val jobs = ConcurrentHashMap<Int, Job>()

  override fun onStartJob(params: JobParameters): Boolean {
    val modelId = params.extras.getString(KEY_MODEL_ID) ?: return false
    val c = (application as LomioApp).container
    val pause = DownloadScheduler.pauseIntent(this, modelId)
    fun notify(name: String?, done: Long, total: Long) =
      setNotification(
        params,
        params.jobId,
        DownloadNotifications.build(this, name, done, total, pause),
        JOB_END_NOTIFICATION_POLICY_REMOVE,
      )
    notify(null, 0, 0)
    jobs[params.jobId] =
      c.scope.launch {
        val outcome =
          try {
            // Attempt 0: a user-initiated job keeps retrying with backoff until the network is
            // back or the user pauses; the card says it is waiting for the network.
            DownloadRunner(c).run(modelId, attempt = 0) { name, done, total -> notify(name, done, total) }
          } catch (e: CancellationException) {
            return@launch // onStopJob: state already settled as PAUSED
          }
        jobs.remove(params.jobId)
        jobFinished(params, outcome == DownloadOutcome.RETRY)
      }
    return true
  }

  override fun onStopJob(params: JobParameters): Boolean {
    jobs.remove(params.jobId)?.cancel()
    // Reschedule unless the app (pause / delete) or the user cancelled it.
    return params.stopReason != JobParameters.STOP_REASON_CANCELLED_BY_APP &&
      params.stopReason != JobParameters.STOP_REASON_USER
  }

  companion object {
    const val KEY_MODEL_ID = "model_id"
  }
}

/** "Pause" in the download notification. */
class DownloadActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_PAUSE) return
    val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: return
    val c = (context.applicationContext as LomioApp).container
    val pending = goAsync()
    c.scope.launch {
      try {
        c.models.pause(modelId)
      } finally {
        pending.finish()
      }
    }
  }

  companion object {
    const val ACTION_PAUSE = "dev.lomio.action.PAUSE_DOWNLOAD"
    const val EXTRA_MODEL_ID = "model_id"
  }
}
