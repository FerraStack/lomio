package dev.lomio.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.concurrent.thread
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainThreadGuardTest {
  @Test
  fun passesOnMainAndThrowsElsewhereInDebug() {
    assertMainThread("test") // Robolectric runs the test on the main looper
    var error: Throwable? = null
    thread(name = "lomio-llama") { error = runCatching { assertMainThread("Chat UI state") }.exceptionOrNull() }.join()
    assertTrue(error?.message.orEmpty(), error is IllegalStateException && error!!.message!!.contains("lomio-llama"))
  }
}
