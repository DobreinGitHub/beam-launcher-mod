package com.home.tiles

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Shows [PanelScreen] over whatever app is on screen. As an accessibility service it can both
 * catch the remote's voice key (F5, unused by the firmware) system-wide and add an overlay
 * window without the "draw over other apps" permission.
 */
class PanelOverlay : AccessibilityService() {
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private val handler = Handler(Looper.getMainLooper())

    // Voice key: a short press toggles the panel, holding it speaks a command.
    private var voice: VoiceSession? = null
    private var listening = false
    private var bubble: TextView? = null
    private val startListening = Runnable {
        listening = true
        Sounds.popup()
        showBubble("🎤  Слушаю…")
    }
    private val hideBubbleTask = Runnable { hideBubble() }

    override fun onServiceConnected() {
        instance = this
        LauncherSettings.init(this)
        RemoteButtons.init(this)
        Sounds.init(this)
        XgimiService.bind(this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val firstDown = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        // The four shortcut keys never get here: the firmware consumes them before accessibility
        // services see them (see the stub module), so only the voice key is handled.
        if (event.keyCode != KeyEvent.KEYCODE_F5) return false
        // Answer at once and do the work afterwards: if this call takes too long (~0.5 s, e.g.
        // while the launcher in this same process is busy), the system also hands the key to the
        // foreground app, and the launcher would open its own copy of the panel.
        when {
            firstDown -> handler.post(::voiceKeyDown)
            event.action == KeyEvent.ACTION_UP -> handler.post(::voiceKeyUp)
        }
        return true
    }

    /**
     * The remote streams its microphone while this key is held, so recording starts right away;
     * it only counts as a voice command once the key has been held past [HOLD_MS].
     */
    private fun voiceKeyDown() {
        voice?.cancel()
        voice = if (VoiceModel.installed(this)) VoiceSession(this).also { it.start() } else null
        listening = false
        if (voice != null) handler.postDelayed(startListening, HOLD_MS)
    }

    private fun voiceKeyUp() {
        handler.removeCallbacks(startListening)
        val session = voice
        voice = null
        if (!listening) {
            session?.cancel()
            if (view == null) showPanel() else hidePanel()
            return
        }
        listening = false
        showBubble("…")
        Thread {
            val text = session?.finish().orEmpty()
            val result = when {
                text == VoiceSession.NO_MODEL -> "Голосовая модель не установлена"
                text.isBlank() -> "Не расслышал"
                else -> VoiceCommands.run(this, text) ?: "Не понял: «$text»"
            }
            handler.post {
                showBubble(result)
                handler.postDelayed(hideBubbleTask, 2500)
            }
        }.start()
    }

    /** Small caption at the bottom of the screen for the voice key. */
    private fun showBubble(text: String) {
        handler.removeCallbacks(hideBubbleTask)
        bubble?.let {
            it.text = text
            return
        }
        val density = resources.displayMetrics.density
        val label = TextView(this).apply {
            this.text = text
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding((28 * density).toInt(), (14 * density).toInt(), (28 * density).toInt(), (14 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 28 * density
                setColor(0xE6202328.toInt())
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (48 * density).toInt()
        }
        runCatching { getSystemService(WindowManager::class.java).addView(label, params) }
        bubble = label
    }

    private fun hideBubble() {
        val label = bubble ?: return
        bubble = null
        runCatching { getSystemService(WindowManager::class.java).removeView(label) }
    }

    private fun showPanel() {
        if (view != null) return
        val owner = OverlayOwner().also { this.owner = it }
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { PanelScreen(onDismiss = ::hidePanel) }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        getSystemService(WindowManager::class.java).addView(composeView, params)
        owner.resume()
        view = composeView
    }

    private fun hidePanel() {
        val composeView = view ?: return
        view = null
        runCatching { getSystemService(WindowManager::class.java).removeView(composeView) }
        owner?.destroy()
        owner = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        voice?.cancel()
        hideBubble()
        hidePanel()
        instance = null
        super.onDestroy()
    }

    companion object {
        /** Holding the voice key this long turns the press into a voice command. */
        private const val HOLD_MS = 400L

        private var instance: PanelOverlay? = null

        /** Whether the service is connected, so it (not the launcher) handles the panel key. */
        val running get() = instance != null

        /** Opens the panel over the current app; false when the service isn't enabled. */
        fun show(): Boolean {
            val service = instance ?: return false
            service.showPanel()
            return true
        }
    }
}

/** Whether the panel is on screen (in the launcher or as the overlay). */
object PanelState {
    @Volatile
    var open = false
}

/** Minimal lifecycle + saved-state owner so Compose can run in a window without an activity. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    init {
        savedState.performRestore(null)
    }

    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
