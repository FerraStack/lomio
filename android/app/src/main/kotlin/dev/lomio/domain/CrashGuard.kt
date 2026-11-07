package dev.lomio.domain

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Crash-loop guard (RESEARCH.md §8.1): a marker is written synchronously before a model load
 * and cleared once the load finished. If the process dies inside llama.cpp (OOM kill, SIGSEGV),
 * the marker survives and the next launch must not auto-load that model again.
 */
class CrashGuard(private val prefs: SharedPreferences) {
  fun beginLoad(modelId: String) {
    // commit(), not apply(): the write must hit disk before native code can crash us.
    prefs.edit(commit = true) { putString(KEY, modelId) }
  }

  fun endLoad() {
    prefs.edit(commit = true) { remove(KEY) }
  }

  /** Model whose load was interrupted by a crash in a previous process; clears the marker. */
  fun consumeCrashedLoad(): String? {
    val id = prefs.getString(KEY, null) ?: return null
    endLoad()
    return id
  }

  private companion object {
    const val KEY = "loading_model_id"
  }
}
