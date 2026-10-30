package nexplorer.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import nexplorer.MainActivity
import nexplorer.R
import nexplorer.ui.home.formatSize
import java.net.InetAddress

class ShareService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastReminder = 0L
    private var watching: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val running = NetworkShare.running.value
        channels()
        runCatching {
            ServiceCompat.startForeground(
                this, ON_ID, onNotification(running?.address ?: ""),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }
        if (intent?.action == STOP || running == null) {
            if (intent?.action == STOP) NetworkShare.shutdown(this)
            stop()
            intent?.getStringExtra(BECAUSE)?.let(::ended)
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).cancel(ENDED_ID)
        keepAwake()
        watch(running.ip)
        handler.removeCallbacks(check)
        handler.postDelayed(check, CHECK_EVERY)
        handler.removeCallbacks(progress)
        handler.postDelayed(progress, PROGRESS_EVERY)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) = endBecause("Android stops background sharing after 6 hours.")

    override fun onDestroy() {
        handler.removeCallbacks(check)
        handler.removeCallbacks(progress)
        unwatch()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun stop() {
        handler.removeCallbacks(progress)
        unwatch()
        getSystemService(NotificationManager::class.java).cancel(REMINDER_ID)
        answered(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun endBecause(because: String) {
        val wasOn = NetworkShare.running.value != null
        NetworkShare.shutdown(this, because)
        stop()
        if (wasOn) ended(because)
    }

    private fun ended(because: String) =
        notify(ENDED_ID, reminder("Network Share was shut down", because, alertOnce = true))

    private fun watch(ip: InetAddress) {
        if (watching != null) return
        val changed = { handler.post { if (NetworkShare.running.value?.ip == ip) endBecause(NETWORK_CHANGED) } }
        val callback = object : ConnectivityManager.NetworkCallback() {
            @Volatile private var home: Network? = null

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val has = lp.linkAddresses.any { it.address == ip }
                if (has && home == null) {
                    home = network
                } else if (!has && network == home) {
                    changed()
                }
            }

            override fun onLost(network: Network) {
                if (network == home) changed()
            }
        }
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        runCatching { getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, callback) }
            .onSuccess { watching = callback }
        handler.removeCallbacks(checkAddress)
        handler.postDelayed(checkAddress, ADDRESS_EVERY)
    }

    private fun unwatch() {
        handler.removeCallbacks(checkAddress)
        watching?.let { w -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(w) } }
        watching = null
    }

    private val checkAddress = object : Runnable {
        override fun run() {
            val r = NetworkShare.running.value ?: return
            if (!NetworkShare.stillHas(r.ip)) return endBecause(NETWORK_CHANGED)
            handler.postDelayed(this, ADDRESS_EVERY)
        }
    }

    private val check = object : Runnable {
        override fun run() {
            val session = NetworkShare.running.value?.session ?: return
            val now = System.currentTimeMillis()
            if (now - maxOf(session.lastActivity, lastReminder) >= IDLE) {
                lastReminder = now
                val minutes = (now - session.lastActivity) / 60_000
                notify(REMINDER_ID, reminder("Network Share is still on", "Nobody has used it for $minutes minutes."))
            }
            handler.postDelayed(this, CHECK_EVERY)
        }
    }

    private var showedProgress = false
    private val progress = object : Runnable {
        override fun run() {
            val r = NetworkShare.running.value ?: return
            val moving = r.session.moving()
            if (moving != null || showedProgress) notify(ON_ID, onNotification(r.address, moving))
            showedProgress = moving != null
            handler.postDelayed(this, PROGRESS_EVERY)
        }
    }

    private fun keepAwake() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NExplorer:share").apply { acquire() }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NExplorer:share").apply { acquire() }
        }
    }

    private fun notify(id: Int, n: Notification) {
        runCatching { getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ON, "Network Share is on", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_REMINDER, "Network Share reminders", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_ASK, "Network Share: files to approve", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun openApp() = openApp(this)

    private fun shutdownAction() = NotificationCompat.Action(
        0, "Shutdown",
        PendingIntent.getService(
            this, 1, Intent(this, ShareService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    )

    private fun onNotification(address: String, moving: ShareSession.Moving? = null) = NotificationCompat.Builder(this, CH_ON)
        .setSmallIcon(R.drawable.ic_share_notify)
        .setContentTitle("Network Share is on")
        .setContentText(moving?.let(::movingText) ?: "Open http://$address on the other device")
        .apply {
            if (moving != null) {
                val part = if (moving.total > 0) (moving.done * 1000 / moving.total).toInt().coerceIn(0, 1000) else 0
                setProgress(1000, part, moving.total <= 0)
            }
        }
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setContentIntent(openApp())
        .addAction(shutdownAction())
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    private fun movingText(m: ShareSession.Moving): String {
        fun files(n: Int) = if (n == 1) "1 file" else "$n files"
        val what = listOfNotNull(
            if (m.sending > 0) "Sending ${files(m.sending)}" else null,
            if (m.receiving > 0) "Receiving ${files(m.receiving)}" else null,
        ).joinToString(", ")
        return "$what · ${formatSize(m.done)} of ${formatSize(m.total)}"
    }

    private fun reminder(title: String, text: String, alertOnce: Boolean = false) = NotificationCompat.Builder(this, CH_REMINDER)
        .setSmallIcon(R.drawable.ic_share_notify)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(openApp())
        .setAutoCancel(true)
        .setOnlyAlertOnce(alertOnce)
        .apply { if (NetworkShare.running.value != null) addAction(shutdownAction()) }
        .build()

    companion object {
        private const val STOP = "nexplorer.share.STOP"
        private const val CH_ON = "share_on"
        private const val CH_REMINDER = "share_reminder"
        private const val ON_ID = 41
        private const val REMINDER_ID = 42
        private const val ASK_ID = 43
        private const val ENDED_ID = 44
        private const val BECAUSE = "because"
        private const val ADDRESS_EVERY = 5_000L
        private const val NETWORK_CHANGED = "This phone's Wi-Fi or hotspot changed."
        private const val CH_ASK = "share_ask"
        private const val CHECK_EVERY = 60_000L
        private const val PROGRESS_EVERY = 1_000L
        private const val IDLE = 15 * 60_000L

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, ShareService::class.java))

        private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.OPEN_SHARE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun ask(ctx: Context, text: String) {
            val n = NotificationCompat.Builder(ctx, CH_ASK)
                .setSmallIcon(R.drawable.ic_share_notify)
                .setContentTitle("Files to approve")
                .setContentText(text)
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ASK_ID, n) }
        }

        fun answered(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java).cancel(ASK_ID)
        }

        fun stop(ctx: Context, because: String? = null) {
            runCatching { ctx.startService(Intent(ctx, ShareService::class.java).setAction(STOP).putExtra(BECAUSE, because)) }
        }
    }
}
