package dev.lomio

import android.app.job.JobScheduler
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lomio.data.download.DownloadScheduler
import dev.lomio.data.settings.KeystoreSecretBox
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Issues #5 and #4 on a real device: the Keystore token box and user-initiated download jobs. */
@RunWith(AndroidJUnit4::class)
class SecureStorageAndDownloadsTest {
  private val app
    get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LomioApp

  @Test
  fun keystoreBoxRoundTripsAndNeverRepeatsCiphertext() {
    val box = KeystoreSecretBox(alias = "lomio_test_box")
    val a = box.seal("hf_abc")
    val b = box.seal("hf_abc")
    assertNotEquals("GCM needs a fresh IV per seal", a, b)
    assertFalse(a.contains("hf_abc"))
    assertEquals("hf_abc", box.open(a))
    assertEquals("hf_abc", box.open(b))
    // Tampering is detected by the GCM tag.
    val flipped = a.dropLast(4) + (if (a.takeLast(4) == "AAAA") "BBBB" else "AAAA")
    assertNull(box.open(flipped))
  }

  @Test
  fun tokenIsNotOnDiskInPlainText() = runBlocking {
    val token = "hf_E2E_${System.nanoTime()}"
    app.container.settings.update { it.copy(hfToken = token) }
    try {
      assertEquals(token, app.container.settings.current().hfToken)
      val files = File(app.filesDir, "datastore").listFiles().orEmpty()
      assertTrue("no DataStore file found", files.isNotEmpty())
      files.forEach { assertFalse("plain token in ${it.name}", it.readBytes().decodeToString().contains(token)) }
    } finally {
      app.container.settings.update { it.copy(hfToken = "") }
    }
  }

  @Test
  fun downloadsStartedFromTheUiAreUserInitiatedJobs() {
    ActivityScenario.launch(MainActivity::class.java).use {
      val id = "e2e-missing-model"
      val path = app.container.downloads.enqueue(id, 1_000_000)
      try {
        if (Build.VERSION.SDK_INT >= 34) {
          assertEquals(DownloadScheduler.Path.USER_INITIATED_JOB, path)
          app.getSystemService(JobScheduler::class.java).getPendingJob(DownloadScheduler.jobId(id))?.let {
            assertTrue(it.isUserInitiated)
          }
        } else {
          assertEquals(DownloadScheduler.Path.WORK_MANAGER, path)
        }
      } finally {
        app.container.downloads.cancel(id)
      }
    }
  }
}
