package dev.lomio.ui

import android.os.Looper
import dev.lomio.BuildConfig

/**
 * Debug-build guard for the streaming path: everything that turns engine output into Compose
 * state must run on the main thread. Upstream of that boundary (JNI callback → LlamaEngine →
 * ChatService → ChatReducer) only thread-safe StateFlows are written, deliberately off main;
 * the ViewModels map them to UI state on Main via `stateIn(viewModelScope)`.
 */
fun assertMainThread(what: String) {
  if (BuildConfig.DEBUG && !Looper.getMainLooper().isCurrentThread) {
    throw IllegalStateException("$what must run on the main thread, not ${Thread.currentThread().name}")
  }
}
