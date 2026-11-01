package nexplorer.download

import android.content.Context
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nexplorer.files.FileOps
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.coroutines.coroutineContext

enum class DownloadState { Queued, Running, Paused, Done, Failed }

class Part(val start: Long, val end: Long, @Volatile var pos: Long)

class Download internal constructor(val id: Long, val url: String, val dir: File, name: String, val created: Long) {
    @Volatile var name: String = name
        internal set
    @Volatile var state = DownloadState.Paused
        internal set
    @Volatile var total = -1L
        internal set
    @Volatile var speed = 0L
        internal set
    @Volatile var error: String? = null
        internal set
    @Volatile var finished = 0L
        internal set
    @Volatile var file: File? = null
        internal set

    @Volatile internal var parts: List<Part> = emptyList()
    internal var validator: String? = null
    internal var job: Job? = null
    internal val conns = CopyOnWriteArraySet<HttpURLConnection>()
    internal val part: File get() = File(dir, ".nexplorer-$id.part")

    val done: Long get() = if (state == DownloadState.Done) total else parts.sumOf { it.pos - it.start }

    fun info() = DownloadInfo(name, state, done, total, speed, error)
}

data class DownloadInfo(val name: String, val state: DownloadState, val done: Long, val total: Long, val speed: Long, val error: String?)

data class Probe(val name: String, val size: Long, val ranges: Boolean)

object Downloads {
    private const val MB = 1024L * 1024L
    private const val MAX_REDIRECTS = 10

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var items: List<Download> = emptyList()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private var app: Context? = null
    private var store: File? = null
    @Volatile private var lastSave = 0L

    private class Restart : IOException("the file changed on the server")
    private class Busy(val wait: Long) : IOException("the server is busy")

    fun init(ctx: Context) {
        synchronized(lock) {
            if (store != null) return
            app = ctx.applicationContext
            DownloadSettings.load(ctx)
            store = File(ctx.applicationContext.filesDir, "downloads.json")
            items = load()
        }
    }

    fun active(): List<Download> = items.filter { it.state != DownloadState.Done }.sortedBy { it.created }
    fun finished(): List<Download> = items.filter { it.state == DownloadState.Done }.sortedByDescending { it.finished }
    val busy: Int get() = items.count { it.state == DownloadState.Running || it.state == DownloadState.Queued }

    fun add(ctx: Context, url: String, dir: File, name: String, size: Long, start: Boolean): Download {
        init(ctx)
        val d = Download(newId(), DownloadNames.secure(url), dir, DownloadNames.clean(name), System.currentTimeMillis())
        d.total = size
        synchronized(lock) { items = items + d }
        if (start) start(ctx, listOf(d)) else changed(save = true)
        return d
    }

    fun start(ctx: Context, list: List<Download>) {
        init(ctx)
        list.filter { it.state == DownloadState.Paused || it.state == DownloadState.Failed }.forEach {
            it.error = null
            it.state = DownloadState.Queued
        }
        changed(save = true)
        pump()
    }

    fun stop(list: List<Download>) {
        list.forEach { d ->
            when (d.state) {
                DownloadState.Queued -> d.state = DownloadState.Paused
                DownloadState.Running -> {
                    d.state = DownloadState.Paused
                    halt(d)
                }
                else -> {}
            }
        }
        changed(save = true)
    }

    fun clear(list: List<Download>) {
        list.forEach { d ->
            val unfinished = d.state != DownloadState.Done
            if (unfinished) {
                d.state = DownloadState.Failed
                halt(d)
            }
            synchronized(lock) { items = items - d }
            if (unfinished) {
                val job = d.job
                scope.launch {
                    job?.join()
                    d.part.delete()
                }
            }
        }
        changed(save = true)
    }

    fun probe(url: String): Probe {
        val ua = DownloadSettings.options.value.userAgent.header()
        val (c, final) = open(DownloadNames.secure(url), ua, "bytes=0-0", null) {}
        try {
            val code = c.responseCode
            val size = if (code == 206) DownloadNames.rangeTotal(c.getHeaderField("Content-Range")) ?: -1L else c.contentLengthLong
            val ranges = code == 206 || c.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
            return Probe(DownloadNames.pick(c.getHeaderField("Content-Disposition"), final.toString(), extension(c)), size, ranges)
        } finally {
            c.disconnect()
        }
    }

    private fun newId(): Long = synchronized(lock) { maxOf(System.currentTimeMillis(), (items.maxOfOrNull { it.id } ?: 0) + 1) }

    private fun halt(d: Download) {
        d.job?.cancel()
        d.conns.forEach { runCatching { it.disconnect() } }
    }

    private fun changed(save: Boolean) {
        _version.update { it + 1 }
        if (save) save(force = true)
    }

    private fun pump() {
        synchronized(lock) {
            val max = DownloadSettings.options.value.simultaneous
            var running = items.count { it.state == DownloadState.Running }
            for (d in active()) {
                if (running >= max) break
                if (d.state != DownloadState.Queued) continue
                launch(d)
                running++
            }
        }
    }

    private fun launch(d: Download) {
        d.state = DownloadState.Running
        app?.let(DownloadService::start)
        d.job = scope.launch {
            try {
                try {
                    transfer(d)
                } catch (_: Restart) {
                    d.parts = emptyList()
                    d.part.delete()
                    transfer(d)
                }
                d.state = DownloadState.Done
                d.finished = System.currentTimeMillis()
                d.parts = emptyList()
            } catch (e: CancellationException) {
                if (d.state == DownloadState.Running) d.state = DownloadState.Paused
            } catch (t: Throwable) {
                if (d.state == DownloadState.Running) {
                    d.state = DownloadState.Failed
                    d.error = t.message ?: "the connection failed"
                }
            } finally {
                d.speed = 0
                d.conns.clear()
                changed(save = true)
                pump()
            }
        }
        changed(save = false)
    }

    private suspend fun transfer(d: Download) = coroutineScope {
        val o = DownloadSettings.options.value
        val ua = o.userAgent.header()
        var first: HttpURLConnection? = null
        if (d.parts.isEmpty()) {
            val (c, _) = open(d.url, ua, "bytes=0-", null) { d.conns += it }
            val code = c.responseCode
            val ranges = code == 206 || c.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
            d.total = if (code == 206) DownloadNames.rangeTotal(c.getHeaderField("Content-Range")) ?: -1L else c.contentLengthLong
            d.validator = c.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") } ?: c.getHeaderField("Last-Modified")
            val plan = if (ranges && d.total > 0 && o.threads > 1) DownloadPlan.segments(d.total, o.threads, o.minPartMb * MB) else emptyList()
            if (plan.size > 1) {
                c.disconnect()
                d.conns -= c
                d.parts = plan.map { Part(it.first, it.last, it.first) }
            } else {
                d.parts = listOf(Part(0, if (d.total > 0) d.total - 1 else -1, 0))
                first = c
            }
            changed(save = true)
        }
        d.dir.mkdirs()
        if (d.total > 0) {
            if (d.dir.usableSpace < d.total - d.done) throw IOException("not enough free space")
            if (o.preallocate && d.part.length() < d.total) RandomAccessFile(d.part, "rw").use { it.setLength(d.total) }
        }
        val sampler = launch {
            var mark = System.nanoTime()
            var markBytes = d.done
            while (true) {
                delay(1000)
                val now = System.nanoTime()
                val bytes = d.done
                d.speed = ((bytes - markBytes) * 1_000_000_000L / (now - mark)).coerceAtLeast(0)
                mark = now
                markBytes = bytes
                save(force = false)
            }
        }
        try {
            val opened = first
            d.parts.filter { it.end < 0 || it.pos <= it.end }
                .map { p -> async { fetch(d, p, ua, if (p === d.parts.first()) opened else null) } }
                .awaitAll()
        } finally {
            sampler.cancel()
        }
        if (d.total < 0) d.total = d.done
        if (d.done < d.total) throw IOException("the connection ended early")
        RandomAccessFile(d.part, "rw").use { it.setLength(d.total) }
        val final = File(d.dir, FileOps.uniqueName(d.dir, d.name))
        if (!d.part.renameTo(final)) throw IOException("couldn't save the file")
        d.name = final.name
        d.file = final
    }

    private suspend fun fetch(d: Download, p: Part, ua: String, opened: HttpURLConnection?) {
        val resuming = p.pos > 0 || d.parts.size > 1
        val c = opened ?: openPatiently(d, ua, "bytes=${p.pos}-${if (p.end >= 0) p.end.toString() else ""}", d.validator.takeIf { resuming })
        try {
            if (opened == null && c.responseCode == 200 && resuming) {
                if (d.parts.size > 1) throw Restart()
                p.pos = p.start
            }
            RandomAccessFile(d.part, "rw").use { raf ->
                raf.seek(p.pos)
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val want = if (p.end < 0) buf.size else minOf(buf.size.toLong(), p.end - p.pos + 1).toInt()
                        if (want <= 0) break
                        val n = input.read(buf, 0, want)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        p.pos += n
                    }
                }
            }
        } finally {
            c.disconnect()
            d.conns -= c
        }
        if (p.end >= 0 && p.pos <= p.end) throw IOException("the connection ended early")
    }

    private suspend fun openPatiently(d: Download, ua: String, range: String, validator: String?): HttpURLConnection {
        var tries = 0
        while (true) {
            try {
                return open(d.url, ua, range, validator) { d.conns += it }.first
            } catch (b: Busy) {
                if (++tries > 30) throw IOException("the server refused this many connections; try fewer threads")
                delay(if (b.wait > 0) b.wait.coerceAtMost(60_000) else (2_000L * tries).coerceAtMost(15_000))
            }
        }
    }

    private fun open(
        link: String,
        ua: String,
        range: String?,
        validator: String?,
        track: (HttpURLConnection) -> Unit,
    ): Pair<HttpURLConnection, URL> {
        var url = URL(link)
        var hops = 0
        while (true) {
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("Accept-Encoding", "identity")
            range?.let { conn.setRequestProperty("Range", it) }
            validator?.let { conn.setRequestProperty("If-Range", it) }
            track(conn)
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
                in 200..299 -> return conn to url
                429, 503 -> {
                    val wait = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1000) ?: 0L
                    conn.disconnect()
                    throw Busy(wait)
                }
                else -> {
                    conn.disconnect()
                    throw IOException(if (code == 416) "the server can't continue this download" else "the server answered $code")
                }
            }
        }
    }

    private fun extension(c: HttpURLConnection): String? =
        c.contentType?.substringBefore(';')?.trim()?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }

    private fun save(force: Boolean) {
        val f = store ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 2000) return
        lastSave = now
        val arr = JSONArray()
        synchronized(lock) {
            items.forEach { d ->
                arr.put(
                    JSONObject()
                        .put("id", d.id).put("url", d.url).put("dir", d.dir.path).put("name", d.name)
                        .put("state", d.state.name).put("total", d.total).put("created", d.created).put("finished", d.finished)
                        .put("file", d.file?.path).put("validator", d.validator).put("error", d.error)
                        .put("parts", JSONArray().apply { d.parts.forEach { put(JSONArray().put(it.start).put(it.end).put(it.pos)) } }),
                )
            }
            runCatching {
                val tmp = File(f.path + ".tmp")
                tmp.writeText(arr.toString())
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            }
        }
    }

    private fun load(): List<Download> {
        val f = store ?: return emptyList()
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Download(o.getLong("id"), o.getString("url"), File(o.getString("dir")), o.getString("name"), o.getLong("created")).apply {
                    val saved = runCatching { DownloadState.valueOf(o.getString("state")) }.getOrDefault(DownloadState.Paused)
                    state = if (saved == DownloadState.Running || saved == DownloadState.Queued) DownloadState.Paused else saved
                    total = o.optLong("total", -1L)
                    finished = o.optLong("finished", 0L)
                    file = o.optString("file").takeIf { o.has("file") && it.isNotEmpty() }?.let(::File)
                    validator = o.optString("validator").takeIf { o.has("validator") }
                    error = o.optString("error").takeIf { o.has("error") }
                    val ps = o.optJSONArray("parts") ?: JSONArray()
                    parts = (0 until ps.length()).map { j ->
                        val p = ps.getJSONArray(j)
                        Part(p.getLong(0), p.getLong(1), p.getLong(2))
                    }
                    if (parts.isNotEmpty() && !part.isFile) parts = emptyList()
                }
            }
        }.getOrDefault(emptyList())
    }
}
