package com.home.tiles

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
    // One WindowManager for adding and removing the overlay windows.
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private var panelParams: WindowManager.LayoutParams? = null

    // Voice key: a short press toggles the panel, holding it speaks a command.
    private var voice: VoiceSession? = null
    private var listening = false
    private var bubble: TextView? = null
    private val startListening = Runnable {
        listening = true
        voice?.loadModel()
        Sounds.popup()
        showBubble(tr(R.string.listening))
    }
    private val hideBubbleTask = Runnable { hideBubble() }

    override fun onServiceConnected() {
        instance = this
        LauncherSettings.init(this)
        RemoteButtons.init(this)
        Sounds.init(this)
        XgimiService.bind(this)
        // The low-battery warning has to come over any app, not only while the launcher shows.
        BatteryMonitor.start(this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val firstDown = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        // The four shortcut keys never get here: the firmware consumes them before accessibility
        // services see them (see the stub module). The settings key is left to the firmware: it
        // opens XGIMI's quick panel, and ours closes for it (see onAccessibilityEvent). XGIMI may
        // not open over ours, so ours steps aside at once and opens it if the firmware didn't.
        if (event.keyCode == SETTINGS_KEY) {
            if (firstDown && view != null) handler.post {
                handOver { handler.postDelayed({ if (!stockShown()) openQuickPanel() }, 300) }
            }
            return false
        }
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
     * Closes XGIMI's quick panel or settings if one is on screen now (it closes on
     * CLOSE_SYSTEM_DIALOGS, any reason but its own). Only what is already there: a guard that
     * closed anything XGIMI opened for a while afterwards also closed the panel the user asked for
     * a moment later.
     */
    private fun closeStockIfOpen() {
        if (!stockShown()) return
        sendCloseDialogs()
        handler.postDelayed({ if (stockShown()) sendCloseDialogs() }, 150)
    }

    private fun stockShown(): Boolean =
        runCatching { getWindows().any { it.root?.packageName == STOCK_SETTINGS } }.getOrDefault(false)

    private fun sendCloseDialogs() {
        @Suppress("DEPRECATION")
        sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "beam"))
    }

    /**
     * The remote streams its microphone while this key is held, so recording starts right away;
     * it only counts as a voice command once the key has been held past [HOLD_MS].
     */
    private fun voiceKeyDown() {
        // Another app is waiting for dictation: the key is its push-to-talk.
        VoiceRequests.onVoiceKey?.let {
            it(true)
            return
        }
        voice?.cancel()
        voice = if (VoiceModel.installed(this)) VoiceSession(this).also { it.start(loadModel = false) } else null
        listening = false
        if (voice != null) handler.postDelayed(startListening, HOLD_MS)
    }

    private fun voiceKeyUp() {
        VoiceRequests.onVoiceKey?.let {
            it(false)
            return
        }
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
            // Recognition is slow and stays here; the command itself touches windows and toasts,
            // which need the main thread.
            handler.post {
                val result = when {
                    text == VoiceSession.NO_MODEL -> tr(R.string.voice_model_missing)
                    text.isBlank() -> tr(R.string.didnt_catch)
                    else -> VoiceCommands.run(this, text) ?: tr(R.string.didnt_understand, text)
                }
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
        runCatching { windows.addView(label, params) }
            .onSuccess { bubble = label }
            .onFailure { Log.w("PanelOverlay", "Caption window failed", it) }
    }

    private fun hideBubble() {
        val label = bubble ?: return
        bubble = null
        runCatching { windows.removeViewImmediate(label) }
    }

    private fun showPanel() {
        if (view != null) return
        val owner = OverlayOwner().also { this.owner = it }
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { PanelScreen(onDismiss = ::hidePanel, handOver = ::handOver) }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { windowAnimations = 0 }
        val added = runCatching { windows.addView(composeView, params) }
            .onFailure { Log.w("PanelOverlay", "Panel window failed", it) }
            .isSuccess
        if (!added) {
            owner.destroy()
            this.owner = null
            return
        }
        owner.resume()
        view = composeView
        panelParams = params
        // One panel at a time: XGIMI's quick panel goes when ours comes (the voice key can open
        // ours over it). Ours covers the same spot, so the swap doesn't show.
        closeStockIfOpen()
    }

    /** Set while ours steps aside for XGIMI's panel or settings (see [handOver]). */
    private var handingOver = false
    private val handOverFallback = Runnable {
        // XGIMI didn't show while ours was still there: let ours go and ask once more.
        if (!handingOver) return@Runnable
        handingOver = false
        hidePanel()
        if (!stockShown()) pendingOpen?.invoke()
        pendingOpen = null
    }
    private var pendingOpen: (() -> Unit)? = null

    /**
     * Opens XGIMI's panel or settings ([open]) in place of ours without a flash: ours stops taking
     * keys and touches but stays (its darkening stays on screen) until XGIMI's window is up, then
     * goes. Removing ours first left a moment of undarkened home in between.
     */
    private fun handOver(open: () -> Unit) {
        val composeView = view ?: return open()
        panelParams?.let { params ->
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { windows.updateViewLayout(composeView, params) }
        }
        handingOver = true
        pendingOpen = open
        open()
        handler.postDelayed(handOverFallback, 900)
    }

    /**
     * Removes the panel window. It must never outlive the panel: an empty, surface-less overlay
     * window once stayed registered with the window manager and kept the input focus, so the remote
     * controlled nothing until a reboot. It's made non-focusable first, then removed immediately.
     */
    private fun hidePanel() {
        handler.removeCallbacks(handOverFallback)
        handingOver = false
        pendingOpen = null
        val composeView = view ?: return
        view = null
        panelParams?.let { params ->
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { windows.updateViewLayout(composeView, params) }
        }
        panelParams = null
        runCatching { windows.removeViewImmediate(composeView) }
            .onFailure { Log.w("PanelOverlay", "Panel window removal failed", it) }
        owner?.destroy()
        owner = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (view != null && event.packageName == STOCK_SETTINGS &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            // XGIMI's panel or settings is up (handed over, its settings key, its own button): ours
            // makes way now, so the two never stand side by side and nothing flashes in between.
            hidePanel()
        }
    }

    override fun onInterrupt() {}

    // The system is tearing the service down (e.g. AccessibilityGuard restarting it): close the
    // panel while its window token is still valid, so the window can't be left behind.
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hideBubble()
        hidePanel()
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        voice?.cancel()
        hideBubble()
        hidePanel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        /** Holding the voice key this long turns the press into a voice command. */
        private const val HOLD_MS = 1000L

        /** The remote's gear key; XGIMI's system UI opens its quick panel on it. */
        private const val SETTINGS_KEY = KeyEvent.KEYCODE_MOVE_HOME
        private const val STOCK_SETTINGS = "com.android.newsettings"

        @Volatile
        private var instance: PanelOverlay? = null

        /** Shows [text] at the bottom of the screen (null hides it), optionally hiding it later. */
        fun caption(text: String?, hideAfterMs: Long = 0) {
            val service = instance ?: return
            service.handler.post {
                if (text == null) service.hideBubble() else {
                    service.showBubble(text)
                    if (hideAfterMs > 0) service.handler.postDelayed(service.hideBubbleTask, hideAfterMs)
                }
            }
        }

        /** Whether the service is connected, so it (not the launcher) handles the panel key. */
        val running get() = instance != null

        /**
         * Opens the panel over the current app; false when the service isn't enabled. Safe from any
         * thread: the window is added on the main thread.
         */
        fun show(): Boolean {
            val service = instance ?: return false
            service.handler.post { service.showPanel() }
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
