package com.home.tiles

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf

/**
 * The remote's four app shortcut keys. The firmware handles them itself by launching fixed
 * Chinese video apps; stub apps with those package names (the :stub module) forward the press to
 * [RemoteButtonActivity], which runs the action chosen here.
 * Actions: "" = nothing, [PANEL], [HOME], or "app:<package>".
 */
object RemoteButtons {
    const val PANEL = "panel"
    const val HOME = "home"
    private const val APP = "app:"

    private lateinit var prefs: SharedPreferences

    /** Package names of the stub apps; never shown as tiles. */
    val stubPackages = setOf("com.cibn.tv", "com.ktcp.tvvideo", "com.gitvjimi.video", "com.hunantv.license")

    val actions = mutableStateListOf("", "", "", "")

    /** Which shortcut key was just pressed while the panel was open (-1 none), to point at its row. */
    val lastPressed = mutableIntStateOf(-1)

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        for (i in 0..3) actions[i] = prefs.getString("button$i", "").orEmpty()
    }

    fun set(index: Int, action: String) {
        actions[index] = action
        prefs.edit().putString("button$index", action).apply()
    }

    fun app(pkg: String) = APP + pkg

    fun packageOf(action: String) = action.removePrefix(APP).takeIf { action.startsWith(APP) }

    /** Runs the action for button [index]; [showPanel] opens the overlay panel. */
    fun perform(context: Context, index: Int, showPanel: () -> Unit) {
        val action = actions[index]
        when {
            action == PANEL -> showPanel()
            action == HOME -> context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            else -> packageOf(action)?.let { context.launchPackage(it) }
        }
    }
}

/** Entry point for the stub apps: `index` is the shortcut key (0-3). */
class RemoteButtonActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LauncherSettings.init(this)
        RemoteButtons.init(this)
        val index = intent.getIntExtra("index", -1)
        if (index in 0..3) {
            // With the panel open, a press just points at that button's row in "Кнопки пульта".
            if (PanelState.open) {
                RemoteButtons.lastPressed.intValue = index
            } else {
                RemoteButtons.perform(this, index) {
                    if (!PanelOverlay.show()) {
                        PanelRequests.count.intValue++
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
        finish()
    }
}
