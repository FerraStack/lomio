package dev.lomio.data.hf

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

enum class HfSort(val apiValue: String, val label: String) {
  TRENDING("trendingScore", "Trending"),
  DOWNLOADS("downloads", "Downloads"),
  LIKES("likes", "Likes"),
  UPDATED("lastModified", "Recently updated"),
}

data class HfModel(
  val id: String,
  val author: String,
  val downloads: Long,
  val likes: Long,
  val lastModified: String?,
  val gated: Boolean,
)

data class HfFile(
  val repo: String,
  val path: String,
  val sizeBytes: Long,
  /** LFS oid = sha256 of the file content. */
  val sha256: String?,
  /** Commit the listing came from; downloads are pinned to it. Null = resolve at download. */
  val revision: String? = null,
) {
  val fileName: String
    get() = path.substringAfterLast('/')

  val quant: String?
    get() = GgufNames.quant(fileName)

  val isProjector: Boolean
    get() = fileName.startsWith("mmproj", ignoreCase = true)

  val splitPart: Pair<Int, Int>?
    get() = GgufNames.splitPart(fileName)
}

/**
 * Vision projectors: a repo that ships an `mmproj*.gguf` next to its weights is a vision model
 * (the llama.cpp / ggml-org convention). One projector serves every quant of the model.
 */
object Projectors {
  /** Projector quant preference: Q8_0 is the usual small-and-good choice, then f16/bf16. */
  private val PREFERENCE = listOf("Q8_0", "F16", "BF16")

  /** The projector to download with a model from [files] (a repo listing), or null if none. */
  fun pick(files: List<HfFile>): HfFile? {
    val candidates = files.filter { it.isProjector && it.splitPart == null }
    if (candidates.isEmpty()) return null
    for (q in PREFERENCE) {
      candidates.filter { projectorQuant(it.fileName) == q }.minByOrNull { it.sizeBytes }?.let { return it }
    }
    // Unknown naming: the smallest that isn't f32.
    return candidates.filter { projectorQuant(it.fileName) != "F32" }.minByOrNull { it.sizeBytes }
      ?: candidates.minByOrNull { it.sizeBytes }
  }

  /** "mmproj-model-f16.gguf" -> "F16"; "mmproj-SmolVLM-256M-Instruct-Q8_0.gguf" -> "Q8_0". */
  fun projectorQuant(fileName: String): String? =
    GgufNames.quant(fileName)
      ?: Regex("(?i)[-_.](f16|bf16|f32)\\.gguf$").find(fileName)?.groupValues?.get(1)?.uppercase()
}

object GgufNames {
  private val QUANT =
    Regex(
      "(?i)(?:^|[-_.])((?:IQ[1-4]_(?:XXS|XS|S|M|NL)|Q[2-8]_K(?:_[SMLX]{1,2})?|Q[4-8]_[01]|F16|BF16|F32|MXFP4|TQ[12]_0))(?=[-_.]|$)"
    )
  private val SPLIT = Regex("-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)

  fun quant(fileName: String): String? = QUANT.find(fileName)?.groupValues?.get(1)?.uppercase()

  fun splitPart(fileName: String): Pair<Int, Int>? =
    SPLIT.find(fileName)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

  /** "Qwen3-1.7B-Q4_K_M.gguf" -> "Qwen3 1.7B". */
  fun displayName(fileName: String): String {
    var base = fileName.removeSuffix(".gguf").removeSuffix(".GGUF")
    quant(fileName)?.let { q -> base = base.replace(Regex("[-_.]$q$", RegexOption.IGNORE_CASE), "") }
    return base.replace('_', ' ').replace('-', ' ').trim()
  }
}

/**
 * Minimal Hugging Face Hub client: the three endpoints PocketPal uses (RESEARCH.md §6).
 * The bearer token is only ever sent to huggingface.co, never to a mirror.
 */
/** Result of checking a Hugging Face token against `/api/whoami-v2`. */
sealed interface TokenCheck {
  data class Valid(val user: String) : TokenCheck

  /** Hugging Face refused the token (HTTP 401/403). */
  data class Rejected(val code: Int) : TokenCheck

  data class Unreachable(val message: String) : TokenCheck
}

class HfClient(
  private val http: OkHttpClient,
  private val baseUrl: String = DEFAULT_BASE_URL,
  private val token: () -> String? = { null },
) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun search(
    query: String,
    sort: HfSort = HfSort.TRENDING,
    author: String? = null,
    limit: Int = 30,
  ): List<HfModel> {
    val url =
      base()
        .newBuilder()
        .addPathSegments("api/models")
        .apply { if (query.isNotBlank()) addQueryParameter("search", query.trim()) }
        .apply { if (!author.isNullOrBlank()) addQueryParameter("author", author.trim()) }
        .addQueryParameter("filter", "gguf")
        .addQueryParameter("sort", sort.apiValue)
        .addQueryParameter("direction", "-1")
        .addQueryParameter("limit", limit.toString())
        .build()
    val items = json.decodeFromString<List<ApiModel>>(get(url))
    return items.map {
      HfModel(
        id = it.id,
        author = it.author ?: it.id.substringBefore('/'),
        downloads = it.downloads ?: 0,
        likes = it.likes ?: 0,
        lastModified = it.lastModified,
        gated = it.gated?.let { g -> (g as? JsonPrimitive)?.booleanOrNull ?: true } ?: false,
      )
    }
  }

  /** Commit sha that [revision] (a branch or tag) currently points to. */
  suspend fun commitSha(repo: String, revision: String = "main"): String {
    val url =
      base().newBuilder().addPathSegments("api/models").addPathSegments(repo).addPathSegment("revision").addPathSegment(revision).build()
    return json.decodeFromString<ApiRevision>(get(url)).sha
  }

  /**
   * GGUF files in a repo with sizes and sha256, sorted by size, listed at the commit [revision]
   * currently resolves to (so a later download fetches exactly these bytes).
   */
  suspend fun ggufFiles(repo: String, revision: String = "main"): List<HfFile> {
    val sha = runCatching { commitSha(repo, revision) }.getOrNull()
    val url =
      base()
        .newBuilder()
        .addPathSegments("api/models")
        .addPathSegments(repo)
        .addPathSegment("tree")
        .addPathSegment(sha ?: revision)
        .addQueryParameter("recursive", "true")
        .build()
    val entries = json.decodeFromString<List<ApiTreeEntry>>(get(url))
    return entries
      .filter { it.type == "file" && it.path.endsWith(".gguf", ignoreCase = true) }
      .map { HfFile(repo, it.path, it.lfs?.size ?: it.size ?: 0, it.lfs?.oid, sha) }
      .sortedBy { it.sizeBytes }
  }

  fun downloadUrl(repo: String, path: String, revision: String = "main"): String =
    base()
      .newBuilder()
      .addPathSegments(repo)
      .addPathSegment("resolve")
      .addPathSegment(revision)
      .addPathSegments(path)
      .build()
      .toString()

  /** Asks Hugging Face who [token] belongs to. Never throws. */
  suspend fun checkToken(token: String): TokenCheck =
    withContext(Dispatchers.IO) {
      val url = base().newBuilder().addPathSegments("api/whoami-v2").build()
      val req = Request.Builder().url(url).header("Authorization", "Bearer ${token.trim()}").build()
      try {
        http.newCall(req).execute().use { resp ->
          when {
            resp.code == 401 || resp.code == 403 -> TokenCheck.Rejected(resp.code)
            !resp.isSuccessful -> TokenCheck.Unreachable("HTTP ${resp.code}")
            else -> TokenCheck.Valid(json.decodeFromString<ApiWhoAmI>(resp.body.string()).name)
          }
        }
      } catch (e: IOException) {
        TokenCheck.Unreachable(e.message ?: e.javaClass.simpleName)
      } catch (e: kotlinx.serialization.SerializationException) {
        TokenCheck.Unreachable("unexpected reply")
      }
    }

  @Serializable private data class ApiWhoAmI(val name: String)

  /** Headers for a download request to [url]; adds the token only for huggingface.co. */
  fun authHeaders(url: String, token: String? = this.token()): Map<String, String> {
    val t = token?.takeIf { it.isNotBlank() } ?: return emptyMap()
    return if (isOfficialHost(url.toHttpUrl())) mapOf("Authorization" to "Bearer $t") else emptyMap()
  }

  private fun base(): HttpUrl = baseUrl.toHttpUrl()

  private suspend fun get(url: HttpUrl): String =
    withContext(Dispatchers.IO) {
      val req = Request.Builder().url(url).apply {
        authHeaders(url.toString()).forEach { (k, v) -> header(k, v) }
      }.build()
      http.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) throw IOException("Hugging Face returned HTTP ${resp.code}")
        resp.body.string()
      }
    }

  @Serializable
  private data class ApiModel(
    val id: String,
    val author: String? = null,
    val downloads: Long? = null,
    val likes: Long? = null,
    val lastModified: String? = null,
    // false | "auto" | "manual"
    val gated: JsonElement? = null,
  )

  @Serializable private data class ApiRevision(val sha: String)

  @Serializable
  private data class ApiTreeEntry(
    val type: String,
    val path: String,
    val size: Long? = null,
    val lfs: Lfs? = null,
  )

  @Serializable
  private data class Lfs(
    val oid: String? = null,
    val size: Long? = null,
    @SerialName("pointerSize") val pointerSize: Long? = null,
  )

  companion object {
    const val DEFAULT_BASE_URL = "https://huggingface.co"

    fun isOfficialHost(url: HttpUrl): Boolean =
      url.isHttps && (url.host == "huggingface.co" || url.host.endsWith(".huggingface.co"))
  }
}
