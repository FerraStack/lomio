package dev.lomio.api

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** A parsed HTTP/1.1 request. Header names are lower-case. */
class HttpRequest(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray) {
  val path: String
    get() = target.substringBefore('?')

  fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * Just enough HTTP/1.1 for a local API server: one request per connection, `Content-Length`
 * or chunked request bodies, and `Connection: close` on every response (an event stream ends
 * when the socket closes). No dependency, so it stays small and F-Droid friendly.
 */
object Http {
  const val MAX_HEADER_BYTES = 32 * 1024
  const val MAX_BODY_BYTES = 8 * 1024 * 1024

  /** Returns null when the client closed the connection before sending anything. */
  fun readRequest(input: InputStream): HttpRequest? {
    var budget = MAX_HEADER_BYTES
    fun line(): String? {
      val l = readLine(input, budget) ?: return null
      budget -= l.length + 2
      if (budget < 0) throw ApiError(431, "Request headers too large")
      return l
    }
    val requestLine = line() ?: return null
    val parts = requestLine.split(' ')
    if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw ApiError(400, "Malformed request line")
    val headers = mutableMapOf<String, String>()
    while (true) {
      val l = line() ?: throw ApiError(400, "Connection closed inside the headers")
      if (l.isEmpty()) break
      val colon = l.indexOf(':')
      if (colon <= 0) throw ApiError(400, "Malformed header line")
      headers[l.substring(0, colon).trim().lowercase()] = l.substring(colon + 1).trim()
    }
    val body =
      when {
        headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
        headers["content-length"] != null -> {
          val n = headers["content-length"]!!.toLongOrNull() ?: throw ApiError(400, "Bad Content-Length")
          if (n < 0) throw ApiError(400, "Bad Content-Length")
          if (n > MAX_BODY_BYTES) throw ApiError(413, "Request body too large")
          readExactly(input, n.toInt())
        }
        else -> ByteArray(0)
      }
    return HttpRequest(parts[0], parts[1], headers, body)
  }

  /** A CRLF- (or LF-) terminated ISO-8859-1 line; null on EOF before the first byte. */
  private fun readLine(input: InputStream, limit: Int): String? {
    val sb = StringBuilder()
    while (true) {
      val b = input.read()
      if (b == -1) {
        if (sb.isEmpty()) return null
        throw EOFException("EOF inside a line")
      }
      if (b == '\n'.code) return if (sb.endsWith('\r')) sb.substring(0, sb.length - 1) else sb.toString()
      sb.append(b.toChar())
      if (sb.length > limit) throw ApiError(431, "Request headers too large")
    }
  }

  private fun readExactly(input: InputStream, n: Int): ByteArray {
    val buf = ByteArray(n)
    var off = 0
    while (off < n) {
      val r = input.read(buf, off, n - off)
      if (r == -1) throw ApiError(400, "Body shorter than Content-Length")
      off += r
    }
    return buf
  }

  private fun readChunked(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    while (true) {
      val sizeLine = readLine(input, 1024) ?: throw ApiError(400, "Truncated chunked body")
      val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw ApiError(400, "Bad chunk size")
      if (size == 0) {
        // Trailers, then an empty line.
        while (!readLine(input, 8192).isNullOrEmpty()) Unit
        return out.toByteArray()
      }
      if (out.size() + size > MAX_BODY_BYTES) throw ApiError(413, "Request body too large")
      out.write(readExactly(input, size))
      readLine(input, 2) // CRLF after the chunk
    }
  }

  fun reason(status: Int): String =
    when (status) {
      200 -> "OK"
      400 -> "Bad Request"
      401 -> "Unauthorized"
      403 -> "Forbidden"
      404 -> "Not Found"
      405 -> "Method Not Allowed"
      413 -> "Payload Too Large"
      415 -> "Unsupported Media Type"
      431 -> "Request Header Fields Too Large"
      503 -> "Service Unavailable"
      else -> if (status >= 500) "Internal Server Error" else "Error"
    }
}

/** Writes one response. After [committed], only event-stream writes are possible. */
class HttpResponder(private val out: OutputStream) {
  var committed = false
    private set

  private fun head(status: Int, contentType: String, length: Long?, extra: Map<String, String>) {
    check(!committed) { "response already started" }
    committed = true
    val sb = StringBuilder("HTTP/1.1 $status ${Http.reason(status)}\r\n")
    sb.append("Content-Type: $contentType\r\n")
    if (length != null) sb.append("Content-Length: $length\r\n")
    sb.append("Connection: close\r\n")
    // The API key and the replies are private; no proxy should keep them.
    sb.append("Cache-Control: no-store\r\n")
    extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
    sb.append("\r\n")
    out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
  }

  fun json(status: Int, body: String, extra: Map<String, String> = emptyMap()) {
    val bytes = body.toByteArray()
    head(status, "application/json; charset=utf-8", bytes.size.toLong(), extra)
    out.write(bytes)
    out.flush()
  }

  fun error(e: ApiError) {
    val extra =
      when (e.status) {
        401 -> mapOf("WWW-Authenticate" to "Bearer")
        503 -> mapOf("Retry-After" to "5")
        else -> emptyMap()
      }
    json(e.status, OpenAiApi.errorJson(e), extra)
  }

  fun startEventStream() {
    head(200, "text/event-stream; charset=utf-8", null, mapOf("X-Accel-Buffering" to "no"))
    out.flush()
  }

  fun event(data: String) {
    out.write("data: $data\n\n".toByteArray())
    out.flush()
  }

  /** An SSE comment: ignored by clients, but a write that fails tells us the client left. */
  fun comment(text: String) {
    out.write(": $text\n\n".toByteArray())
    out.flush()
  }
}
