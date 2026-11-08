package dev.lomio.api

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.io.IOException
import java.net.BindException
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ApiServerStatus {
  data object Stopped : ApiServerStatus

  data object Starting : ApiServerStatus

  data class Running(val port: Int, val lan: Boolean) : ApiServerStatus

  data class Failed(val message: String) : ApiServerStatus
}

/**
 * Owns the server's lifecycle. The UI calls [setEnabled] / [change]; [ApiServerService] (a
 * foreground service, so the process and the loaded model stay alive) calls [startServer] and
 * [stopServer].
 */
class ApiServerController(
  private val context: Context,
  private val server: ApiServer,
  val settings: ApiServerSettingsRepository,
  private val scope: CoroutineScope,
) {
  private val _status = MutableStateFlow<ApiServerStatus>(ApiServerStatus.Stopped)
  val status: StateFlow<ApiServerStatus> = _status.asStateFlow()
  val stats = server.stats
  private val lock = Mutex()
  private var runningConfig: ApiServerConfig? = null

  val isRunning: Boolean
    get() = server.isRunning

  /** Persists the switch and starts or stops the foreground service. */
  fun setEnabled(on: Boolean) =
    scope.launch {
      settings.update { it.copy(enabled = on) }
      if (on) {
        startService()
      } else {
        stopService()
        clearFailure()
      }
    }

  /** Port, LAN or key changes; a running server restarts with them. */
  fun change(transform: (ApiServerPrefs) -> ApiServerPrefs) =
    scope.launch {
      val next = settings.update(transform)
      if (next.enabled) startService()
    }

  /** Starts the service if the switch is on (e.g. when the app comes to the foreground). */
  suspend fun resumeIfEnabled() {
    if (settings.current().enabled && !server.isRunning) startService()
  }

  fun startService() {
    ContextCompat.startForegroundService(context, Intent(context, ApiServerService::class.java))
  }

  private fun stopService() {
    context.stopService(Intent(context, ApiServerService::class.java))
  }

  /** (Re)starts the server with the saved settings; a no-op if they haven't changed. */
  suspend fun startServer(): Boolean =
    lock.withLock {
      var prefs = settings.current()
      if (prefs.lan && prefs.apiKey.isBlank()) prefs = settings.update { it.copy(apiKey = ApiServer.newApiKey()) }
      val config = ApiServerConfig(prefs.port, prefs.lan, prefs.apiKey.takeIf { prefs.lan })
      if (server.isRunning && config == runningConfig) return@withLock true
      server.stop()
      _status.value = ApiServerStatus.Starting
      try {
        val port = server.start(config)
        runningConfig = config
        _status.value = ApiServerStatus.Running(port, prefs.lan)
        true
      } catch (e: IOException) {
        runningConfig = null
        _status.value =
          ApiServerStatus.Failed(
            if (e is BindException) "Port ${prefs.port} is already in use. Pick another port."
            else "Couldn't start: ${e.message ?: e.javaClass.simpleName}"
          )
        false
      }
    }

  fun stopServer() {
    server.stop()
    runningConfig = null
    if (_status.value !is ApiServerStatus.Failed) _status.value = ApiServerStatus.Stopped
  }

  /** Clears a start failure once the user has seen it and turned the switch off. */
  fun clearFailure() {
    if (_status.value is ApiServerStatus.Failed) _status.value = ApiServerStatus.Stopped
  }

  companion object {
    /** This phone's IPv4 address on the local network (Wi-Fi, Ethernet, hotspot), if any. */
    fun lanAddress(): String? =
      runCatching {
          NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
        }
        .getOrNull()
  }
}
