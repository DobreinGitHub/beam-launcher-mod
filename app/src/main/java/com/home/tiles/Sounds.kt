package com.home.tiles

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** Switch-style UI sounds. */
object Sounds {
    private var pool: SoundPool? = null
    private var nav = 0
    private var activate = 0
    private var popup = 0
    private var lastArrowAt = 0L

    fun init(context: Context) {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build().also {
            nav = it.load(context, R.raw.nav, 1)
            activate = it.load(context, R.raw.activate, 1)
            popup = it.load(context, R.raw.popup, 1)
        }
    }

    fun release() {
        pool?.release()
        pool = null
    }

    /** Called on arrow presses so only user-driven focus moves click, not focus restored on resume. */
    fun markArrow() {
        lastArrowAt = SystemClock.uptimeMillis()
    }

    fun navigate() {
        if (SystemClock.uptimeMillis() - lastArrowAt < 300) play(nav)
    }

    fun activate() = play(activate)

    /** Only the voice key's "listening" cue: menus and panels open silently. */
    fun popup() = play(popup)

    private fun play(id: Int) {
        if (id != 0 && LauncherSettings.sounds) pool?.play(id, 0.7f, 0.7f, 1, 0, 1f)
    }
}

/** Put on the root of each window so arrow presses arm the navigation sound. */
fun Modifier.arrowSoundTracker(): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown) {
        when (event.nativeKeyEvent.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN,
            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
            -> Sounds.markArrow()
        }
    }
    false
}
