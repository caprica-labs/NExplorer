package nexplorer.download

import android.content.Context
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nexplorer.files.FileOps
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import kotlin.coroutines.coroutineContext

enum class DownloadState { Running, Paused, Done, Failed }

class Download internal constructor(val id: Long, val url: String, val dir: File) {
    @Volatile var name: String = DownloadNames.clean(DownloadNames.fromUrl(url) ?: DownloadNames.FALLBACK)
        internal set
    @Volatile var state = DownloadState.Running
        internal set
    @Volatile var done = 0L
        internal set
    @Volatile var total = -1L
        internal set
    @Volatile var speed = 0L
        internal set
    @Volatile var error: String? = null
        internal set
    @Volatile var file: File? = null
        internal set

    internal var part: File? = null
    internal var validator: String? = null
    internal var job: Job? = null
    @Volatile internal var conn: HttpURLConnection? = null

    fun info() = DownloadInfo(name, state, done, total, speed, error)
}

data class DownloadInfo(val name: String, val state: DownloadState, val done: Long, val total: Long, val speed: Long, val error: String?)

object Downloads {
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) NExplorer"
    private const val MAX_REDIRECTS = 10

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _list = MutableStateFlow<List<Download>>(emptyList())
    val list: StateFlow<List<Download>> = _list
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private var nextId = 1L

    val running: Int get() = _list.value.count { it.state == DownloadState.Running }

    fun normalize(link: String): String? {
        val t = link.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val withScheme = if ("://" in t) t else "https://$t"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) return null
        return withScheme
    }

    fun start(ctx: Context, url: String, dir: File): Download {
        val d = Download(nextId++, url, dir)
        _list.update { listOf(d) + it }
        run(ctx.applicationContext, d)
        return d
    }

    fun pause(d: Download) {
        if (d.state != DownloadState.Running) return
        d.state = DownloadState.Paused
        stop(d)
    }

    fun resume(ctx: Context, d: Download) {
        if (d.state != DownloadState.Paused && d.state != DownloadState.Failed) return
        d.error = null
        run(ctx.applicationContext, d)
    }

    fun pauseAll() = _list.value.forEach(::pause)

    fun cancel(d: Download) {
        val wasRunning = d.state == DownloadState.Running
        d.state = DownloadState.Failed
        stop(d)
        val job = d.job
        scope.launch {
            if (wasRunning) job?.join()
            d.part?.delete()
        }
        _list.update { it - d }
    }

    fun remove(d: Download) {
        if (d.state == DownloadState.Done) _list.update { it - d } else cancel(d)
    }

    private fun stop(d: Download) {
        d.job?.cancel()
        runCatching { d.conn?.disconnect() }
        changed()
    }

    private fun changed() = _version.update { it + 1 }

    private fun run(app: Context, d: Download) {
        d.state = DownloadState.Running
        changed()
        DownloadService.start(app)
        d.job = scope.launch {
            try {
                fetch(d)
                d.state = DownloadState.Done
            } catch (e: CancellationException) {
                if (d.state == DownloadState.Running) d.state = DownloadState.Paused
            } catch (t: Throwable) {
                if (d.state == DownloadState.Running) {
                    d.state = DownloadState.Failed
                    d.error = t.message ?: "the connection failed"
                }
            } finally {
                d.conn = null
                d.speed = 0
                changed()
            }
        }
    }

    private suspend fun fetch(d: Download) {
        val part = d.part
        val have = part?.takeIf { it.isFile }?.length() ?: 0L
        var url = URL(d.url)
        var hops = 0
        var answer: HttpURLConnection? = null
        while (answer == null) {
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (have > 0) {
                conn.setRequestProperty("Range", "bytes=$have-")
                d.validator?.let { conn.setRequestProperty("If-Range", it) }
            }
            d.conn = conn
            coroutineContext.ensureActive()
            val code = conn.responseCode
            when (code) {
                in 300..399 -> {
                    val location = conn.getHeaderField("Location") ?: throw IOException("the server sent a broken redirect")
                    conn.disconnect()
                    val next = URL(url, location)
                    if (next.protocol !in setOf("http", "https")) throw IOException("the link led to an unsupported address")
                    if (url.protocol == "https" && next.protocol == "http") throw IOException("the link redirected to an unencrypted address")
                    if (++hops > MAX_REDIRECTS) throw IOException("too many redirects")
                    url = next
                }
                in 200..299 -> answer = conn
                else -> {
                    conn.disconnect()
                    throw IOException(if (code == 416) "the server can't continue this download" else "the server answered $code")
                }
            }
        }
        val c = answer

        val resuming = have > 0 && c.responseCode == 206
        if (part == null) {
            val ext = c.contentType?.substringBefore(';')?.trim()?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            d.name = DownloadNames.pick(c.getHeaderField("Content-Disposition"), url.toString(), ext)
            d.part = File(d.dir, ".${d.name}.${d.id}.part")
        }
        d.validator = c.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") } ?: c.getHeaderField("Last-Modified")
        d.total = when {
            resuming -> DownloadNames.rangeTotal(c.getHeaderField("Content-Range")) ?: -1L
            c.contentLengthLong >= 0 -> c.contentLengthLong
            else -> -1L
        }
        d.done = if (resuming) have else 0L

        val out = d.part!!
        out.parentFile?.mkdirs()
        var mark = System.nanoTime()
        var markBytes = d.done
        c.inputStream.use { input ->
            FileOutputStream(out, resuming).use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    d.done += n
                    val now = System.nanoTime()
                    if (now - mark >= 1_000_000_000L) {
                        d.speed = (d.done - markBytes) * 1_000_000_000L / (now - mark)
                        mark = now
                        markBytes = d.done
                    }
                }
            }
        }
        c.disconnect()
        if (d.total >= 0 && d.done < d.total) throw IOException("the connection ended early")

        val final = File(d.dir, FileOps.uniqueName(d.dir, d.name))
        if (!out.renameTo(final)) throw IOException("couldn't save the file")
        d.name = final.name
        d.file = final
        if (d.total < 0) d.total = d.done
    }
}

object DownloadNames {
    const val FALLBACK = "download"
    private const val MAX = 150

    fun pick(disposition: String?, url: String, ext: String?): String {
        val name = clean(fromDisposition(disposition) ?: fromUrl(url) ?: FALLBACK)
        val hasExt = name.lastIndexOf('.') > 0
        return if (hasExt || ext.isNullOrEmpty()) name else limit("$name.$ext")
    }

    fun fromDisposition(header: String?): String? {
        if (header == null) return null
        val star = Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(header)
        if (star != null) {
            val charset = star.groupValues[1].ifBlank { "UTF-8" }
            runCatching { URLDecoder.decode(star.groupValues[2].trim().replace("+", "%2B"), charset) }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val plain = Regex("""filename\s*=\s*("((?:[^"\\]|\\.)*)"|[^;]+)""", RegexOption.IGNORE_CASE).find(header) ?: return null
        val quoted = plain.groupValues[2]
        val value = if (plain.groupValues[1].startsWith('"')) quoted.replace(Regex("""\\(.)"""), "$1") else plain.groupValues[1].trim()
        return value.takeIf { it.isNotBlank() }
    }

    fun fromUrl(url: String): String? {
        val path = runCatching { URI(url).rawPath }.getOrNull() ?: return null
        val last = path.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return null
        return runCatching { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last).takeIf { it.isNotBlank() }
    }

    fun clean(raw: String): String {
        val leaf = raw.substringAfterLast('/').substringAfterLast('\\')
        val safe = leaf.map { c -> if (c < ' ' || c == '\u007f' || c in "\\/:*?\"<>|") '_' else c }.joinToString("")
        val trimmed = safe.trim().trim('.').trim()
        return limit(trimmed.ifEmpty { FALLBACK })
    }

    private fun limit(name: String): String {
        if (name.length <= MAX) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 && name.length - it <= 12 }
        return if (dot == null) name.take(MAX) else name.take(MAX - (name.length - dot)) + name.substring(dot)
    }

    fun rangeTotal(header: String?): Long? = header?.substringAfterLast('/')?.trim()?.toLongOrNull()
}
