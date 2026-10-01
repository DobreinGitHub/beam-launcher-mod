package com.home.tiles

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The XGIMI firmware resets the enabled accessibility services on boot, and a service whose process
 * got killed is marked crashed and never rebound. The launcher starts on boot and on every return
 * home, so it re-enables (or restarts) our [PanelOverlay] service. Needs WRITE_SECURE_SETTINGS,
 * granted over adb.
 */
object AccessibilityGuard {
    private val overlay = ComponentName("com.home.tiles", "com.home.tiles.PanelOverlay")

    fun ensure(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val app = context.applicationContext
        if (isEnabled(app) && isBound(app)) return
        if (!restarting.compareAndSet(false, true)) return
        // Off the caller's thread: restoring needs pauses and a check that the system bound it.
        Thread {
            runCatching {
                // After a force stop the service is dropped *and* marked crashed; re-adding it
                // doesn't always clear that, so verify and restart (remove, pause, add) until bound.
                for (attempt in 1..4) {
                    val wasEnabled = isEnabled(app)
                    if (wasEnabled) {
                        // Two writes in a row get coalesced, so the system must see the removal first.
                        write(app, others(app))
                        Thread.sleep(1500)
                    }
                    write(app, others(app) + overlay.flattenToString())
                    Settings.Secure.putString(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
                    Log.i("AccessibilityGuard", "${if (wasEnabled) "Restarted" else "Re-enabled"} panel service (attempt $attempt)")
                    Thread.sleep(3000)
                    if (isBound(app)) break
                }
            }.onFailure { Log.w("AccessibilityGuard", "Could not restore panel service", it) }
            restarting.set(false)
        }.start()
    }

    private fun services(context: Context) =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() }

    private fun others(context: Context) = services(context).filter { ComponentName.unflattenFromString(it) != overlay }

    private fun isEnabled(context: Context) = services(context).any { ComponentName.unflattenFromString(it) == overlay }

    private fun isBound(context: Context) = context.getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { ComponentName.unflattenFromString(it.id) == overlay }

    private fun write(context: Context, entries: List<String>) {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, entries.joinToString(":"))
    }

    private val restarting = AtomicBoolean(false)
}
