package com.home.tiles

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** XGIMI's sensor switches: keystone when moved, refocus on tilt, eye protection. */
@Composable
internal fun SensorToggles() {
    val realtime = rememberFirmwareState { Sensors.realtimeKeystone() }
    val motionFocus = rememberFirmwareState { Sensors.motionFocus() }
    val eyes = rememberFirmwareState { Sensors.eyeProtection() }
    val bootKeystone = rememberFirmwareState { Sensors.bootKeystone() }
    // Nothing is shown until the firmware has answered, and nothing if it has none of these.
    if (realtime.value == null && motionFocus.value == null && eyes.value == null && bootKeystone.value == null) return
    Section(tr(R.string.sensors))
    bootKeystone.value?.let { on ->
        Toggle(tr(R.string.kst_on_boot), on, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            bootKeystone.change(!on) { Sensors.setBootKeystone(!on) }
        }
    }
    realtime.value?.let { on ->
        Toggle(tr(R.string.kst_on_move), on, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            realtime.change(!on) { Sensors.setRealtimeKeystone(!on) }
        }
    }
    motionFocus.value?.let { on ->
        Toggle(tr(R.string.af_on_move), on, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            motionFocus.change(!on) { Sensors.setMotionFocus(!on) }
        }
    }
    eyes.value?.let { on ->
        Toggle(tr(R.string.eye_protection), on, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            eyes.change(!on) { Sensors.setEyeProtection(!on) }
        }
    }
}

/** Model, firmware and the like, read once when the page opens. */
@Composable
internal fun AboutSection() {
    val context = LocalContext.current
    val rows by produceState(emptyList<Pair<String, String>>()) {
        value = withContext(Dispatchers.IO) { aboutRows(context) }
    }
    if (rows.isEmpty()) return
    Section(tr(R.string.about_projector))
    Column(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 12.dp)) {
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                T(label, 15.sp, color = PanelDim)
                Spacer(Modifier.weight(1f))
                T(value, 15.sp, color = PanelText)
            }
        }
    }
}

private fun aboutRows(context: Context): List<Pair<String, String>> {
    fun prop(name: String) = XgimiCommon.property(name).takeIf { it.isNotBlank() }
    val memory = android.app.ActivityManager.MemoryInfo().also {
        context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)
    }
    val storage = android.os.StatFs(android.os.Environment.getDataDirectory().path)
    val uptime = android.os.SystemClock.elapsedRealtime() / 60_000
    val ip = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress
    }.getOrNull()
    val beam = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    fun gb(bytes: Long) = String.format(Locale.US, "%.1f", bytes / 1e9)
    return listOfNotNull(
        prop("ro.boot.xgimi.modelname")?.let { tr(R.string.model) to "XGIMI · $it" },
        prop("ro.build.version.incremental")?.let { tr(R.string.firmware) to it },
        "Android" to android.os.Build.VERSION.RELEASE,
        prop("ro.boot.serialno")?.let { tr(R.string.serial) to it },
        ip?.let { tr(R.string.ip_address) to it },
        tr(R.string.uptime) to if (uptime >= 60) tr(R.string.hours_minutes, uptime / 60, uptime % 60) else tr(R.string.minutes_short, uptime),
        tr(R.string.free_memory) to tr(R.string.memory_of, memory.availMem / 1_048_576, memory.totalMem / 1_048_576),
        tr(R.string.free_storage) to tr(R.string.storage_of, gb(storage.availableBytes), gb(storage.totalBytes)),
        beam?.let { "Beam" to it },
    )
}

/** Table, ceiling or automatic mounting, rear projection, and a fine tilt of the picture. */
@Composable
internal fun ProjectionPage(onRotatePage: () -> Unit) {
    val mount = rememberFirmwareState { Projection.mount() }
    val rear = rememberFirmwareState { Projection.rear() }
    mount.value?.let { current ->
        Section(tr(R.string.installation))
        listOf(Projection.AUTO to tr(R.string.auto), Projection.TABLE to tr(R.string.on_table), Projection.CEILING to tr(R.string.on_ceiling)).forEach { (value, label) ->
            Chip(label, current == value, Modifier.fillMaxWidth().padding(bottom = 8.dp), note = if (value == Projection.AUTO) tr(R.string.by_position_sensor) else null) {
                mount.change(value) { Projection.setMount(value) }
            }
        }
    }
    rear.value?.let { on ->
        Spacer(Modifier.height(2.dp))
        Toggle(tr(R.string.rear_projection), on, Modifier.fillMaxWidth()) {
            rear.change(!on) { Projection.setRear(!on) }
        }
        T(tr(R.string.rear_projection_hint), 14.sp, color = PanelDim)
    }
    Section(tr(R.string.picture_tilt))
    Selector(tr(R.string.straighten), tr(R.string.by_half_degree), Modifier.fillMaxWidth()) { step ->
        // Every step counts, so each gets its own key: none is merged into the one before.
        PanelIo.submit("tilt-${System.nanoTime()}") { Projection.tilt(clockwise = step > 0) }
    }
    Spacer(Modifier.height(10.dp))
    ListRow(tr(R.string.rotate_xgimi), onRotatePage)
}

/** Power off now, or later with the sleep timer; XGIMI's own menu for restart and the rest. */
@Composable
internal fun PowerPage(onOff: () -> Unit, onXgimiMenu: () -> Unit) {
    val context = LocalContext.current
    val end = SleepTimer.endsAt.longValue
    // Ticks the remaining time while the page is open.
    val now by produceState(System.currentTimeMillis(), end) {
        while (true) {
            value = System.currentTimeMillis()
            delay(15_000)
        }
    }
    Section(tr(R.string.now))
    ListRow(tr(R.string.power_off), onOff)
    Section(if (end > 0) tr(R.string.sleep_timer_left, SleepTimer.minutesLeft(now)) else tr(R.string.sleep_timer))
    val choices = listOf(0) + SleepTimer.options
    choices.chunked(2).forEach { pair ->
        PairRow {
            pair.forEach { minutes ->
                val label = if (minutes == 0) tr(R.string.off) else if (minutes % 60 == 0) tr(R.string.hours_short, minutes / 60) else tr(R.string.minutes_short, minutes)
                // The running timer's own chip is the one ticked; a new choice restarts it.
                val selected = if (minutes == 0) end == 0L else end > 0 && SleepTimer.lastMinutes(context) == minutes
                Chip(label, selected, Modifier.weight(1f)) {
                    if (minutes == 0) SleepTimer.cancel(context) else SleepTimer.start(context, minutes)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    if (end > 0) {
        val at = SimpleDateFormat("HH:mm", AppLanguage.locale).format(Date(end))
        T(tr(R.string.sleep_off_at, at), 14.sp, color = PanelDim)
    }
    Section(tr(R.string.more))
    ListRow(tr(R.string.reboot_xgimi_menu), onXgimiMenu)
}

/**
 * The screensaver: which one runs (XGIMI's "Any Door" or an installed one like Aerial Views),
 * its own settings, and how long the projector waits before starting it.
 */
@Composable
internal fun ScreensaverPage(onSetup: (android.content.ComponentName) -> Unit) {
    val context = LocalContext.current
    val choices by produceState(emptyList<Screensavers.Choice>()) {
        value = withContext(Dispatchers.IO) { Screensavers.installed(context) }
    }
    var current by remember { mutableStateOf(Screensavers.current(context)) }
    if (choices.isNotEmpty()) {
        Section(tr(R.string.screensaver))
        val index = choices.indexOfFirst { it.component == current }.coerceAtLeast(0)
        Selector(tr(R.string.screensaver_which), choices[index].label, Modifier.fillMaxWidth()) { step ->
            val next = choices[(index + step).mod(choices.size)]
            if (Screensavers.set(context, next.component)) current = Screensavers.current(context)
        }
        Spacer(Modifier.height(10.dp))
        ListRow(tr(R.string.screensaver_setup)) { onSetup(choices[index].component) }
    }
    var timeout by remember { mutableStateOf(ScreensaverTimeout.current(context)) }
    val options = ScreensaverTimeout.options
    Section(tr(R.string.start_after))
    if (ScreensaverTimeout.canWrite(context)) {
        // Unknown values (set elsewhere) show as the nearest longer option.
        val index = options.indices.filter { options[it].first >= timeout }.minBy { options[it].first }
        Selector(tr(R.string.idle), options[index].second, Modifier.fillMaxWidth()) { step ->
            val next = options[(index + step).coerceIn(0, options.lastIndex)].first
            if (ScreensaverTimeout.set(context, next)) timeout = ScreensaverTimeout.current(context)
        }
    } else {
        T(tr(R.string.no_write_settings), 14.sp, color = PanelDim)
    }
}
