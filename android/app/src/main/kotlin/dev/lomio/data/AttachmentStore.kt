package dev.lomio.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import dev.lomio.domain.SettingsJson
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Message attachments are stored as a JSON array of file names (see [AttachmentStore]). */
object Attachments {
  fun encode(names: List<String>): String? = names.takeIf { it.isNotEmpty() }?.let { SettingsJson.json.encodeToString(it) }

  fun decode(json: String?): List<String> =
    json?.let { runCatching { SettingsJson.json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()
}

/**
 * Images attached to chat messages. A picked image is decoded once (EXIF rotation applied,
 * HEIC/WebP/PNG converted), scaled down to [MAX_SIDE_PX] and saved as JPEG, plus a small
 * thumbnail for the chat list. The model reads the JPEG through libmtmd (stb_image), which
 * handles JPEG and PNG but not HEIC, so the conversion is required, not only a size saving.
 *
 * Files live in `filesDir/attachments` (app-private; the Photo Picker grants read access only
 * temporarily, so we keep our own copy).
 */
class AttachmentStore(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
  private val thumbs: File
    get() = File(dir, "thumbs")

  fun file(name: String): File = File(dir, name)

  fun thumbnail(name: String): File = File(thumbs, name)

  /** Copies the image at [uri] into the store and returns its file name. */
  suspend fun import(resolver: ContentResolver, uri: Uri): String =
    withContext(Dispatchers.IO) {
      val bitmap =
        try {
          ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (w, h) = fitInside(info.size.width, info.size.height, MAX_SIDE_PX)
            decoder.setTargetSize(w, h)
          }
        } catch (e: Exception) {
          throw IOException("Couldn't read the image (${e.message})", e)
        }
      save(bitmap)
    }

  /** Saves [bitmap] (already decoded) as a new attachment; used by import and tests. */
  fun save(bitmap: Bitmap): String {
    dir.mkdirs()
    thumbs.mkdirs()
    val name = "${UUID.randomUUID()}.jpg"
    val scaled = scaleDown(bitmap, MAX_SIDE_PX)
    file(name).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    val thumb = scaleDown(scaled, THUMB_SIDE_PX)
    thumbnail(name).outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    return name
  }

  /**
   * Deletes image files that no message references. Files newer than [graceMs] are kept: they
   * may belong to a message that is still being composed.
   */
  suspend fun sweep(referenced: Set<String>, graceMs: Long = 24L * 3600 * 1000): Int =
    withContext(Dispatchers.IO) {
      val cutoff = clock() - graceMs
      var removed = 0
      dir.listFiles()?.forEach { f ->
        if (f.isFile && f.name !in referenced && f.lastModified() < cutoff) {
          f.delete()
          thumbnail(f.name).delete()
          removed++
        }
      }
      removed
    }

  companion object {
    /** Longest side of a stored image; vision encoders work at 224–1024 px anyway. */
    const val MAX_SIDE_PX = 1024
    const val THUMB_SIDE_PX = 512

    fun fitInside(w: Int, h: Int, max: Int): Pair<Int, Int> {
      if (w <= max && h <= max) return w to h
      val scale = max.toDouble() / maxOf(w, h)
      return maxOf(1, (w * scale).roundToInt()) to maxOf(1, (h * scale).roundToInt())
    }

    private fun scaleDown(b: Bitmap, max: Int): Bitmap {
      val (w, h) = fitInside(b.width, b.height, max)
      return if (w == b.width && h == b.height) b else Bitmap.createScaledBitmap(b, w, h, true)
    }

    /** Decodes [file] for display, at most [maxPx] on the longest side (or null). */
    fun decodeForDisplay(file: File, maxPx: Int): Bitmap? {
      if (!file.exists()) return null
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.path, bounds)
      var sample = 1
      while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
      return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
  }
}
