package nexplorer.share

import android.content.Context
import android.os.Environment
import android.provider.Settings
import nexplorer.files.FolderScanner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkShare {
    class Running(val session: ShareSession, val server: ShareServer, val ip: InetAddress, val address: String)

    private val _running = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> = _running

    private val _received = MutableSharedFlow<File>(extraBufferCapacity = 64)
    val received: SharedFlow<File> = _received

    @Volatile var endedBecause: String? = null
        private set

    fun inbox(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NExplorer Share")

    fun deviceName(ctx: Context): String =
        Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: android.os.Build.MODEL

    fun start(ctx: Context, name: String): String? {
        if (_running.value != null) return null
        val ip = localAddress() ?: return "Connect to Wi-Fi, or turn on this phone's hotspot, first."
        val app = ctx.applicationContext
        val session = ShareSession(ShareNames.clean(name) ?: "Phone")
        val server = ShareServer(
            session,
            inbox(),
            asset = { file -> runCatching { app.assets.open("share/$file").use { it.readBytes() } }.getOrNull() },
            onReceived = {
                FolderScanner.invalidate()
                _received.tryEmit(it)
            },
        )
        session.onOffer = { o -> ShareService.ask(app, "${o.device.name} wants to share ${filesText(o)} with you") }
        val port = try {
            server.start(ip)
        } catch (e: Exception) {
            return "Couldn't start sharing: ${e.message}"
        }
        endedBecause = null
        _running.value = Running(session, server, ip, "${ip.hostAddress}:$port")
        ShareService.start(app)
        return null
    }

    fun shutdown(ctx: Context, because: String? = null) {
        val r = _running.value ?: return
        endedBecause = because
        _running.value = null
        Thread { r.server.stop() }.start()
        ShareService.stop(ctx.applicationContext, because)
    }

    fun stillHas(ip: InetAddress): Boolean =
        runCatching { NetworkInterface.getByInetAddress(ip)?.isUp == true }.getOrDefault(false)

    fun filesText(o: Offer): String {
        val n = o.files.size
        val mb = o.size / (1024.0 * 1024.0)
        val size = when {
            mb < 0.1 -> "under 0.1 MB"
            mb < 1024 -> String.format("%.1f MB", mb)
            else -> String.format("%.2f GB", mb / 1024)
        }
        return "${if (n == 1) "1 file" else "$n files"} ($size)"
    }

    private fun localAddress(): InetAddress? {
        val found = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .filterNot { n -> SKIP.any { n.name.startsWith(it) } }
            .flatMap { n -> n.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { n.name to it } }
        return found.minByOrNull { (name, _) -> PREFER.indexOfFirst { name.startsWith(it) }.let { if (it < 0) PREFER.size else it } }?.second
    }

    private val PREFER = listOf("wlan", "swlan", "ap", "softap", "eth")
    private val SKIP = listOf("rmnet", "ccmni", "v4-", "tun", "ppp", "dummy", "clat")
}
