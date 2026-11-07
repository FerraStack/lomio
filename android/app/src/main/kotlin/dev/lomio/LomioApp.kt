package dev.lomio

import android.app.Application
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LomioApp : Application() {
  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    container = AppContainer(this)
    container.scope.launch {
      container.chats.recoverInterrupted()
      container.settings.settings.map { it.hfToken }.distinctUntilChanged().collect {
        container.setHfToken(it)
      }
    }
    container.scope.launch { container.modelManager.onAppStart() }
  }
}
