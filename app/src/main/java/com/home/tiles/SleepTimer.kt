package com.home.tiles

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableLongStateOf

/**
 * Turns the projector off after a set time. The alarm lives in Beam (XGIMI's own timer is only in
 * its Chinese power menu); a minute before, a caption warns, and at the end [Power.off] runs.
 * Turning the projector off earlier cancels it, so it can't fire right after the next power-on.
 */
object SleepTimer {
    /** Uptime-independent wall clock time the projector goes off, or 0. Compose reads it. */
    val endsAt = mutableLongStateOf(0L)

    val options = listOf(15, 30, 45, 60, 90, 120)

    fun init(context: Context) {
        val end = prefs(context).getLong(KEY_END, 0)
        endsAt.longValue = if (end > System.currentTimeMillis()) end else 0
    }

    /** The length last chosen, to tick its chip. */
    fun lastMinutes(context: Context): Int = prefs(context).getInt(KEY_MINUTES, 0)

    fun start(context: Context, minutes: Int) {
        val end = System.currentTimeMillis() + minutes * 60_000L
        prefs(context).edit().putLong(KEY_END, end).putInt(KEY_MINUTES, minutes).apply()
        endsAt.longValue = end
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC, end - WARNING_MS, pending(context, ACTION_WARN))
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC, end, pending(context, ACTION_OFF))
    }

    fun cancel(context: Context) {
        if (endsAt.longValue == 0L && !prefs(context).contains(KEY_END)) return
        prefs(context).edit().remove(KEY_END).apply()
        endsAt.longValue = 0
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(pending(context, ACTION_WARN))
        alarms.cancel(pending(context, ACTION_OFF))
    }

    /** Minutes left, rounded up; 0 when no timer runs. */
    fun minutesLeft(now: Long = System.currentTimeMillis()): Int {
        val end = endsAt.longValue
        return if (end > now) ((end - now + 59_999) / 60_000).toInt() else 0
    }

    internal fun fire(context: Context, action: String) {
        val end = prefs(context).getLong(KEY_END, 0)
        // A stale alarm (the projector slept through it) must not act after power-on.
        if (end == 0L || System.currentTimeMillis() - end > LATE_MS) {
            cancel(context)
            return
        }
        when (action) {
            ACTION_WARN -> PanelOverlay.caption("Таймер сна: проектор выключится через минуту", 10_000)
            ACTION_OFF -> {
                Log.i("SleepTimer", "Time's up, turning the projector off")
                cancel(context)
                Power.off(context)
            }
        }
    }

    private fun pending(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        Intent(context, SleepTimerReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun prefs(context: Context) = context.getSharedPreferences("sleepTimer", Context.MODE_PRIVATE)

    private const val KEY_END = "endsAt"
    private const val KEY_MINUTES = "minutes"
    private const val WARNING_MS = 60_000L
    private const val LATE_MS = 2 * 60_000L
    private const val ACTION_WARN = "com.home.tiles.SLEEP_WARN"
    private const val ACTION_OFF = "com.home.tiles.SLEEP_OFF"
}

class SleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SleepTimer.fire(context, intent.action ?: return)
    }
}

/**
 * Powering off the way the remote does (standby). XGIMI's power menu turns the projector off when
 * it is asked to show while already open, so Beam opens it and asks again once it's up.
 */
object Power {
    fun off(context: Context) {
        val menu = Intent("com.xgimi.action.WINODWSYSTEM").setPackage(SHUTDOWN_PKG)
        val send = { runCatching { context.startService(menu) }.onFailure { Log.w("Power", "power menu unavailable", it) } }
        send()
        Handler(Looper.getMainLooper()).postDelayed({ send() }, 1500)
    }

    private const val SHUTDOWN_PKG = "com.xgimi.shutdown"
}
