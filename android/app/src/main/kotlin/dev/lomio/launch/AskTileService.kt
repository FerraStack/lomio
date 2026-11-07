package dev.lomio.launch

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.lomio.LomioApp
import dev.lomio.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile "Ask Lomio": opens a new chat with the keyboard up. The subtitle names the
 * loaded model, so the tile also tells you what will answer.
 */
class AskTileService : TileService() {
  private var scope: CoroutineScope? = null

  override fun onStartListening() {
    super.onStartListening()
    val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
    val c = (application as LomioApp).container
    s.launch {
      val name = c.modelManager.activeModelId.value?.let { c.models.get(it)?.displayName }
      qsTile?.apply {
        label = getString(R.string.tile_ask)
        state = Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) subtitle = name ?: "New chat"
        contentDescription = if (name != null) "Ask Lomio, $name" else "Ask Lomio"
        updateTile()
      }
    }
  }

  override fun onStopListening() {
    scope?.cancel()
    scope = null
    super.onStopListening()
  }

  override fun onClick() {
    super.onClick()
    if (isLocked) unlockAndRun { open() } else open()
  }

  @SuppressLint("StartActivityAndCollapseDeprecated")
  private fun open() {
    val intent = LaunchIntents.newChat(this, ask = true)
    if (Build.VERSION.SDK_INT >= 34) {
      startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    } else {
      @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }
  }
}
