package nexplorer.jobs

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

class FileJobService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Copying, moving and deleting files", NotificationManager.IMPORTANCE_LOW))
        runCatching {
            ServiceCompat.startForeground(
                this, ID, notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }
        if (intent?.action == CANCEL) FileJobs.cancelCurrent()
        handler.removeCallbacks(update)
        handler.post(update)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) = end()

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
            if (FileJobs.active.value == 0) return end()
            runCatching { getSystemService(NotificationManager::class.java).notify(ID, notification()) }
            handler.postDelayed(this, EVERY)
        }
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL).apply {
        val r = FileJobs.current
        val waiting = FileJobs.active.value - (if (r != null) 1 else 0)
        setSmallIcon(R.drawable.ic_files_notify)
        setContentTitle(r?.title ?: "Getting ready")
        val amount = when {
            r == null || r.total <= 0 -> null
            r.bytes -> "${formatSize(r.done)} of ${formatSize(r.total)}"
            else -> "${r.done} of ${r.total} files"
        }
        val more = if (waiting > 0) "$waiting more waiting" else null
        setContentText(listOfNotNull(amount, r?.detail?.takeIf { it.isNotEmpty() }, more).joinToString(" · "))
        if (r != null && r.total > 0) {
            setProgress(1000, (r.done * 1000 / r.total).toInt().coerceIn(0, 1000), false)
        } else {
            setProgress(0, 0, true)
        }
        setOngoing(true)
        setOnlyAlertOnce(true)
        setSilent(true)
        setContentIntent(
            PendingIntent.getActivity(
                this@FileJobService, 0,
                Intent(this@FileJobService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        if (r != null) {
            addAction(
                0, "Cancel",
                PendingIntent.getService(
                    this@FileJobService, 1,
                    Intent(this@FileJobService, FileJobService::class.java).setAction(CANCEL),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }.build()

    companion object {
        private const val CHANNEL = "file_jobs"
        private const val ID = 51
        private const val CANCEL = "nexplorer.jobs.CANCEL"
        private const val EVERY = 500L

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, FileJobService::class.java)) }
        }
    }
}
