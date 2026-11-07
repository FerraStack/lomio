package dev.lomio.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lomio.AppContainer
import dev.lomio.data.transfer.ArchiveException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ExportFormat(val mime: String, val extension: String, val label: String) {
  MARKDOWN("text/markdown", "md", "Markdown"),
  JSON("application/json", "json", "JSON (can be imported)"),
}

/** What to export; [ids] null = every chat. */
data class ExportRequest(val ids: List<String>?, val format: ExportFormat, val title: String? = null) {
  /** Suggested file name for the system file picker. */
  val fileName: String
    get() {
      val base =
        title?.let { t -> t.replace(Regex("[\\\\/:*?\"<>|\\n\\r]+"), " ").trim().take(60).ifBlank { null } }
          ?: "lomio-chats-${LocalDate.now()}"
      return "$base.${format.extension}"
    }
}

/** Writes exports to, and reads imports from, files the user picked (SAF). */
class TransferViewModel(private val c: AppContainer) : ViewModel() {
  /** The export waiting for the file picker; kept here so it survives a rotation. */
  var pending: ExportRequest? = null

  private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
  val messages: SharedFlow<String> = _messages.asSharedFlow()

  fun export(resolver: ContentResolver, uri: Uri, request: ExportRequest) =
    viewModelScope.launch {
      val result = runCatching {
        val text =
          when (request.format) {
            ExportFormat.JSON -> c.archive.exportJson(request.ids)
            ExportFormat.MARKDOWN -> c.archive.exportMarkdown(request.ids)
          }
        withContext(Dispatchers.IO) {
          resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("can't open the file")
        }
      }
      val what = if (request.ids?.size == 1) "Chat" else "Chats"
      _messages.emit(result.fold({ "$what exported" }, { "Export failed: ${it.message}" }))
    }

  fun import(resolver: ContentResolver, uri: Uri) =
    viewModelScope.launch {
      val message =
        try {
          val text =
            withContext(Dispatchers.IO) {
              resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("can't open the file")
            }
          val r = c.archive.importJson(text)
          when {
            r.imported == 0 && r.skipped > 0 -> "All ${r.skipped} chats are already here"
            r.skipped > 0 -> "Imported ${plural(r.imported)} (${r.skipped} already here)"
            else -> "Imported ${plural(r.imported)}"
          }
        } catch (e: ArchiveException) {
          e.message!!
        } catch (e: Exception) {
          "Import failed: ${e.message}"
        }
      _messages.emit(message)
    }

  private fun plural(n: Int) = if (n == 1) "1 chat" else "$n chats"
}
