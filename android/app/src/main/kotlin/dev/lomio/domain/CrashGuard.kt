package dev.lomio.domain

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Crash-loop guard (RESEARCH.md §8.1): a marker is written synchronously before a model load
 * and cleared once the load finished. If the process dies inside llama.cpp (OOM kill, SIGSEGV),
 * the marker survives and the next launch must not auto-load that model again.
 */
class CrashGuard(private val prefs: SharedPreferences) {
  /** @param gpu the load offloads to the GPU (its driver initialises inside the guarded load). */
  fun beginLoad(modelId: String, gpu: Boolean = false) {
    // commit(), not apply(): the write must hit disk before native code can crash us.
    prefs.edit(commit = true) {
      putString(KEY, modelId)
      putBoolean(KEY_GPU, gpu)
    }
  }

  fun endLoad() {
    prefs.edit(commit = true) {
      remove(KEY)
      remove(KEY_GPU)
    }
  }

  /** True if the load found by the last [consumeCrashedLoad] was a GPU load. */
  var crashedWithGpu: Boolean = false
    private set

  /** Model whose load was interrupted by a crash in a previous process; clears the marker. */
  fun consumeCrashedLoad(): String? {
    val id = prefs.getString(KEY, null) ?: return null
    crashedWithGpu = prefs.getBoolean(KEY_GPU, false)
    endLoad()
    return id
  }

  private companion object {
    const val KEY = "loading_model_id"
    const val KEY_GPU = "loading_with_gpu"
  }
}
