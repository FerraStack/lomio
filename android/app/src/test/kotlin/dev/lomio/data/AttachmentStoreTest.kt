package dev.lomio.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentStoreTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun savesAScaledJpegAndAThumbnail() {
    val store = AttachmentStore(tmp.root)
    val big = Bitmap.createBitmap(3000, 1500, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
    val name = store.save(big)
    val stored = BitmapFactory.decodeFile(store.file(name).path)
    assertEquals(1024, stored.width)
    assertEquals(512, stored.height)
    val thumb = BitmapFactory.decodeFile(store.thumbnail(name).path)
    assertEquals(512, thumb.width)
    assertEquals(256, thumb.height)
  }

  @Test
  fun fitInsideKeepsSmallImagesAndAspect() {
    assertEquals(800 to 600, AttachmentStore.fitInside(800, 600, 1024))
    assertEquals(768 to 1024, AttachmentStore.fitInside(3000, 4000, 1024))
  }

  @Test
  fun sweepRemovesOnlyOldUnreferencedFiles() = runTest {
    var now = 10_000_000L
    val store = AttachmentStore(tmp.root) { now }
    val bmp = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
    val kept = store.save(bmp)
    val orphan = store.save(bmp)
    val fresh = store.save(bmp)
    store.file(kept).setLastModified(1_000)
    store.file(orphan).setLastModified(1_000)
    store.file(fresh).setLastModified(now - 1_000)
    assertEquals(1, store.sweep(referenced = setOf(kept), graceMs = 60_000))
    assertTrue(store.file(kept).exists())
    assertFalse(store.file(orphan).exists())
    assertFalse(store.thumbnail(orphan).exists())
    assertTrue("too new to be an orphan", store.file(fresh).exists())
  }
}
