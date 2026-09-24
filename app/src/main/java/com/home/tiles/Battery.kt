package com.home.tiles

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class BatteryInfo(val percent: Int, val charging: Boolean)

/** Null on devices without a battery (e.g. mains-only projectors). */
private fun Intent.toBatteryInfo(): BatteryInfo? {
    if (!getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) return null
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    if (level < 0 || scale <= 0) return null
    val status = getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return BatteryInfo(level * 100 / scale, charging)
}

/**
 * The XGIMI projector's Android battery service is a stub ("not present", always 100%); the real
 * pack is behind com.xgimi.gmpf.api.PowerManager in the com.xgimi.api platform library.
 * Reflection, because that library only exists on XGIMI firmware. Null if unavailable.
 */
private fun readXgimiBattery(): BatteryInfo? = runCatching {
    val cls = Class.forName("com.xgimi.gmpf.api.PowerManager")
    val pm = cls.getMethod("getInstance").invoke(null)
    val level = (cls.getMethod("getBatteryLevel").invoke(pm) as Number).toInt()
    val adapter = cls.getMethod("isAdapterPowered").invoke(pm) as? Boolean ?: false
    if (level in 0..100) BatteryInfo(level, charging = adapter) else null
}.onFailure { android.util.Log.w("Battery", "XGIMI battery unavailable", it) }.getOrNull()

@Composable
private fun rememberBattery(): State<BatteryInfo?> {
    val context = LocalContext.current.applicationContext
    if (Device.isTv) {
        // No change broadcasts from the XGIMI side, so poll; a battery drains slowly.
        return produceState(readXgimiBattery()) {
            while (true) {
                delay(60_000)
                value = withContext(Dispatchers.IO) { readXgimiBattery() }
            }
        }
    }
    return produceState<BatteryInfo?>(null, context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { value = intent.toBatteryInfo() }
        }
        // Sticky broadcast: registering returns the current state right away.
        val sticky = ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        value = sticky?.toBatteryInfo()
        awaitDispose { context.unregisterReceiver(receiver) }
    }
}

/** Switch-style battery readout for the top bar; hidden when the device has no battery. */
@Composable
fun BatteryIndicator(textSize: TextUnit = StatusTextSize, color: Color = Colors.Text) {
    val battery by rememberBattery()
    val info = battery ?: return
    val low = info.percent <= 15 && !info.charging
    val tint = if (low) Color(0xFFE53935) else color
    Row(verticalAlignment = Alignment.CenterVertically) {
        BatteryGlyph(info, tint)
        Spacer(Modifier.width(8.dp))
        T("${info.percent}%", textSize, color = tint, weight = FontWeight.Light)
    }
}

/** Horizontal battery like the Switch's: outline, fill proportional to charge, bolt when charging. */
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
        if (info.charging) {
            // Knocked out against the fill so it stays visible at any charge level.
            Image(
                Icons.Rounded.Bolt,
                null,
                Modifier.size(16.dp),
                colorFilter = ColorFilter.tint(if (info.percent > 45) Colors.Background else tint),
            )
        }
    }
}
