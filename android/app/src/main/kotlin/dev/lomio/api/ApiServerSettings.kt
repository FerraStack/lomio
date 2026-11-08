package dev.lomio.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class ApiServerPrefs(
  /** Off by default: nothing listens until the user turns it on. */
  val enabled: Boolean = false,
  val port: Int = ApiServer.DEFAULT_PORT,
  /** Listen on all interfaces (Wi-Fi) instead of 127.0.0.1; requires [apiKey]. */
  val lan: Boolean = false,
  val apiKey: String = "",
  /** Ask for [apiKey] on 127.0.0.1 too, so other apps on the phone need it as well. */
  val requireKeyLocal: Boolean = false,
) {
  /** Whether requests must carry [apiKey]: always on the LAN, optionally on this phone. */
  val keyRequired: Boolean
    get() = lan || requireKeyLocal
}

/**
 * Its own DataStore file (`datastore/api_server.preferences_pb`), which the backup and
 * device-transfer rules already exclude with the rest of `datastore/`.
 */
class ApiServerSettingsRepository(private val store: DataStore<Preferences>) {
  val prefs: Flow<ApiServerPrefs> =
    store.data.map { p ->
      ApiServerPrefs(
        enabled = p[ENABLED] ?: false,
        port = p[PORT] ?: ApiServer.DEFAULT_PORT,
        lan = p[LAN] ?: false,
        apiKey = p[KEY] ?: "",
        requireKeyLocal = p[KEY_LOCAL] ?: false,
      )
    }

  suspend fun current(): ApiServerPrefs = prefs.first()

  suspend fun update(transform: (ApiServerPrefs) -> ApiServerPrefs): ApiServerPrefs {
    var result = ApiServerPrefs()
    store.edit { p ->
      val cur = ApiServerPrefs(p[ENABLED] ?: false, p[PORT] ?: ApiServer.DEFAULT_PORT, p[LAN] ?: false, p[KEY] ?: "", p[KEY_LOCAL] ?: false)
      val next = transform(cur)
      p[ENABLED] = next.enabled
      p[PORT] = next.port
      p[LAN] = next.lan
      p[KEY] = next.apiKey
      p[KEY_LOCAL] = next.requireKeyLocal
      result = next
    }
    return result
  }

  private companion object {
    val ENABLED = booleanPreferencesKey("enabled")
    val PORT = intPreferencesKey("port")
    val LAN = booleanPreferencesKey("lan")
    val KEY = stringPreferencesKey("api_key")
    val KEY_LOCAL = booleanPreferencesKey("require_key_local")
  }
}
