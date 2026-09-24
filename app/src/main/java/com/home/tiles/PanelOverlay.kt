package com.home.tiles

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
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

    override fun onServiceConnected() {
        instance = this
        LauncherSettings.init(this)
        RemoteButtons.init(this)
        Sounds.init(this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val firstDown = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        // The four shortcut keys never get here: the firmware consumes them before accessibility
        // services see them (see the stub module), so only the voice key is handled.
        if (event.keyCode != KeyEvent.KEYCODE_F5) return false
        // Answer at once and build the panel afterwards: if this call takes too long (~0.5 s, e.g.
        // while the launcher in this same process is busy), the system also hands the key to the
        // foreground app, and the launcher would open its own copy of the panel.
        if (firstDown) handler.post { if (view == null) showPanel() else hidePanel() }
        return true
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
        hidePanel()
        instance = null
        super.onDestroy()
    }

    companion object {
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
