package nexplorer.share

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ShareServer(
    private val session: ShareSession,
    private val inbox: File,
    private val asset: (String) -> ByteArray?,
    private val onReceived: (File) -> Unit = {},
) {
    @Volatile private var socket: ServerSocket? = null
    private val open = Collections.synchronizedSet(HashSet<Socket>())
    private val pool = ThreadPoolExecutor(2, 24, 30, TimeUnit.SECONDS, ArrayBlockingQueue(24))
    private var host = ""

    fun start(address: InetAddress, ports: IntRange = 8080..8099): Int {
        val s = ports.firstNotNullOfOrNull { p -> runCatching { ServerSocket(p, 50, address) }.getOrNull() }
            ?: ServerSocket(0, 50, address)
        socket = s
        host = "${address.hostAddress}:${s.localPort}"
        thread(name = "share-accept", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: IOException) { break }
                try { pool.execute { serve(c) } } catch (_: RejectedExecutionException) { c.close() }
            }
        }
        return s.localPort
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        synchronized(open) { open.forEach { runCatching { it.close() } } }
        pool.shutdownNow()
    }

    private fun serve(c: Socket) {
        open += c
        try {
            c.use {
                c.soTimeout = 30_000
                val input = BufferedInputStream(c.getInputStream())
                val out = BufferedOutputStream(c.getOutputStream(), 64 * 1024)
                val req = Request.read(input) ?: return
                route(req, out, c.inetAddress.hostAddress ?: "")
                out.flush()
                finish(c, input)
            }
        } catch (_: IOException) {
        } finally {
            open -= c
        }
    }

    private fun finish(c: Socket, input: InputStream) {
        runCatching {
            c.shutdownOutput()
            c.soTimeout = DRAIN_MS
            val buf = ByteArray(16 * 1024)
            var left = DRAIN_LIMIT
            while (left > 0) {
                val r = input.read(buf)
                if (r < 0) break
                left -= r
            }
        }
    }

    private fun route(req: Request, out: OutputStream, address: String) {
        if (req.headers["host"] != host) return text(out, 421, "Open this page by the address shown on the phone.")
        when {
            req.method == "GET" && req.path == "/" -> page(out, "index.html", "text/html; charset=utf-8")
            req.method == "GET" && req.path == "/app.js" -> page(out, "app.js", "text/javascript; charset=utf-8")
            req.method == "GET" && req.path == "/app.css" -> page(out, "app.css", "text/css; charset=utf-8")
            req.path.startsWith("/api/") -> api(req, out, address)
            else -> text(out, 404, "Not found")
        }
    }

    private fun page(out: OutputStream, name: String, type: String) {
        val body = asset(name) ?: return text(out, 404, "Not found")
        send(out, 200, type, body)
    }

    private fun api(req: Request, out: OutputStream, address: String) {
        if (req.method == "POST" && req.headers["x-nx"] != "1") return error(out, 403, "Not allowed")
        if (req.method == "POST" && req.path == "/api/join") return join(req, out, address)
        val device = session.device(req.cookie(COOKIE)) ?: return error(out, 401, "Join first")
        when {
            req.method == "GET" && req.path == "/api/me" -> json(out, 200, "{\"name\":${quote(device.name)}}")
            req.method == "GET" && req.path == "/api/list" -> json(out, 200, list(device))
            req.method == "GET" && req.path.startsWith("/api/file/") ->
                session.transferring { download(req.path.removePrefix("/api/file/").toIntOrNull(), device, out) }
            req.method == "GET" && req.path == "/api/zip" -> session.transferring { downloadAll(req.query["owner"], device, out) }
            req.method == "POST" && req.path == "/api/offer" -> offer(req, device, out)
            req.method == "GET" && req.path == "/api/offer" ->
                session.offer(req.query["id"])?.takeIf { it.device.token == device.token }
                    ?.let { json(out, 200, "{\"state\":${quote(stateOf(it))}}") }
                    ?: error(out, 404, "Not found")
            req.method == "POST" && req.path == "/api/upload" -> session.transferring { upload(req, device, out) }
            else -> error(out, 404, "Not found")
        }
    }

    private fun join(req: Request, out: OutputStream, address: String) {
        val length = req.contentLength
        if (length == null || length > 4096) return error(out, 400, "Bad request")
        val form = Form.parse(String(req.body.readExactly(length.toInt()), Charsets.UTF_8))
        when (val r = session.join(form["name"] ?: "", form["pin"], form["key"], address)) {
            is JoinResult.Joined -> json(
                out, 200, "{\"name\":${quote(r.device.name)}}",
                listOf("Set-Cookie" to "$COOKIE=${r.device.token}; Path=/; HttpOnly; SameSite=Strict"),
            )
            is JoinResult.Refused -> error(out, if (r.locked) 429 else 403, r.message)
        }
    }

    private fun list(me: Device): String {
        val folders = session.folders().joinToString(",") { (owner, files) ->
            val items = files.joinToString(",") { f ->
                "{\"id\":${f.id},\"name\":${quote(f.file.name)},\"size\":${f.file.length()}}"
            }
            "{\"name\":${quote(owner)},\"phone\":${owner == session.hostName},\"mine\":${owner == me.name},\"files\":[$items]}"
        }
        return "{\"me\":${quote(me.name)},\"folders\":[$folders]}"
    }

    private fun downloadAll(owner: String?, device: Device, out: OutputStream) {
        val files = session.folders().firstOrNull { it.first == owner }?.second
            ?: return error(out, 404, "This folder isn't shared any more")
        head(out, 200, listOf("Content-Type" to "application/zip", "Content-Disposition" to attachment("$owner.zip")))
        session.sendingOut(files.sumOf { it.file.length() }) { progress -> zipTo(out, files, device, progress) }
    }

    private fun zipTo(out: OutputStream, files: List<SharedFile>, device: Device, progress: ShareSession.Sending) {
        val zip = ZipOutputStream(out)
        zip.setLevel(Deflater.NO_COMPRESSION)
        val used = HashSet<String>()
        for (shared in files) {
            val f = shared.file
            var name = f.name
            var n = 2
            while (!used.add(name.lowercase())) {
                val dot = f.name.lastIndexOf('.').takeIf { it > 0 }
                name = if (dot == null) "${f.name} ($n)" else "${f.name.substring(0, dot)} ($n)${f.name.substring(dot)}"
                n++
            }
            zip.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
            f.inputStream().use { it.sendTo(zip, device, progress) }
            zip.closeEntry()
        }
        zip.finish()
    }

    private fun download(id: Int?, device: Device, out: OutputStream) {
        val shared = id?.let(session::file) ?: return error(out, 404, "This file isn't shared any more")
        val f = shared.file
        head(
            out, 200,
            listOf(
                "Content-Type" to "application/octet-stream",
                "Content-Length" to f.length().toString(),
                "Content-Disposition" to attachment(f.name),
            ),
        )
        session.sendingOut(f.length()) { progress -> f.inputStream().use { it.sendTo(out, device, progress) } }
    }

    private fun InputStream.sendTo(out: OutputStream, device: Device, progress: ShareSession.Sending) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val r = read(buf)
            if (r < 0) return
            stillIn(device)
            out.write(buf, 0, r)
            progress.sent += r
        }
    }

    private fun stillIn(device: Device) {
        if (session.device(device.token) == null) throw IOException("Removed")
    }

    private fun offer(req: Request, device: Device, out: OutputStream) {
        val length = req.contentLength
        if (length == null || length > 256 * 1024) return error(out, 400, "Too many files at once")
        val items = String(req.body.readExactly(length.toInt()), Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { line ->
            val size = line.substringBefore(':').toLongOrNull()?.takeIf { it >= 0 } ?: return error(out, 400, "Bad request")
            Form.decode(line.substringAfter(':')) to size
        }
        if (items.isEmpty() || items.size > 1000) return error(out, 400, "Send between 1 and 1000 files at once")
        val o = session.offer(device, items) ?: return error(out, 409, "The phone hasn't answered your last files yet")
        json(out, 200, "{\"id\":${quote(o.id)},\"state\":${quote(stateOf(o))}}")
    }

    private fun stateOf(o: Offer) = when {
        !o.answered -> "waiting"
        o.files.any { it.state == TransferState.Rejected } -> "rejected"
        else -> "approved"
    }

    private fun upload(req: Request, device: Device, out: OutputStream) {
        val offer = session.offer(req.query["offer"])?.takeIf { it.device.token == device.token }
            ?: return error(out, 404, "These files weren't asked for")
        val t = req.query["file"]?.toIntOrNull()?.let { offer.files.getOrNull(it) } ?: return error(out, 404, "This file wasn't asked for")
        val length = req.contentLength ?: return error(out, 411, "The file's size wasn't sent")
        if (length != t.size) return error(out, 400, "This file isn't the size that was asked for")
        synchronized(t) {
            if (t.state != TransferState.Approved) return error(out, 409, "This file wasn't approved, or was already sent")
            t.state = TransferState.Downloading
        }
        session.changed()
        val dir = File(inbox, device.name)
        var done = false
        val part = File(dir, ".nx-upload-${System.nanoTime()}.part")
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return error(out, 500, "The phone couldn't make a folder for the file")
            if (dir.usableSpace - length < KEEP_FREE) return error(out, 507, "The phone doesn't have enough free space for this file")
            part.outputStream().use { o ->
                req.body.copyExactly(length, o) {
                    t.received = it
                    stillIn(device)
                }
            }
            val target = synchronized(this) {
                File(dir, nexplorer.files.FileOps.uniqueName(dir, t.name)).also {
                    if (!part.renameTo(it)) throw IOException("rename")
                }
            }
            t.file = target
            t.state = TransferState.Done
            done = true
            session.received(target, device.name)
            onReceived(target)
            json(out, 200, "{\"ok\":true}")
        } finally {
            part.delete()
            if (!done) {
                t.state = TransferState.Failed
                session.changed()
            }
        }
    }

    private fun text(out: OutputStream, status: Int, message: String) =
        send(out, status, "text/plain; charset=utf-8", message.toByteArray())

    private fun error(out: OutputStream, status: Int, message: String) =
        json(out, status, "{\"error\":${quote(message)}}")

    private fun json(out: OutputStream, status: Int, body: String, extra: List<Pair<String, String>> = emptyList()) =
        send(out, status, "application/json; charset=utf-8", body.toByteArray(), extra)

    private fun send(out: OutputStream, status: Int, type: String, body: ByteArray, extra: List<Pair<String, String>> = emptyList()) {
        head(out, status, listOf("Content-Type" to type, "Content-Length" to body.size.toString()) + extra)
        out.write(body)
    }

    private fun head(out: OutputStream, status: Int, headers: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 $status ${REASONS[status] ?: "Error"}\r\n")
        (headers + COMMON).forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val COOKIE = "nx"
        const val KEEP_FREE = 200L * 1024 * 1024

        private const val DRAIN_LIMIT = 4L * 1024 * 1024
        private const val DRAIN_MS = 2_000

        private val REASONS = mapOf(
            200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
            411 to "Length Required", 421 to "Misdirected Request", 429 to "Too Many Requests",
            500 to "Internal Server Error", 507 to "Insufficient Storage",
        )

        private val COMMON = listOf(
            "Connection" to "close",
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "X-Frame-Options" to "DENY",
            "Referrer-Policy" to "no-referrer",
            "Content-Security-Policy" to "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; " +
                "img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
        )

        fun quote(s: String): String {
            val sb = StringBuilder("\"")
            for (ch in s) {
                when {
                    ch == '"' -> sb.append("\\\"")
                    ch == '\\' -> sb.append("\\\\")
                    ch < ' ' || ch in "<>&  " -> sb.append(String.format("\\u%04x", ch.code))
                    else -> sb.append(ch)
                }
            }
            return sb.append('"').toString()
        }

        fun attachment(name: String): String {
            val plain = name.map { if (it in ' '..'~' && it != '"' && it != '\\') it else '_' }.joinToString("")
            val utf = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            return "attachment; filename=\"$plain\"; filename*=UTF-8''$utf"
        }
    }
}

internal class Request(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: InputStream,
) {
    val contentLength: Long? get() = headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 }

    fun cookie(name: String): String? = headers["cookie"]?.split(';')
        ?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    companion object {
        private const val MAX_HEAD = 16 * 1024

        fun read(input: InputStream): Request? {
            val head = ByteArrayOutputStream()
            var last = 0
            while (last != 0x0D0A0D0A) {
                val b = input.read()
                if (b < 0 || head.size() >= MAX_HEAD) return null
                head.write(b)
                last = (last shl 8) or b
            }
            val lines = head.toString("ISO-8859-1").split("\r\n")
            val first = lines[0].split(' ')
            if (first.size != 3 || !first[2].startsWith("HTTP/1.")) return null
            val target = first[1]
            val headers = lines.drop(1).filter { ':' in it }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            return Request(
                first[0],
                target.substringBefore('?'),
                if ('?' in target) Form.parse(target.substringAfter('?')) else emptyMap(),
                headers,
                input,
            )
        }
    }
}

internal object Form {
    fun parse(s: String): Map<String, String> = s.split('&').filter { it.isNotEmpty() }.associate {
        decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
    }

    fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault("")
}

internal fun InputStream.readExactly(n: Int): ByteArray {
    val buf = ByteArray(n)
    var at = 0
    while (at < n) {
        val r = read(buf, at, n - at)
        if (r < 0) throw IOException("Ended early")
        at += r
    }
    return buf
}

internal fun InputStream.copyExactly(n: Long, out: OutputStream, onProgress: (Long) -> Unit = {}) {
    val buf = ByteArray(64 * 1024)
    var left = n
    while (left > 0) {
        val r = read(buf, 0, minOf(buf.size.toLong(), left).toInt())
        if (r < 0) throw IOException("Ended early")
        out.write(buf, 0, r)
        left -= r
        onProgress(n - left)
    }
}
