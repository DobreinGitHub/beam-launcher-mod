package com.home.tiles

import android.media.AudioManager
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/** eARC for an HDMI 2.1 sound system (XGIMI's "eARC mode": Auto / Off). */
@Composable
internal fun EarcToggle() {
    var earc by remember { mutableStateOf(Earc.enabled()) }
    earc?.let { on ->
        Section("HDMI")
        Toggle("eARC", on, Modifier.fillMaxWidth()) {
            Earc.set(!on)
            earc = Earc.enabled() ?: !on
        }
    }
}

/** XGIMI's sound modes: AI, movie, music, sport, karaoke. */
@Composable
internal fun SoundModeSection() {
    var current by remember { mutableStateOf(SoundMode.current()) }
    if (current == null) return
    Section("Звуковой режим")
    SoundMode.modes.chunked(2).forEach { pair ->
        PairRow {
            pair.forEach { (mode, label) ->
                Chip(label, current == mode, Modifier.weight(1f)) {
                    SoundMode.set(mode)
                    current = SoundMode.current() ?: mode
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** Media volume, changed with left/right like the XGIMI sliders. */
@Composable
internal fun VolumeSlider(modifier: Modifier) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    LevelSlider(if (volume == 0) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, volume, max, modifier) {
        volume = it
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
    }
}

internal fun soundOutputName(device: Int) = when (device) {
    SoundOutput.SPEAKER -> "Динамик"
    SoundOutput.SPDIF -> "Оптика"
    SoundOutput.ARC -> "HDMI ARC"
    SoundOutput.BLUETOOTH -> "Bluetooth"
    else -> "Другой выход"
}

/** Where the sound goes, like XGIMI's page: automatic on/off, and the devices to pick when off. */
@Composable
internal fun SoundOutputSection() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { SoundOutput.prepare(context) }
    var auto by remember { mutableStateOf(SoundOutput.auto() ?: true) }
    var output by remember { mutableStateOf(SoundOutput.output()) }
    val connected = remember { listOf(SoundOutput.SPEAKER, SoundOutput.ARC, SoundOutput.BLUETOOTH).filter(SoundOutput::connected) }
    val scope = rememberCoroutineScope()
    var recheck by remember { mutableStateOf<Job?>(null) }
    // The firmware applies a switch asynchronously, so show the choice right away and read the
    // real state back a moment later.
    fun recheckSoon() {
        recheck?.cancel()
        recheck = scope.launch {
            delay(1200)
            auto = SoundOutput.auto() ?: auto
            output = SoundOutput.output()
        }
    }
    fun select(device: Int) {
        SoundOutput.setOutput(device)
        output = device
        recheckSoon()
    }
    Section("Выход звука")
    Toggle("Автовыбор", auto, Modifier.fillMaxWidth()) {
        auto = !auto
        SoundOutput.setAuto(auto)
        recheckSoon()
    }
    if (auto) {
        output?.let {
            Spacer(Modifier.height(8.dp))
            T("Сейчас: ${soundOutputName(it)}", 14.sp, color = PanelDim)
        }
    } else {
        Spacer(Modifier.height(10.dp))
        PairRow {
            OutputChip(SoundOutput.SPEAKER, output, connected, ::select)
            OutputChip(SoundOutput.ARC, output, connected, ::select)
        }
        Spacer(Modifier.height(10.dp))
        PairRow {
            OutputChip(SoundOutput.BLUETOOTH, output, connected, ::select)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun RowScope.OutputChip(device: Int, output: Int?, connected: List<Int>, select: (Int) -> Unit) {
    val available = device in connected
    Chip(
        soundOutputName(device),
        output == device,
        Modifier.weight(1f),
        note = if (available) null else "не подключено",
        enabled = available,
    ) { select(device) }
}
