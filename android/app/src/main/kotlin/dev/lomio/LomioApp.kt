package dev.lomio

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LomioApp : Application() {
  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    container = AppContainer(this)
    // Runs for every process start, including WorkManager starting us for a download:
    // no model loading here (it costs gigabytes of RAM).
    container.modelManager.onProcessStart()
    container.scope.launch {
      container.chats.recoverInterrupted()
      container.settings.settings.map { it.hfToken }.distinctUntilChanged().collect {
        container.setHfToken(it)
      }
    }
    ProcessLifecycleOwner.get().lifecycle.addObserver(ForegroundObserver(container))
  }

  /** Auto-loads on the first foreground; unloads after N minutes in the background. */
  private class ForegroundObserver(private val c: AppContainer) : DefaultLifecycleObserver {
    private var unloadJob: Job? = null

    override fun onStart(owner: LifecycleOwner) {
      unloadJob?.cancel()
      unloadJob = null
      c.scope.launch { c.modelManager.onFirstForeground() }
    }

    override fun onStop(owner: LifecycleOwner) {
      unloadJob?.cancel()
      unloadJob =
        c.scope.launch {
          val minutes = c.settings.current().unloadAfterMinutes
          if (minutes <= 0) return@launch
          delay(minutes * 60_000L)
          // Never cut off a reply that is still being written in the background.
          while (c.chatService.isBusy) delay(30_000)
          if (c.modelManager.activeModelId.value != null) c.modelManager.unload()
        }
    }
  }
}
