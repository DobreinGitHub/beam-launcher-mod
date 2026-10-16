package com.home.tiles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.media.tv.TvInputManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.storage.StorageManager
import android.service.notification.NotificationListenerService
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SettingsInputHdmi
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Lets the system give us media sessions (granted over adb:
 * `cmd notification allow_listener com.home.tiles/com.home.tiles.MediaListener`).
 *
 * Also the one thing that restarts us after the firmware force-stops Beam for memory (it does so
 * during video playback). A force stop drops [PanelOverlay] from the enabled accessibility
 * services, but the system rebinds this listener right away, so it restores the panel service.
 */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        AccessibilityGuard.ensure(this)
    }
}

// ---- Now playing ----

class NowPlaying(
    val controller: MediaController,
    val title: String,
    val artist: String,
    val art: ImageBitmap?,
    val playing: Boolean,
)

@Composable
fun rememberNowPlaying(): State<NowPlaying?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<NowPlaying?>(null) }
    DisposableEffect(Unit) {
        val msm = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, MediaListener::class.java)
        val handler = Handler(Looper.getMainLooper())
        var current: MediaController? = null
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) { state.value = current?.toNowPlaying() }
            override fun onPlaybackStateChanged(playback: PlaybackState?) { state.value = current?.toNowPlaying() }
            override fun onSessionDestroyed() { state.value = null }
        }
        fun pick(sessions: List<MediaController>) {
            current?.unregisterCallback(callback)
            // Prefer whatever is actually playing.
            current = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull()
            current?.registerCallback(callback, handler)
            state.value = current?.toNowPlaying()
        }
        val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it.orEmpty()) }
        // SecurityException until the notification listener has been allowed; the widget just stays hidden.
        runCatching {
            pick(msm.getActiveSessions(listener))
            msm.addOnActiveSessionsChangedListener(sessionsListener, listener, handler)
        }
        onDispose {
            runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
            current?.unregisterCallback(callback)
        }
    }
    return state
}

private fun MediaController.toNowPlaying(): NowPlaying? {
    val meta = metadata ?: return null
    val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
        ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        ?: return null
    val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
        ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        ?: ""
    val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    return NowPlaying(this, title, artist, art?.asImageBitmap(), playbackState?.state == PlaybackState.STATE_PLAYING)
}

@Composable
fun NowPlayingBar(np: NowPlaying) {
    val controls = np.controller.transportControls
    Row(
        Modifier
            .height(66.dp)
            .background(Colors.Button, CircleShape)
            .padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(50.dp).clip(CircleShape).background(Color(0xFF3A3A3A)), contentAlignment = Alignment.Center) {
            if (np.art != null) {
                Image(np.art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Image(Icons.Rounded.MusicNote, null, Modifier.size(28.dp), colorFilter = ColorFilter.tint(Color.White))
            }
        }
        Spacer(Modifier.width(12.dp))
        // Gives way first when the bar is full (the title ends in «…»).
        Column(Modifier.weight(1f, fill = false).widthIn(max = 220.dp)) {
            T(np.title, 16.sp)
            if (np.artist.isNotEmpty()) T(np.artist, 14.sp, color = Colors.TextDim)
        }
        Spacer(Modifier.width(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MediaButton(Icons.Rounded.SkipPrevious) { controls.skipToPrevious() }
            MediaButton(if (np.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow) {
                if (np.playing) controls.pause() else controls.play()
            }
            MediaButton(Icons.Rounded.SkipNext) { controls.skipToNext() }
        }
    }
}

@Composable
private fun MediaButton(icon: ImageVector, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(44.dp)
            // Selected like the other top bar buttons: white, the icon dark.
            .background(if (focused) FocusBg else Color.Transparent, CircleShape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.navigate()
            }
            .clickable(remember { MutableInteractionSource() }, null) {
                Sounds.activate()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(icon, null, Modifier.size(30.dp), colorFilter = ColorFilter.tint(if (focused) FocusText else Colors.Text))
    }
}

// ---- USB drives ----

/** [totalBytes]: the drive's capacity, 0 when it can't be read. */
class UsbDrive(val key: String, val label: String, val totalBytes: Long = 0)

/**
 * A pretend USB drive and HDMI device in the top bar, for screenshots without plugging anything
 * in. Off unless switched on over adb: --ez demo_devices true (see AdbCommandReceiver).
 */
object DemoDevices {
    val on = mutableStateOf(false)
    val drive = UsbDrive("demo", "SanDisk", 64L * 1000 * 1000 * 1000)
    val hdmi = Xgimi.Input("PlayStation 5", "demo", port = "HDMI 1")
}

@Composable
fun rememberUsbDrives(): State<List<UsbDrive>> {
    val context = LocalContext.current
    return produceState(queryUsbDrives(context)) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { value = queryUsbDrives(context) }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        awaitDispose { context.unregisterReceiver(receiver) }
    }
}

private fun queryUsbDrives(context: Context): List<UsbDrive> = runCatching {
    context.getSystemService(StorageManager::class.java).storageVolumes
        .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
        .map { volume ->
            val size = if (Build.VERSION.SDK_INT >= 30) {
                runCatching { volume.directory?.let { StatFs(it.path).totalBytes } }.getOrNull() ?: 0L
            } else 0L
            UsbDrive(volume.uuid ?: volume.getDescription(context), volume.getDescription(context), size)
        }
}.getOrDefault(emptyList())

// ---- HDMI ----

/** HDMI inputs with a live signal (a powered-on device on the cable). */
@Composable
fun rememberLiveHdmi(): State<List<Xgimi.Input>> {
    val context = LocalContext.current
    return produceState(queryLiveHdmi(context)) {
        val tv = context.getSystemService(TvInputManager::class.java)
        val callback = object : TvInputManager.TvInputCallback() {
            override fun onInputStateChanged(inputId: String, state: Int) { value = queryLiveHdmi(context) }
            override fun onInputAdded(inputId: String) { value = queryLiveHdmi(context) }
            override fun onInputRemoved(inputId: String) { value = queryLiveHdmi(context) }
        }
        tv?.registerCallback(callback, Handler(Looper.getMainLooper()))
        awaitDispose { tv?.unregisterCallback(callback) }
    }
}

private fun queryLiveHdmi(context: Context): List<Xgimi.Input> {
    val tv = context.getSystemService(TvInputManager::class.java) ?: return emptyList()
    return Xgimi.hdmiInputs(context).filter {
        runCatching { tv.getInputState(it.id) }.getOrNull() == TvInputManager.INPUT_STATE_CONNECTED
    }
}

// ---- Connected devices ----

/**
 * A connected device in the top bar, shown only while it is plugged in: a pill like the now-playing
 * bar with a round icon in the device's colour, its name and a second line (size, port). Selected,
 * it fills like the top-bar buttons. [compact]: just the round icon, opening out into the pill
 * while selected (the bar has no room for several full pills).
 */
@Composable
fun DeviceChip(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    compact: Boolean,
    onGone: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // Unplugged while selected: the selection would go with the chip and leave nothing selected
    // (the remote then did nothing); it moves to a neighbour instead, once the chip is gone.
    val view = LocalView.current
    DisposableEffect(Unit) { onDispose { if (focused) view.post(onGone) } }
    val focusFill = FocusBg
    val focusText = FocusText
    val fill by animateColorAsState(if (focused) focusFill else Colors.Button, motion(150), label = "fill")
    val text by animateColorAsState(if (focused) focusText else Colors.Text, motion(150), label = "text")
    val open = !compact || focused
    Row(
        Modifier
            .height(64.dp)
            .background(fill, CircleShape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.navigate()
            }
            .clickable(remember { MutableInteractionSource() }, null) {
                Sounds.activate()
                onClick()
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).background(Colors.Background, CircleShape), contentAlignment = Alignment.Center) {
            Image(icon, title, Modifier.size(26.dp), colorFilter = ColorFilter.tint(tint))
        }
        AnimatedVisibility(
            open,
            enter = expandHorizontally(motion(180), expandFrom = Alignment.Start) + fadeIn(motion(180)),
            exit = shrinkHorizontally(motion(150), shrinkTowards = Alignment.Start) + fadeOut(motion(100)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(12.dp))
                // Gives way first when the bar is full (the title ends in «…»).
        Column(Modifier.weight(1f, fill = false).widthIn(max = 220.dp)) {
                    T(title, 16.sp, color = text)
                    T(subtitle, 14.sp, color = text.copy(alpha = 0.7f))
                }
                Spacer(Modifier.width(14.dp))
            }
        }
    }
}

/** The devices' colours; deeper in the light theme, where the pale ones wouldn't show. */
val UsbTint get() = if (LauncherSettings.dark) Color(0xFFFFB27A) else Color(0xFFD9682B)
val HdmiTint get() = if (LauncherSettings.dark) Color(0xFFB3A9FF) else Color(0xFF6A5AD6)

@Composable
fun UsbChip(drive: UsbDrive, compact: Boolean, onGone: () -> Unit, onClick: () -> Unit) = DeviceChip(
    Icons.Rounded.Usb,
    UsbTint,
    drive.label.ifBlank { tr(R.string.usb_drive) },
    if (drive.totalBytes > 0) tr(R.string.usb_size, (drive.totalBytes / 1_000_000_000.0).roundToInt()) else "USB",
    compact,
    onGone,
    onClick,
)

/** The device's own name when it gives one (HDMI-CEC), with the port under it; else the port. */
@Composable
fun HdmiChip(input: Xgimi.Input, compact: Boolean, onGone: () -> Unit, onClick: () -> Unit) = DeviceChip(
    Icons.Rounded.SettingsInputHdmi,
    HdmiTint,
    input.label,
    if (input.device != null) input.port else tr(R.string.device_connected),
    compact,
    onGone,
    onClick,
)
