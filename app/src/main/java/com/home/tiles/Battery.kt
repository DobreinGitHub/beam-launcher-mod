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
import androidx.compose.ui.graphics.drawscope.DrawScope
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

    /** On XGIMI's battery stand (its power bank), without the adapter: the stand's charge goes first. */
    STAND,
}

/**
 * XGIMI's battery stand as the firmware reports it (its "copower" device type, in the order of the
 * firmware's CopowerDeviceTypeEnum): 0 a plain adapter, 1 the stand powering the projector, 2 the
 * stand too low to, 3 the projector's own battery, 4 the stand with its charger plugged in, which
 * passes the charger's power on. With 0 and 3 there is no stand, whatever its last level says (the
 * firmware keeps the last one after the stand is taken off).
 */
enum class StandState { NONE, POWERING, LOW, CHARGING }

private fun standState(type: Int?): StandState = when (type) {
    1 -> StandState.POWERING
    2 -> StandState.LOW
    4 -> StandState.CHARGING
    else -> StandState.NONE
}

/**
 * [minutesLeft]: on battery or the stand, how long the projector runs at the recent rate (on the
 * stand: the stand's charge, then its own); null off battery. [stand]: the
 * battery stand's state; [standLevel]: its charge, null when it isn't attached or doesn't report it
 * (while it charges); [standCharging]: its own charger is in.
 */
class BatteryInfo(
    val percent: Int,
    val power: PowerSource,
    val minutesLeft: Int?,
    val stand: StandState = StandState.NONE,
    val standLevel: Int? = null,
) {
    val standCharging get() = stand == StandState.CHARGING
}

private class Reading(val level: Int, val adapter: Boolean, val full: Boolean, val stand: StandState, val standLevel: Int?)

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
    // XGIMI's battery stand is its "copower" device, see StandState.
    val stand = standState(runCatching { (cls.getMethod("getCopowerDeviceType").invoke(pm) as Number).toInt() }.getOrNull())
    // While it charges the stand reports 0 ("invalid" in the firmware's log): no level then.
    val standLevel = if (stand == StandState.NONE) null else
        runCatching { (cls.getMethod("getCopowerBatteryLevel").invoke(pm) as Number).toInt() }.getOrNull()
            ?.takeIf { it in 0..100 && !(stand == StandState.CHARGING && it == 0) }
    if (level in 0..100) Reading(level, adapter, full, stand, standLevel) else null
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
    private val samples = ArrayDeque<Pair<Long, Int>>() // elapsed time, level of the charge in use
    private val standSamples = ArrayDeque<Pair<Long, Int>>() // elapsed time, the stand's level
    private var lastSource: Pair<Boolean, StandState>? = null // adapter, stand
    private var trend = 0
    private var standTrend = 0
    private val warned = mutableSetOf<Int>()
    private val standWarned = mutableSetOf<Int>()
    private var hadStand: Boolean? = null
    // The level in use at the last sample: a drop of a percent is when a drain rate is learned.
    private var lastLevel: Int? = null

    @Synchronized
    fun start(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        executor.scheduleWithFixedDelay({ runCatching { sample() } }, 0, POLL_S, TimeUnit.SECONDS)
    }

    /**
     * How long a percent lasts, remembered for the projector's own battery and for the stand, and
     * for each with Eco on and off (the firmware turns Eco on by itself on the stand, and it
     * changes the drain): the time left shows as soon as the projector runs on either, and the
     * live reading refines it. The other Eco state's rate stands in while one isn't known yet.
     */
    private fun remembered(source: String, eco: Boolean): Long? {
        val prefs = appContext?.let(::prefs) ?: return null
        val rate = prefs.getLong("$KEY_RATE$source:$eco", 0L).takeIf { it > 0 }
            ?: prefs.getLong("$KEY_RATE$source:${!eco}", 0L).takeIf { it > 0 }
            // The Play 6's battery and the Power Base Stand are both 20,000 mAh: a percent of one
            // lasts about as long as a percent of the other, so each stands in for the other.
            ?: prefs.getLong("$KEY_RATE${other(source)}:$eco", 0L).takeIf { it > 0 }
            ?: prefs.getLong("$KEY_RATE${other(source)}:${!eco}", 0L).takeIf { it > 0 }
            ?: STAND_SEED_MS
        return plausibleRate(rate)
    }

    /** The remembered rates, for the adb hook (--ez battery_get true). */
    fun describeRates(): String {
        val prefs = appContext?.let(::prefs) ?: return "not started"
        return listOf(OWN, STAND).flatMap { s -> listOf(true, false).map { e -> "$s eco=$e: ${prefs.getLong("$KEY_RATE$s:$e", 0L) / 1000}s/%" } }
            .joinToString("; ") + " | now ${info?.minutesLeft} min"
    }

    private fun other(source: String) = if (source == OWN) STAND else OWN

    private fun learn(source: String, eco: Boolean, measured: Long) {
        val prefs = appContext?.let(::prefs) ?: return
        val key = "$KEY_RATE$source:$eco"
        val old = prefs.getLong(key, 0L).takeIf { it > 0 }
        prefs.edit().putLong(key, learnRate(old, plausibleRate(measured))).apply()
    }

    private fun sample() {
        val reading = readXgimiBattery() ?: return
        val now = SystemClock.elapsedRealtime()
        // The stand carries the projector (the adapter flag is set then too, so it goes first).
        val onStand = reading.stand == StandState.POWERING || reading.stand == StandState.LOW
        // The charge in use: the stand's while it carries the projector, else the projector's own.
        val level = if (onStand) reading.standLevel ?: reading.level else reading.level
        val source = reading.adapter to reading.stand
        if (source != lastSource) {
            samples.clear()
            standSamples.clear()
            trend = 0
            standTrend = 0
            lastSource = source
        }
        fun ArrayDeque<Pair<Long, Int>>.add(value: Int): Pair<Long, Int> {
            addLast(now to value)
            while (size > 1 && now - first().first > WINDOW_MS) removeFirst()
            val (since, then) = first()
            return (now - since) to (value - then)
        }
        val (span, change) = samples.add(level)
        if (span >= MIN_TREND_MS && change != 0) trend = if (change > 0) 1 else -1
        reading.standLevel?.let { stand ->
            val (standSpan, standChange) = standSamples.add(stand)
            if (standSpan >= MIN_TREND_MS && standChange != 0) standTrend = if (standChange > 0) 1 else -1
        }
        val power = when {
            onStand -> PowerSource.STAND
            !reading.adapter -> PowerSource.BATTERY
            reading.full || reading.level >= 100 -> PowerSource.FULL
            trend < 0 -> PowerSource.PLUGGED_DRAINING
            else -> PowerSource.CHARGING
        }
        // Two percents at least, so one step of the level doesn't make a wild guess.
        val live = if (span >= MIN_TREND_MS && change <= -2) span / -change else null
        val drainSource = when (power) {
            PowerSource.BATTERY -> OWN
            PowerSource.STAND -> STAND
            else -> null
        }
        val eco = runCatching { Eco.enabled() }.getOrNull() == true
        // Each percent the charge in use drops, what the live reading says is learned.
        if (drainSource != null && live != null && lastLevel != null && level < lastLevel!!) learn(drainSource, eco, live)
        lastLevel = level
        val rate = drainSource?.let { blendRate(remembered(it, eco), live, span) }
        val minutesLeft = when {
            rate == null -> null
            power == PowerSource.BATTERY -> (level * rate / 60_000).toInt()
            // The stand's charge, then the projector's own (it stays full while the stand carries it),
            // both at the drain of the present use: the two hold the same 20,000 mAh, and what is playing
            // now goes on playing once the stand runs out.
            else -> ((level + reading.level) * rate / 60_000).toInt()
        }
        state.value = BatteryInfo(reading.level, power, minutesLeft, reading.stand, reading.standLevel)
        noteStand(reading)
        warnIfLow(reading)
    }

    /** A short notice when the stand is attached or taken off (not for how things are at start). */
    private fun noteStand(reading: Reading) {
        val has = reading.stand != StandState.NONE
        val before = hadStand
        hadStand = has
        if (before == null || before == has) return
        say(
            when {
                !has -> tr(R.string.stand_off)
                reading.standLevel != null -> tr(R.string.stand_on, reading.standLevel)
                else -> tr(R.string.stand_on_plain)
            },
        )
    }

    private fun say(text: String) {
        if (PanelOverlay.running) {
            PanelOverlay.caption(text, hideAfterMs = 6_000)
        } else {
            val context = appContext ?: return
            Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
        }
    }

    /**
     * Once per threshold while on battery, or on the stand for the stand's charge; the adapter
     * resets them.
     */
    private fun warnIfLow(reading: Reading) {
        val stand = reading.standLevel
        if (reading.stand == StandState.POWERING || reading.stand == StandState.LOW) {
            if (stand == null) return
            val threshold = WARN_AT.filter { stand <= it }.minOrNull() ?: return
            if (threshold in standWarned) return
            standWarned += WARN_AT.filter { it >= threshold }
            say(tr(R.string.stand_low, stand))
            return
        }
        if (reading.adapter) {
            warned.clear()
            // The stand's warnings come again once it has been charged.
            if (reading.stand == StandState.CHARGING) standWarned.clear()
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
 * plug. On battery: red at 15% and below (the time left is in Beam's settings header, see batteryTimeLeft).
 * [weight]: of the percentages; Regular beside the top bar's tabs, Light in a large header.
 */
@Composable
fun BatteryIndicator(
    textSize: TextUnit = StatusTextSize,
    color: Color = Colors.Text,
    iconScale: Float = 1f,
    weight: FontWeight = FontWeight.Normal,
) {
    BatteryMonitor.start(LocalContext.current)
    val info = BatteryMonitor.info ?: return
    val low = info.power == PowerSource.BATTERY && info.percent <= 15
    val tint = when (info.power) {
        PowerSource.CHARGING, PowerSource.FULL -> ChargingGreen
        PowerSource.BATTERY -> if (low) LowRed else color
        PowerSource.PLUGGED_DRAINING, PowerSource.STAND -> color
    }
    val badge = when (info.power) {
        PowerSource.CHARGING -> Icons.Rounded.Bolt to ChargingGreen
        PowerSource.FULL -> Icons.Rounded.Power to ChargingGreen
        PowerSource.PLUGGED_DRAINING -> Icons.Rounded.Power to DrainAmber
        PowerSource.BATTERY, PowerSource.STAND -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // XGIMI's battery stand, while attached: its own charge first (on it, it's the one in use);
        // green with a bolt while its charger is in (it doesn't report its level then), red when
        // it's too low to carry the projector or at 15% and below. Then the projector's battery.
        if (info.stand != StandState.NONE) {
            val level = info.standLevel
            val standTint = when {
                info.standCharging -> ChargingGreen
                info.stand == StandState.LOW || (level != null && level <= 15) -> LowRed
                else -> color
            }
            if (info.standCharging) {
                Image(Icons.Rounded.Bolt, null, Modifier.size(26.dp * iconScale), colorFilter = ColorFilter.tint(ChargingGreen))
                Spacer(Modifier.width(2.dp))
            }
            StandGlyph(level ?: 0, standTint, iconScale)
            level?.let {
                Spacer(Modifier.width(8.dp * iconScale))
                T("$it%", textSize, color = standTint, weight = weight, tabular = true)
            }
            Spacer(Modifier.width(18.dp * iconScale))
        }
        badge?.let { (icon, badgeTint) ->
            Image(icon, null, Modifier.size(26.dp * iconScale), colorFilter = ColorFilter.tint(badgeTint))
            Spacer(Modifier.width(2.dp))
        }
        BatteryGlyph(info.percent, tint, iconScale)
        Spacer(Modifier.width(8.dp * iconScale))
        T("${info.percent}%", textSize, color = if (low) tint else color, weight = weight, tabular = true)
    }
}

/** Horizontal battery like the Switch's: outline and a fill proportional to the charge. */
@Composable
private fun BatteryGlyph(percent: Int, tint: Color, scale: Float = 1f) {
    // Same height as the Wi-Fi glyph's visible part, so the two icons line up.
    Canvas(Modifier.size(width = 40.dp * scale, height = 20.dp * scale)) {
        drawBattery(percent, tint, scale, Offset.Zero, size)
    }
}

/**
 * XGIMI's battery stand: a vertical battery (its pole is the battery) on two feet (its tripod),
 * filling from the bottom with its charge. Upright, so it reads apart from the projector's
 * battery beside it; one shape, so it stays clear at status size. Centred on the same line as
 * [BatteryGlyph].
 */
@Composable
private fun StandGlyph(percent: Int, tint: Color, scale: Float = 1f) {
    Canvas(Modifier.size(width = 18.dp * scale, height = 26.dp * scale)) {
        val stroke = 2.4.dp.toPx() * scale.coerceAtLeast(0.7f)
        val cx = size.width / 2
        // Body: an upright battery outline, its nub on top.
        val bodyW = size.width * 0.72f
        val nubH = size.height * 0.09f
        val bodyTop = nubH
        val bodyBottom = size.height * 0.78f
        val nubW = bodyW * 0.45f
        drawRoundRect(tint, Offset(cx - nubW / 2, 0f), Size(nubW, nubH + stroke / 2), CornerRadius(1.dp.toPx()))
        val body = Size(bodyW - stroke, bodyBottom - bodyTop - stroke)
        val bodyAt = Offset(cx - bodyW / 2 + stroke / 2, bodyTop + stroke / 2)
        drawRoundRect(tint, bodyAt, body, CornerRadius(3.dp.toPx() * scale.coerceAtLeast(0.7f)), style = Stroke(stroke))
        // Fill from the bottom with the charge.
        val inset = stroke + 1.5.dp.toPx() * scale.coerceAtLeast(0.7f)
        val inner = body.height - inset * 2 + stroke
        val fill = inner * (percent.coerceIn(0, 100) / 100f)
        if (fill > 0f) {
            drawRoundRect(
                tint,
                Offset(bodyAt.x + inset - stroke / 2, bodyAt.y + inset - stroke / 2 + (inner - fill)),
                Size(body.width - inset * 2 + stroke, fill),
                CornerRadius(1.5.dp.toPx()),
            )
        }
        // Two feet, out from under the battery.
        val foot = Offset(cx, bodyBottom)
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        drawLine(tint, foot, Offset(stroke * 0.9f, size.height - stroke / 2), stroke, cap)
        drawLine(tint, foot, Offset(size.width - stroke * 0.9f, size.height - stroke / 2), stroke, cap)
    }
}

/** A battery outline with its nub and a fill for [percent], in the box at [at] of [box]. */
private fun DrawScope.drawBattery(percent: Int, tint: Color, scale: Float, at: Offset, box: Size) {
    val stroke = 2.5.dp.toPx() * scale.coerceAtLeast(0.7f)
    val nub = 3.dp.toPx() * scale
    val body = Size(box.width - nub - stroke, box.height - stroke)
    val corner = CornerRadius(4.dp.toPx() * scale.coerceAtLeast(0.7f))
    drawRoundRect(tint, at + Offset(stroke / 2, stroke / 2), body, corner, style = Stroke(stroke))
    drawRoundRect(
        tint,
        at + Offset(box.width - nub, box.height * 0.32f),
        Size(nub, box.height * 0.36f),
        CornerRadius(1.5.dp.toPx()),
    )
    val inset = stroke + 2.dp.toPx() * scale.coerceAtLeast(0.7f)
    val fillWidth = (body.width - inset * 2 + stroke) * (percent.coerceIn(0, 100) / 100f)
    if (fillWidth > 0f) {
        drawRoundRect(tint, at + Offset(inset, inset), Size(fillWidth, box.height - inset * 2), CornerRadius(2.dp.toPx()))
    }
}
/**
 * The time the charge in use lasts, "≈ 1 ч 20 мин", once it can be told; null otherwise. Shown in
 * Beam's settings header, not the top bar: there it would crowd the devices and the clock.
 */
@Composable
fun batteryTimeLeft(): String? {
    BatteryMonitor.start(LocalContext.current)
    return BatteryMonitor.info?.minutesLeft?.let(::timeLeft)
}

private const val KEY_RATE = "msPerPercent:"
private const val OWN = "own"
private const val STAND = "stand"
// Until either battery has been timed: measured on this projector on the stand, 33% in 62 minutes.
private const val STAND_SEED_MS = 112_000L
// The live reading takes over from the remembered rate over this long.
private const val BLEND_MS = 15 * 60_000L

/**
 * The rate to estimate with: the remembered one at first, the live one ([liveSpanMs] of readings
 * behind it) taking over gradually, so the time left settles rather than jumps.
 */
internal fun blendRate(remembered: Long?, live: Long?, liveSpanMs: Long): Long? {
    if (live == null) return remembered
    if (remembered == null) return live
    val w = (liveSpanMs.toDouble() / BLEND_MS).coerceIn(0.0, 1.0)
    return (remembered * (1 - w) + live * w).toLong()
}

/**
 * A rate kept within what this projector can do: the Play 6 runs about 2.5 h on its 20,000 mAh
 * for video at full brightness (1.5 min a percent), longer when dimmer or Eco; a reading outside
 * 50 s to 4 min a percent (about 1.4 to 6.7 h for a full charge) is a mismeasure, such as one
 * taken while the charger was in for part of the time.
 */
internal fun plausibleRate(rate: Long): Long = rate.coerceIn(50_000L, 240_000L)

/** A remembered rate moved a third of the way to a new measurement, so one odd session doesn't wipe it. */
internal fun learnRate(old: Long?, measured: Long): Long = if (old == null) measured else (old * 2 + measured) / 3

private fun prefs(context: Context) = context.getSharedPreferences("battery", Context.MODE_PRIVATE)
