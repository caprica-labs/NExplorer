package nexplorer.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import nexplorer.MainActivity
import nexplorer.R
import nexplorer.ui.home.formatSize

class DownloadService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        runCatching {
            ServiceCompat.startForeground(
                this, ID, notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }
        if (intent?.action == PAUSE) Downloads.stop(Downloads.active())
        handler.removeCallbacks(update)
        handler.post(update)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Downloads.stop(Downloads.active())
        end()
    }

    override fun onDestroy() {
        handler.removeCallbacks(update)
        super.onDestroy()
    }

    private fun end() {
        handler.removeCallbacks(update)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val update = object : Runnable {
        override fun run() {
            if (Downloads.busy == 0) return end()
            runCatching { getSystemService(NotificationManager::class.java).notify(ID, notification()) }
            handler.postDelayed(this, EVERY)
        }
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL).apply {
        val running = Downloads.active().filter { it.state == DownloadState.Running }
        setSmallIcon(R.drawable.ic_files_notify)
        setContentTitle(running.singleOrNull()?.name ?: if (running.isEmpty()) "Downloads" else "Downloading ${running.size} files")
        val done = running.sumOf { it.done }
        val known = running.isNotEmpty() && running.all { it.total > 0 }
        val total = running.sumOf { it.total }
        val speed = running.sumOf { it.speed }
        setContentText(
            listOfNotNull(
                if (known) "${formatSize(done)} of ${formatSize(total)}" else formatSize(done),
                speed.takeIf { it > 0 }?.let { "${formatSize(it)}/s" },
            ).joinToString(" · "),
        )
        if (known) setProgress(1000, (done * 1000 / total).toInt().coerceIn(0, 1000), false) else setProgress(0, 0, true)
        setOngoing(true)
        setOnlyAlertOnce(true)
        setSilent(true)
        setContentIntent(
            PendingIntent.getActivity(
                this@DownloadService, 0,
                Intent(this@DownloadService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        addAction(
            0, "Pause",
            PendingIntent.getService(
                this@DownloadService, 1,
                Intent(this@DownloadService, DownloadService::class.java).setAction(PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }.build()

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 52
        private const val PAUSE = "nexplorer.download.PAUSE"
        private const val EVERY = 1000L

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java)) }
        }
    }
}
