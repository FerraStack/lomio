package dev.lomio.ui.chat

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.lomio.data.AttachmentStore
import java.io.File

/**
 * Shows an attached image from app storage. Uses the small thumbnail the store writes next to
 * the image (`thumbs/<name>`), decoded synchronously and cached: it is ~512 px and decodes in a
 * few milliseconds, which keeps chat rendering (and screenshot tests) deterministic.
 */
@Composable
fun AttachmentImage(path: String, contentDescription: String, modifier: Modifier = Modifier, crop: Boolean = false) {
  val bitmap = remember(path) { Thumbnails.get(path) }
  if (bitmap == null) {
    Box(modifier.size(96.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
      Icon(Icons.Outlined.BrokenImage, contentDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    return
  }
  val image = remember(bitmap) { bitmap.asImageBitmap() }
  Image(
    image,
    contentDescription = contentDescription,
    contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
    modifier = modifier,
  )
}

private object Thumbnails {
  private val cache = LruCache<String, Bitmap>(32)

  fun get(path: String): Bitmap? {
    cache.get(path)?.let { return it }
    val full = File(path)
    val thumb = File(full.parentFile, "thumbs/${full.name}")
    val bitmap =
      AttachmentStore.decodeForDisplay(thumb, AttachmentStore.THUMB_SIDE_PX)
        ?: AttachmentStore.decodeForDisplay(full, AttachmentStore.THUMB_SIDE_PX)
        ?: return null
    cache.put(path, bitmap)
    return bitmap
  }
}
