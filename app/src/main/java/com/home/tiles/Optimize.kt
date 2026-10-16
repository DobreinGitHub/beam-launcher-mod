package com.home.tiles

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Memory: what is free, and clearing it by closing the apps that sit idle in the background.
 * The projector has 1.3 GB and the firmware marks it low-RAM; with background apps kept (see
 * restore.ps1 -KeepBackgroundApps) a few of them fill it up.
 *
 * Android's own killBackgroundProcesses does the closing: it ends only processes the system counts
 * as background (cached, plain services), the same ones it ends itself when memory runs out. The app
 * on screen, a playing player, a VPN (foreground services) and the input method stay. Only apps the
 * user installed: XGIMI's own run the projector.
 */
object Memory {
    fun availableMb(context: Context): Long = info(context).availMem / MB
    fun totalMb(context: Context): Long = info(context).totalMem / MB

    private fun info(context: Context) =
        ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }

    /** Closes the idle background apps; returns how many megabytes that freed. */
    suspend fun clear(context: Context): Long = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(ActivityManager::class.java)
        val before = availableMb(context)
        val keyboard = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let { ComponentName.unflattenFromString(it)?.packageName }
        context.packageManager.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { it.packageName }
            .filter { it != context.packageName && it != keyboard && !it.startsWith("com.beam.") }
            .forEach { runCatching { manager.killBackgroundProcesses(it) } }
        // The kernel takes a moment to hand the memory back.
        delay(800)
        (availableMb(context) - before).coerceAtLeast(0)
    }

    private const val MB = 1024L * 1024L
}

/**
 * Fast mode: no window-open and transition animations, so apps open and close at once. The third
 * scale (animator duration) is left alone: it also drives every app's own interface animations,
 * Beam Mod's included, and turning it off makes them jump rather than feel faster.
 * Needs WRITE_SECURE_SETTINGS, which the installer grants.
 *
 * Beam Mod follows it too (see [Effects]): [state] tracks the setting, changed here or anywhere else.
 */
object FastMode {
    val state = mutableStateOf(false)
    private var watching = false

    fun init(context: Context) {
        if (watching) return
        watching = true
        val app = context.applicationContext
        state.value = on(app)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { state.value = on(app) }
        }
        for (name in listOf(Settings.Global.WINDOW_ANIMATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE)) {
            app.contentResolver.registerContentObserver(Settings.Global.getUriFor(name), false, observer)
        }
    }

    fun on(context: Context): Boolean {
        val resolver = context.contentResolver
        return Settings.Global.getFloat(resolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f) == 0f &&
            Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f
    }

    /** Returns false if the permission is missing. */
    fun set(context: Context, on: Boolean): Boolean = runCatching {
        val scale = if (on) 0f else 1f
        Settings.Global.putFloat(context.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, scale)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, scale)
        state.value = on
    }.isSuccess
}

/**
 * Beam Mod's own half of fast mode: no focus light, no background tint following the selection, no
 * moving XMB or loading shimmer, and every move and fade of its own done at once ([motion]).
 */
object Effects {
    val reduced: Boolean get() = FastMode.state.value
}

/** The spec for one of Beam Mod's own animations: [ms] long, or at once in fast mode. */
fun <T> motion(ms: Int, easing: Easing = FastOutSlowInEasing): FiniteAnimationSpec<T> =
    if (Effects.reduced) snap() else tween(ms, easing = easing)