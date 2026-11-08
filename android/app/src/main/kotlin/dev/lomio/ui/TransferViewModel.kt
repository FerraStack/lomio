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
import dev.lomio.R
import dev.lomio.data.transfer.ChatArchive

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
      val done = c.text.getString(if (request.ids?.size == 1) R.string.transfer_chat_exported else R.string.transfer_chats_exported)
      _messages.emit(result.fold({ done }, { c.text.getString(R.string.transfer_export_failed, it.message.orEmpty()) }))
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
            r.imported == 0 && r.skipped > 0 -> c.text.getQuantityString(R.plurals.import_all_present, r.skipped, r.skipped)
            r.skipped > 0 -> c.text.getQuantityString(R.plurals.import_result_skipped, r.imported, r.imported, r.skipped)
            else -> c.text.getQuantityString(R.plurals.import_result, r.imported, r.imported)
          }
        } catch (e: ArchiveException) {
          archiveMessage(e)
        } catch (e: Exception) {
          c.text.getString(R.string.transfer_import_failed, e.message.orEmpty())
        }
      _messages.emit(message)
    }

  /** ChatArchive has no resources; its two user-facing errors are mapped here. */
  private fun archiveMessage(e: ArchiveException): String =
    when (e.message) {
      ChatArchive.NOT_LOMIO -> c.text.getString(R.string.archive_not_lomio)
      ChatArchive.NEWER_VERSION -> c.text.getString(R.string.archive_newer)
      else -> e.message.orEmpty()
    }
}
