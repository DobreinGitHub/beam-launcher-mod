package com.home.tiles

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** eARC for an HDMI 2.1 sound system (XGIMI's "eARC mode": Auto / Off). */
@Composable
internal fun EarcToggle() {
    val earc = rememberFirmwareState { Earc.enabled() }
    earc.value?.let { on ->
        Section("HDMI")
        Toggle("eARC", on, Modifier.fillMaxWidth()) {
            earc.change(!on) { Earc.set(!on) }
        }
    }
}

/** XGIMI's sound modes: AI, movie, music, sport, karaoke. */
@Composable
internal fun SoundModeSection() {
    val soundMode = rememberFirmwareState { SoundMode.current() }
    val current = soundMode.value ?: return
    Section(tr(R.string.sound_mode))
    SoundMode.modes.chunked(2).forEach { pair ->
        PairRow {
            pair.forEach { (mode, label) ->
                Chip(label, current == mode, Modifier.weight(1f)) {
                    soundMode.change(mode) { SoundMode.set(mode) }
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
    // The volume keys (and apps) change it while the panel is open: follow.
    DisposableEffect(audio) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra(EXTRA_VOLUME_STREAM, -1) == AudioManager.STREAM_MUSIC) {
                    volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(VOLUME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LevelSlider(if (volume == 0) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, volume, max, modifier) {
        volume = it
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
    }
}

private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
private const val EXTRA_VOLUME_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"

internal fun soundOutputName(device: Int) = when (device) {
    SoundOutput.SPEAKER -> tr(R.string.speaker)
    SoundOutput.SPDIF -> tr(R.string.optical)
    SoundOutput.ARC -> "HDMI ARC"
    SoundOutput.BLUETOOTH -> "Bluetooth"
    else -> tr(R.string.other_output)
}

/** What the sound output section shows: automatic or manual, the output in use, and which are connected. */
private data class SoundOutputState(val auto: Boolean, val output: Int?, val connected: List<Int>)

private fun readSoundOutput() = SoundOutputState(
    auto = SoundOutput.auto() ?: true,
    output = SoundOutput.output(),
    connected = listOf(SoundOutput.SPEAKER, SoundOutput.ARC, SoundOutput.BLUETOOTH).filter(SoundOutput::connected),
)

/** Where the sound goes, like XGIMI's page: automatic on/off, and the devices to pick when off. */
@Composable
internal fun SoundOutputSection() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SoundOutput.prepare(context) } }
    val sound = rememberFirmwareState { readSoundOutput() }
    val state = sound.value ?: return
    // The firmware applies a switch asynchronously, so the choice shows at once and the real state
    // is read back a moment later.
    fun select(device: Int) {
        sound.change(state.copy(output = device), settleMs = 1200) { SoundOutput.setOutput(device) }
    }
    Section(tr(R.string.sound_output))
    Toggle(tr(R.string.auto_select), state.auto, Modifier.fillMaxWidth()) {
        val auto = !state.auto
        sound.change(state.copy(auto = auto), settleMs = 1200) { SoundOutput.setAuto(auto) }
    }
    if (state.auto) {
        state.output?.let {
            Spacer(Modifier.height(8.dp))
            T(tr(R.string.now_value, soundOutputName(it)), 14.sp, color = PanelDim)
        }
    } else {
        Spacer(Modifier.height(10.dp))
        PairRow {
            OutputChip(SoundOutput.SPEAKER, state.output, state.connected, ::select)
            OutputChip(SoundOutput.ARC, state.output, state.connected, ::select)
        }
        Spacer(Modifier.height(10.dp))
        PairRow {
            OutputChip(SoundOutput.BLUETOOTH, state.output, state.connected, ::select)
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
        note = if (available) null else tr(R.string.not_connected_lc),
        enabled = available,
    ) { select(device) }
}
