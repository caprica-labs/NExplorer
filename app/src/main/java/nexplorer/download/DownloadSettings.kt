package nexplorer.download

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class UserAgent(val label: String) {
    Android("Android stock"), Chrome("Chrome"), Firefox("Firefox"), Opera("Opera"), Safari("Safari");

    fun header(): String = when (this) {
        Android -> System.getProperty("http.agent")?.takeIf { it.isNotBlank() } ?: "Dalvik/2.1.0 (Linux; U; Android)"
        Chrome -> CHROME
        Firefox -> "Mozilla/5.0 (Android 16; Mobile; rv:144.0) Gecko/144.0 Firefox/144.0"
        Opera -> "$CHROME OPR/92.0.0.0"
        Safari -> "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1"
    }

    private companion object {
        const val CHROME = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
    }
}

data class DownloadOptions(
    val preallocate: Boolean = false,
    val simultaneous: Int = 3,
    val threads: Int = 1,
    val minPartMb: Int = 1,
    val userAgent: UserAgent = UserAgent.Chrome,
    val schedule: Boolean = false,
    val startMinute: Int = 2 * 60,
    val stopMinute: Int = 8 * 60,
)

object DownloadSettings {
    private const val FILE = "downloads"
    private val _options = MutableStateFlow(DownloadOptions())
    val options: StateFlow<DownloadOptions> = _options
    private var loaded = false

    fun load(ctx: Context): DownloadOptions {
        if (loaded) return _options.value
        val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val d = DownloadOptions()
        _options.value = DownloadOptions(
            preallocate = p.getBoolean("preallocate", d.preallocate),
            simultaneous = p.getInt("simultaneous", d.simultaneous).coerceIn(1, 5),
            threads = p.getInt("threads", d.threads).coerceIn(1, 16),
            minPartMb = p.getInt("minPartMb", d.minPartMb).coerceIn(1, 64),
            userAgent = runCatching { UserAgent.valueOf(p.getString("userAgent", null) ?: "") }.getOrDefault(d.userAgent),
            schedule = p.getBoolean("schedule", d.schedule),
            startMinute = p.getInt("startMinute", d.startMinute).coerceIn(0, 1439),
            stopMinute = p.getInt("stopMinute", d.stopMinute).coerceIn(0, 1439),
        )
        loaded = true
        return _options.value
    }

    fun update(ctx: Context, change: (DownloadOptions) -> DownloadOptions) {
        val o = change(load(ctx))
        _options.value = o
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("preallocate", o.preallocate)
            .putInt("simultaneous", o.simultaneous)
            .putInt("threads", o.threads)
            .putInt("minPartMb", o.minPartMb)
            .putString("userAgent", o.userAgent.name)
            .putBoolean("schedule", o.schedule)
            .putInt("startMinute", o.startMinute)
            .putInt("stopMinute", o.stopMinute)
            .apply()
        DownloadScheduler.apply(ctx)
    }
}

object DownloadScheduler {
    const val START = "nexplorer.download.SCHEDULE_START"
    const val STOP = "nexplorer.download.SCHEDULE_STOP"

    fun canSchedule(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun apply(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val start = pending(app, START)
        val stop = pending(app, STOP)
        am.cancel(start)
        am.cancel(stop)
        val o = DownloadSettings.load(app)
        if (!o.schedule || !canSchedule(app)) return
        val now = System.currentTimeMillis()
        runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, DownloadPlan.nextAt(now, o.startMinute), start)
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, DownloadPlan.nextAt(now, o.stopMinute), stop)
        }
    }

    private fun pending(ctx: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, action.hashCode(),
        Intent(ctx, DownloadAlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class DownloadAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Downloads.init(ctx)
        when (intent.action) {
            DownloadScheduler.START -> Downloads.start(ctx, Downloads.active())
            DownloadScheduler.STOP -> Downloads.stop(Downloads.active())
        }
        DownloadScheduler.apply(ctx)
    }
}

class DownloadBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = DownloadScheduler.apply(ctx)
}
