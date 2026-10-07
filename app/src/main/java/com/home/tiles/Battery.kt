package com.home.tiles

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Power
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Where the projector's power comes from, and what the battery is doing about it. */
enum class PowerSource {
    BATTERY,

    /** On the adapter and the charge is going up (or hasn't had time to show either way). */
    CHARGING,

    /** On the adapter, but the charge still goes down: the projector draws more than it gets. */
    PLUGGED_DRAINING,

    /** On the adapter with a full battery. */
    FULL,
}

/** [minutesLeft]: on battery, how long the charge lasts at the recent rate; null until known. */
class BatteryInfo(val percent: Int, val power: PowerSource, val minutesLeft: Int?)

private class Reading(val level: Int, val adapter: Boolean, val full: Boolean)

/**
 * The XGIMI projector's Android battery service is a stub ("not present", always 100%); the real
 * pack is behind com.xgimi.gmpf.api.PowerManager in the com.xgimi.api platform library.
 * Reflection, because that library only exists on XGIMI firmware. Null if unavailable.
 * The adapter flag says only that it is plugged in, not that the battery is filling up.
 */
private fun readXgimiBattery(): Reading? = runCatching {
    val cls = Class.forName("com.xgimi.gmpf.api.PowerManager")
    val pm = cls.getMethod("getInstance").invoke(null)
    val level = (cls.getMethod("getBatteryLevel").invoke(pm) as Number).toInt()
    val adapter = cls.getMethod("isAdapterPowered").invoke(pm) as? Boolean ?: false
    val full = runCatching { cls.getMethod("isBatteryChargedFull").invoke(pm) as? Boolean }.getOrNull() ?: false
    if (level in 0..100) Reading(level, adapter, full) else null
}.onFailure { android.util.Log.w("Battery", "XGIMI battery unavailable", it) }.getOrNull()

/**
 * Polls the battery for the whole process: the top bar shows [info], and the low-battery warning
 * must come over whatever app is playing, so it runs from the accessibility service too.
 * The level moves by whole percents, so whether it charges or drains, and how fast, is read from
 * its change over the last [WINDOW_MS]; plugging the adapter in or out starts that over.
 */
object BatteryMonitor {
    private const val POLL_S = 5L
    private const val WINDOW_MS = 20 * 60_000L
    private const val MIN_TREND_MS = 3 * 60_000L
    private val WARN_AT = listOf(15, 5)

    private val state = mutableStateOf<BatteryInfo?>(null)
    val info: BatteryInfo? get() = state.value

    private var appContext: Context? = null
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val samples = ArrayDeque<Pair<Long, Int>>() // elapsed time, level
    private var lastAdapter: Boolean? = null
    private var trend = 0
    private val warned = mutableSetOf<Int>()

    @Synchronized
    fun start(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        executor.scheduleWithFixedDelay({ runCatching { sample() } }, 0, POLL_S, TimeUnit.SECONDS)
    }

    private fun sample() {
        val reading = readXgimiBattery() ?: return
        val now = SystemClock.elapsedRealtime()
        if (reading.adapter != lastAdapter) {
            samples.clear()
            trend = 0
            lastAdapter = reading.adapter
        }
        samples.addLast(now to reading.level)
        while (samples.size > 1 && now - samples.first().first > WINDOW_MS) samples.removeFirst()
        val (since, then) = samples.first()
        val span = now - since
        val change = reading.level - then
        if (span >= MIN_TREND_MS && change != 0) trend = if (change > 0) 1 else -1
        val power = when {
            !reading.adapter -> PowerSource.BATTERY
            reading.full || reading.level >= 100 -> PowerSource.FULL
            trend < 0 -> PowerSource.PLUGGED_DRAINING
            else -> PowerSource.CHARGING
        }
        // Two percents at least, so one step of the level doesn't make a wild guess.
        val minutesLeft = if (power == PowerSource.BATTERY && span >= MIN_TREND_MS && change <= -2) {
            (reading.level * span / -change / 60_000).toInt()
        } else null
        state.value = BatteryInfo(reading.level, power, minutesLeft)
        warnIfLow(reading)
    }

    /** Once per threshold while on battery; the adapter resets it. */
    private fun warnIfLow(reading: Reading) {
        if (reading.adapter) {
            warned.clear()
            return
        }
        val threshold = WARN_AT.filter { reading.level <= it }.minOrNull() ?: return
        if (threshold in warned) return
        // Starting below 5% counts as warned for 15% too.
        warned += WARN_AT.filter { it >= threshold }
        val text = tr(R.string.battery_low, reading.level)
        if (PanelOverlay.running) {
            PanelOverlay.caption(text, hideAfterMs = 10_000)
        } else {
            val context = appContext ?: return
            Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
        }
    }
}

private val ChargingGreen = Color(0xFF2EB85C)
private val DrainAmber = Color(0xFFF5A623)
private val LowRed = Color(0xFFE53935)

/** "≈ 1 h 20 min" for the time left on battery. */
private fun timeLeft(minutes: Int): String =
    if (minutes >= 60) tr(R.string.battery_left_hm, minutes / 60, minutes % 60) else tr(R.string.battery_left_m, minutes)

/**
 * Switch-style battery readout for the top bar; hidden when the device has no battery.
 * Charging: green battery with a bolt. On the adapter but draining: an amber plug. Full: a green
 * plug. On battery: the time left once it can be told, and red at 15% and below.
 */
@Composable
fun BatteryIndicator(textSize: TextUnit = StatusTextSize, color: Color = Colors.Text) {
    BatteryMonitor.start(LocalContext.current)
    val info = BatteryMonitor.info ?: return
    val low = info.power == PowerSource.BATTERY && info.percent <= 15
    val tint = when (info.power) {
        PowerSource.CHARGING, PowerSource.FULL -> ChargingGreen
        PowerSource.BATTERY -> if (low) LowRed else color
        PowerSource.PLUGGED_DRAINING -> color
    }
    val badge = when (info.power) {
        PowerSource.CHARGING -> Icons.Rounded.Bolt to ChargingGreen
        PowerSource.FULL -> Icons.Rounded.Power to ChargingGreen
        PowerSource.PLUGGED_DRAINING -> Icons.Rounded.Power to DrainAmber
        PowerSource.BATTERY -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        badge?.let { (icon, badgeTint) ->
            Image(icon, null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(badgeTint))
            Spacer(Modifier.width(2.dp))
        }
        BatteryGlyph(info, tint)
        Spacer(Modifier.width(8.dp))
        T("${info.percent}%", textSize, color = if (low) tint else color, weight = FontWeight.Light)
        info.minutesLeft?.let {
            Spacer(Modifier.width(10.dp))
            T(timeLeft(it), textSize * 0.6f, color = Colors.TextDim, weight = FontWeight.Light)
        }
    }
}

/** Horizontal battery like the Switch's: outline and a fill proportional to the charge. */
@Composable
private fun BatteryGlyph(info: BatteryInfo, tint: Color) {
    // Same height as the Wi-Fi glyph's visible part, so the two icons line up.
    Box(Modifier.size(width = 40.dp, height = 20.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = 2.5.dp.toPx()
            val nub = 3.dp.toPx()
            val body = Size(size.width - nub - stroke, size.height - stroke)
            val corner = CornerRadius(4.dp.toPx())
            drawRoundRect(tint, Offset(stroke / 2, stroke / 2), body, corner, style = Stroke(stroke))
            drawRoundRect(
                tint,
                Offset(size.width - nub, size.height * 0.32f),
                Size(nub, size.height * 0.36f),
                CornerRadius(1.5.dp.toPx()),
            )
            val inset = stroke + 2.dp.toPx()
            val fillWidth = (body.width - inset * 2 + stroke) * (info.percent.coerceIn(0, 100) / 100f)
            if (fillWidth > 0f) {
                drawRoundRect(
                    tint,
                    Offset(inset, inset),
                    Size(fillWidth, size.height - inset * 2),
                    CornerRadius(2.dp.toPx()),
                )
            }
        }
    }
}
