package nexplorer.share

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.file.Files

class ShareServerTest {
    private lateinit var dir: File
    private lateinit var inbox: File
    private lateinit var session: ShareSession
    private lateinit var server: ShareServer
    private var port = 0

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("share").toFile()
        inbox = File(dir, "inbox")
        session = ShareSession("Phone")
        server = ShareServer(session, inbox, { name -> "asset:$name".toByteArray() })
        port = server.start(InetAddress.getLoopbackAddress(), 0..0)
    }

    @After
    fun tearDown() {
        server.stop()
        dir.deleteRecursively()
    }

    private class Reply(val status: Int, val body: String, val headers: Map<String, List<String>>)

    private fun call(
        method: String,
        path: String,
        body: ByteArray? = null,
        cookie: String? = null,
        host: String = "127.0.0.1:$port",
        nx: Boolean = true,
    ): Reply {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Host", host)
        if (nx) c.setRequestProperty("X-NX", "1")
        cookie?.let { c.setRequestProperty("Cookie", "nx=$it") }
        if (body != null) {
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        val status = c.responseCode
        val text = (if (status < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
        return Reply(status, text, c.headerFields.filterKeys { it != null })
    }

    private fun join(name: String, pin: String? = null, key: String? = null): Reply {
        val form = listOfNotNull("name=$name", pin?.let { "pin=$it" }, key?.let { "key=$it" }).joinToString("&")
        return call("POST", "/api/join", form.toByteArray())
    }

    private fun token(r: Reply) = r.headers["Set-Cookie"]!!.first().substringAfter("nx=").substringBefore(';')

    init {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true")
    }

    @Test
    fun pageIsServedWithoutJoining() {
        val r = call("GET", "/")
        assertEquals(200, r.status)
        assertEquals("asset:index.html", r.body)
        assertTrue(r.headers["Content-Security-Policy"]!!.first().contains("default-src 'none'"))
    }

    @Test
    fun otherAddressesAreTurnedAway() {
        assertEquals(421, call("GET", "/", host = "evil.example:$port").status)
    }

    @Test
    fun everythingElseNeedsJoining() {
        assertEquals(401, call("GET", "/api/list").status)
        assertEquals(401, call("GET", "/api/file/1").status)
        assertEquals(401, call("POST", "/api/offer", "1:a.txt".toByteArray()).status)
    }

    @Test
    fun postsWithoutTheHeaderAreRefused() {
        assertEquals(403, call("POST", "/api/join", "name=A&pin=${session.pin}".toByteArray(), nx = false).status)
    }

    @Test
    fun joinWithPinOrKey() {
        val a = join("Ali", pin = session.pin)
        assertEquals(200, a.status)
        assertTrue(a.headers["Set-Cookie"]!!.first().contains("HttpOnly"))
        val b = join("Ali", key = session.key)
        assertEquals(200, b.status)
        assertTrue(b.body.contains("Ali (2)"))
        assertEquals(403, join("Sam", pin = "000000".takeIf { it != session.pin } ?: "111111").status)
    }

    private fun offer(me: String, vararg files: Pair<String, Int>): String {
        val body = files.joinToString("\n") { (name, size) -> "$size:" + java.net.URLEncoder.encode(name, "UTF-8") }
        val r = call("POST", "/api/offer", body.toByteArray(), cookie = me)
        assertEquals(r.body, 200, r.status)
        return r.body.substringAfter("\"id\":\"").substringBefore('"')
    }

    private fun state(me: String, id: String) = call("GET", "/api/offer?id=$id", cookie = me).body

    @Test
    fun uploadNeedsApprovalThenDownload() {
        val me = token(join("Ali", pin = session.pin))
        val id = offer(me, "notes ü.txt" to 5)
        assertTrue(state(me, id).contains("waiting"))
        assertEquals(409, call("POST", "/api/upload?offer=$id&file=0", "hello".toByteArray(), cookie = me).status)
        session.answer(session.pending().single(), approve = true)
        assertTrue(state(me, id).contains("approved"))
        assertEquals(400, call("POST", "/api/upload?offer=$id&file=0", "hello!".toByteArray(), cookie = me).status)
        assertEquals(200, call("POST", "/api/upload?offer=$id&file=0", "hello".toByteArray(), cookie = me).status)
        assertEquals(409, call("POST", "/api/upload?offer=$id&file=0", "hello".toByteArray(), cookie = me).status)
        assertEquals(404, call("POST", "/api/upload?offer=$id&file=1", "hello".toByteArray(), cookie = me).status)
        val saved = File(inbox, "Ali/notes ü.txt")
        assertEquals("hello", saved.readText())
        assertEquals(TransferState.Done, session.transfers().single().state)

        val list = call("GET", "/api/list", cookie = me)
        assertTrue(list.body, list.body.contains("\"name\":\"Ali\""))
        val fileId = session.folders().single().second.single().id
        val down = call("GET", "/api/file/$fileId", cookie = me)
        assertEquals("hello", down.body)
        assertTrue(down.headers["Content-Disposition"]!!.first().startsWith("attachment"))
    }

    @Test
    fun rejectedFilesCantBeSent() {
        val me = token(join("Ali", pin = session.pin))
        val id = offer(me, "a.txt" to 1)
        session.answer(session.pending().single(), approve = false)
        assertTrue(state(me, id).contains("rejected"))
        assertEquals(409, call("POST", "/api/upload?offer=$id&file=0", "x".toByteArray(), cookie = me).status)
        assertFalse(File(inbox, "Ali/a.txt").exists())
    }

    @Test
    fun approveAllSkipsAskingForThatDeviceOnly() {
        val ali = token(join("Ali", pin = session.pin))
        val sam = token(join("Sam", pin = session.pin))
        offer(ali, "a.txt" to 1)
        session.answer(session.pending().single(), approve = true, trustDevice = true)
        assertTrue(state(ali, offer(ali, "b.txt" to 1)).contains("approved"))
        assertTrue(state(sam, offer(sam, "c.txt" to 1)).contains("waiting"))
        val samsId = session.pending().single().id
        assertEquals(404, call("GET", "/api/offer?id=$samsId", cookie = ali).status)
    }

    @Test
    fun oneAskAtATime() {
        val me = token(join("Ali", pin = session.pin))
        offer(me, "a.txt" to 1)
        assertEquals(409, call("POST", "/api/offer", "1:b.txt".toByteArray(), cookie = me).status)
    }

    @Test
    fun uploadNamesCantLeaveTheFolder() {
        val me = token(join("Ali", pin = session.pin))
        val id = offer(me, "../../evil.txt" to 1)
        session.answer(session.pending().single(), approve = true)
        call("POST", "/api/upload?offer=$id&file=0", "x".toByteArray(), cookie = me)
        assertTrue(File(inbox, "Ali/evil.txt").isFile)
        assertFalse(File(dir, "evil.txt").exists())
    }

    @Test
    fun onlyListedFilesCanBeDownloaded() {
        val me = token(join("Ali", pin = session.pin))
        val secret = File(dir, "secret.txt").apply { writeText("no") }
        val shared = File(dir, "shared.txt").apply { writeText("yes") }
        session.share(listOf(shared))
        val id = session.folders().single().second.single().id
        assertEquals(200, call("GET", "/api/file/$id", cookie = me).status)
        assertEquals(404, call("GET", "/api/file/${id + 1}", cookie = me).status)
        assertEquals(404, call("GET", "/api/file/../secret.txt", cookie = me).status)
        session.unshare(id)
        assertEquals(404, call("GET", "/api/file/$id", cookie = me).status)
        assertTrue(shared.isFile && secret.isFile)
    }

    @Test
    fun downloadAllGivesTheFolderAsAZip() {
        val me = token(join("Ali", pin = session.pin))
        val a = File(dir, "a.txt").apply { writeText("one") }
        val b = File(File(dir, "sub").apply { mkdirs() }, "a.txt").apply { writeText("two") }
        session.share(listOf(a, b))
        val c = URL("http://127.0.0.1:$port/api/zip?owner=Phone").openConnection() as HttpURLConnection
        c.setRequestProperty("Host", "127.0.0.1:$port")
        c.setRequestProperty("Cookie", "nx=$me")
        val entries = java.util.zip.ZipInputStream(c.inputStream).use { z ->
            generateSequence { z.nextEntry }.map { it.name to z.readBytes().decodeToString() }.toList()
        }
        assertEquals(listOf("a.txt" to "one", "a (2).txt" to "two"), entries)
        assertEquals(404, call("GET", "/api/zip?owner=Nobody", cookie = me).status)
        assertEquals(401, call("GET", "/api/zip?owner=Phone").status)
    }

    @Test
    fun removedDeviceIsOut() {
        val me = token(join("Ali", pin = session.pin))
        session.remove(session.devices().single())
        assertEquals(401, call("GET", "/api/list", cookie = me).status)
    }

    @Test
    fun removingADeviceCutsItsUpload() {
        val me = token(join("Ali", pin = session.pin))
        val id = offer(me, "big.bin" to 1_000_000)
        session.answer(session.pending().single(), approve = true)
        java.net.Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10_000
            val out = s.getOutputStream()
            out.write(
                ("POST /api/upload?offer=$id&file=0 HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nX-NX: 1\r\n" +
                    "Cookie: nx=$me\r\nContent-Length: 1000000\r\n\r\n").toByteArray(),
            )
            out.write(ByteArray(200_000))
            out.flush()
            val t = session.transfers().single()
            val until = System.currentTimeMillis() + 5_000
            while (t.received == 0L && System.currentTimeMillis() < until) Thread.sleep(20)
            session.remove(session.devices().single())
            runCatching { repeat(20) { out.write(ByteArray(40_000)); out.flush() } }
            val until2 = System.currentTimeMillis() + 5_000
            while (t.state != TransferState.Failed && System.currentTimeMillis() < until2) Thread.sleep(20)
            assertEquals(TransferState.Failed, t.state)
        }
        assertFalse(File(inbox, "Ali/big.bin").exists())
    }

    @Test
    fun onlyJoiningAndTransfersCountAsActivity() {
        val start = session.lastActivity
        Thread.sleep(30)
        val me = token(join("Ali", pin = session.pin))
        val joined = session.lastActivity
        assertTrue(joined > start)
        Thread.sleep(30)
        call("GET", "/api/list", cookie = me)
        call("GET", "/api/me", cookie = me)
        session.share(listOf(File(dir, "a.txt").apply { writeText("a") }))
        assertEquals(joined, session.lastActivity)
        val fileId = session.folders().single().second.single().id
        call("GET", "/api/file/$fileId", cookie = me)
        assertTrue(session.lastActivity > joined)
        session.transferring {
            Thread.sleep(30)
            assertTrue(System.currentTimeMillis() - session.lastActivity < 20)
        }
    }

    @Test
    fun shutdownStopsListening() {
        server.stop()
        val failed = runCatching { call("GET", "/") }.isFailure
        assertTrue(failed)
    }
}

class ShareSessionTest {
    private val wrong get() = "000000"

    @Test
    fun pinAndKeyAreNewEachTime() {
        val a = ShareSession("P")
        val b = ShareSession("P")
        assertTrue(a.pin.matches(Regex("\\d{6}")))
        assertTrue(a.key.length >= 20)
        assertFalse(a.key == b.key)
    }

    @Test
    fun fiveWrongPinsLockThatDeviceOut() {
        val s = ShareSession("P")
        val bad = if (s.pin == wrong) "111111" else wrong
        repeat(4) { assertTrue(s.join("A", bad, null, "1.1.1.1") is JoinResult.Refused) }
        val fifth = s.join("A", bad, null, "1.1.1.1") as JoinResult.Refused
        assertTrue(fifth.locked)
        assertTrue(s.join("A", s.pin, null, "1.1.1.1") is JoinResult.Refused)
        assertTrue(s.join("B", s.pin, null, "2.2.2.2") is JoinResult.Joined)
    }

    @Test
    fun tooManyWrongPinsOverallTurnPinJoiningOff() {
        val s = ShareSession("P")
        val bad = if (s.pin == wrong) "111111" else wrong
        repeat(ShareSession.WRONG_TOTAL) { s.join("A", bad, null, "10.0.0.$it") }
        assertFalse(s.pinOn)
        assertTrue(s.join("B", s.pin, null, "10.0.1.1") is JoinResult.Refused)
        assertTrue(s.join("B", null, s.key, "10.0.1.1") is JoinResult.Joined)
    }

    @Test
    fun newPinReplacesTheOldAndTurnsPinJoiningBackOn() {
        val s = ShareSession("P")
        val bad = if (s.pin == wrong) "111111" else wrong
        repeat(ShareSession.WRONG_TOTAL) { s.join("A", bad, null, "10.0.0.$it") }
        assertFalse(s.pinOn)
        val old = s.pin
        s.newPin()
        assertTrue(s.pinOn)
        if (s.pin != old) assertTrue(s.join("B", old, null, "10.0.1.1") is JoinResult.Refused)
        assertTrue(s.join("C", s.pin, null, "10.0.1.2") is JoinResult.Joined)
    }

    @Test
    fun namesAreCleanedAndKeptApart() {
        assertEquals("Ali", ShareNames.clean("  ../Ali/  "))
        assertNull(ShareNames.clean("../"))
        assertEquals("a b", ShareNames.clean("a\n\tb"))
        assertEquals("Phone (2)", ShareNames.unique("Phone", listOf("phone")))
        assertEquals("evil.txt", ShareNames.fileName("../../evil.txt"))
        val s = ShareSession("Phone")
        val joined = s.join("Phone", s.pin, null, "1") as JoinResult.Joined
        assertEquals("Phone (2)", joined.device.name)
    }

    @Test
    fun quoteEscapesMarkup() {
        assertEquals("\"\\u003cb\\u003e\\\"\"", ShareServer.quote("<b>\""))
        assertNotNull(ShareServer.attachment("a\"\r\nb.txt").takeIf { '\r' !in it && '\n' !in it })
    }
}
