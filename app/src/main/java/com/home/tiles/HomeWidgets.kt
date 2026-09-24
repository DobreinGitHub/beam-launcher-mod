package com.home.tiles

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
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.service.notification.NotificationListenerService
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

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
            .shadow(3.dp, CircleShape)
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
        Column(Modifier.widthIn(max = 220.dp)) {
            T(np.title, 18.sp)
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
            .background(if (focused) Colors.Accent.copy(alpha = 0.18f) else Color.Transparent, CircleShape)
            .then(if (focused) Modifier.pulseBorder(3.dp, CircleShape) else Modifier)
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
        Image(icon, null, Modifier.size(30.dp), colorFilter = ColorFilter.tint(Colors.Text))
    }
}

// ---- USB drives ----

class UsbDrive(val key: String, val label: String)

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
        .map { UsbDrive(it.uuid ?: it.getDescription(context), it.getDescription(context)) }
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

// ---- Tile art for the special tiles ----

@Composable
fun IconArt(icon: ImageVector, top: Color, bottom: Color) {
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom))),
        contentAlignment = Alignment.Center,
    ) {
        Image(icon, null, Modifier.fillMaxSize(0.42f), colorFilter = ColorFilter.tint(Color.White))
    }
}

@Composable
fun UsbArt() = IconArt(Icons.Rounded.Usb, Color(0xFFFF9F43), Color(0xFFE8590C))

@Composable
fun HdmiArt() = IconArt(Icons.Rounded.SettingsInputHdmi, Color(0xFF6C5CE7), Color(0xFF3B2DB0))
