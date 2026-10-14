package com.home.tiles

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * UI sounds. Moving and selecting play the system's own interface effects, the ones XGIMI's
 * settings and panels use (android_TV_scroll_* per direction, android_TV_select), so Beam sounds
 * like the rest of the projector and follows its click-sound switch. Only the voice key's
 * "listening" cue is Beam's own.
 */
object Sounds {
    private var audio: AudioManager? = null
    private var pool: SoundPool? = null
    private var popup = 0
    private var lastArrowAt = 0L
    private var lastArrow = AndroidKeyEvent.KEYCODE_DPAD_RIGHT

    fun init(context: Context) {
        if (audio == null) audio = context.getSystemService(AudioManager::class.java)
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attrs).build().also {
            popup = it.load(context, R.raw.popup, 1)
        }
    }

    fun release() {
        pool?.release()
        pool = null
    }

    /** Called on arrow presses so only user-driven focus moves sound, not focus restored on resume. */
    fun markArrow(keyCode: Int) {
        lastArrowAt = SystemClock.uptimeMillis()
        lastArrow = keyCode
    }

    fun navigate() {
        if (SystemClock.uptimeMillis() - lastArrowAt >= 300) return
        effect(
            when (lastArrow) {
                AndroidKeyEvent.KEYCODE_DPAD_UP -> AudioManager.FX_FOCUS_NAVIGATION_UP
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> AudioManager.FX_FOCUS_NAVIGATION_DOWN
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> AudioManager.FX_FOCUS_NAVIGATION_LEFT
                else -> AudioManager.FX_FOCUS_NAVIGATION_RIGHT
            },
        )
    }

    fun activate() = effect(AudioManager.FX_KEY_CLICK)

    /** Only the voice key's "listening" cue: menus and panels open silently. */
    fun popup() {
        if (popup != 0 && LauncherSettings.sounds) pool?.play(popup, 0.7f, 0.7f, 1, 0, 1f)
    }

    private fun effect(type: Int) {
        if (LauncherSettings.sounds) audio?.playSoundEffect(type)
    }
}

/** Put on the root of each window so arrow presses arm the navigation sound. */
fun Modifier.arrowSoundTracker(): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown) {
        when (val code = event.nativeKeyEvent.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN,
            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
            -> Sounds.markArrow(code)
        }
    }
    false
}
