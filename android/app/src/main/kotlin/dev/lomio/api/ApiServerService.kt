package dev.lomio.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.lomio.LomioApp
import dev.lomio.MainActivity
import dev.lomio.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import androidx.core.app.ServiceCompat

/**
 * Keeps the process (and the loaded model) alive while the local API server runs, with an
 * ongoing notification that says so and has a Stop button.
 *
 * Foreground service type `specialUse` (Android 14+): a local HTTP server fits none of the
 * other types, and unlike `dataSync` it has no 6-hour daily cap. Started only from the UI, so
 * the background-start restrictions don't apply. Not sticky: after the process dies there is no
 * model loaded, so restarting the server on its own would only answer errors.
 */
class ApiServerService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var watcher: Job? = null

  private val container
    get() = (application as LomioApp).container

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      container.apiServer.setEnabled(false)
      stopSelf()
      return START_NOT_STICKY
    }
    startInForeground(notification("Starting…"))
    scope.launch { if (!container.apiServer.startServer()) stopSelf() }
    if (watcher == null) watcher = scope.launch { keepNotificationCurrent() }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    // Stop updating the notification first: stopServer() changes the status, and a collector
    // still running would post the notification again after the service has gone.
    scope.cancel()
    container.apiServer.stopServer()
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    super.onDestroy()
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private suspend fun keepNotificationCurrent() {
    val c = container
    val modelName = c.modelManager.activeModelId.mapLatest { id -> id?.let { c.models.get(it)?.displayName } }
    combine(c.apiServer.status, c.apiServer.stats, modelName) { status, stats, model ->
        when (status) {
          is ApiServerStatus.Running -> {
            val host = if (status.lan) ApiServerController.lanAddress() ?: "0.0.0.0" else "127.0.0.1"
            val what = if (stats.active > 0) "Answering a request" else model ?: "No model loaded"
            "http://$host:${status.port}/v1 · $what"
          }
          is ApiServerStatus.Failed -> status.message
          else -> "Starting…"
        }
      }
      .collect { text -> getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }
  }

  private fun startInForeground(n: Notification) {
    if (Build.VERSION.SDK_INT >= 34) {
      startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
      startForeground(NOTIFICATION_ID, n)
    }
  }

  private fun notification(text: String): Notification {
    val nm = getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL) == null) {
      nm.createNotificationChannel(NotificationChannel(CHANNEL, "Local API server", NotificationManager.IMPORTANCE_LOW))
    }
    val open =
      PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val stop =
      PendingIntent.getService(
        this,
        1,
        Intent(this, ApiServerService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    return NotificationCompat.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.ic_stat_api)
      .setContentTitle("Local API server is on")
      .setContentText(text)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setContentIntent(open)
      .addAction(0, "Stop", stop)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .build()
  }

  companion object {
    const val CHANNEL = "api_server"
    const val NOTIFICATION_ID = 0x4150 // "AP"
    const val ACTION_STOP = "dev.lomio.api.STOP"
  }
}
