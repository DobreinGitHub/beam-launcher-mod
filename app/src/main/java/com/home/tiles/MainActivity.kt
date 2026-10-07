package com.home.tiles

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var repo: AppRepository
    /** Null until the first load: the row shows placeholders instead of a lone "All apps" tile. */
    private val apps = mutableStateOf<List<AppEntry>?>(null)
    private val resumeTick = mutableIntStateOf(0)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepository(this)
        LauncherSettings.init(this)
        RemoteButtons.init(this)
        AccessibilityGuard.ensure(this)
        Sounds.init(this)
        Hdmi.migrateBootSource(this)
        setContent {
            LauncherApp(repo, apps.value, resumeTick.intValue, PanelRequests.count.intValue, onChanged = ::reload)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        AccessibilityGuard.ensure(this)
        reload()
        resumeTick.intValue++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home: jump back to the start.
        resumeTick.intValue++
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        Sounds.release()
        super.onDestroy()
    }

    private fun reload() {
        lifecycleScope.launch {
            apps.value = withContext(Dispatchers.IO) { repo.loadApps() }
        }
    }
}

/** Panel open requests from [OpenPanelActivity]; same process, so a plain counter is enough. */
object PanelRequests {
    val count = mutableIntStateOf(0)
}

/** Invisible trampoline: flags a panel request and brings the launcher forward. */
class OpenPanelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Over the current app when our accessibility service runs; otherwise via the launcher.
        if (!PanelOverlay.show()) {
            PanelRequests.count.intValue++
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}

private enum class Screen { Home, AllApps }

@Composable
private fun LauncherApp(
    repo: AppRepository,
    apps: List<AppEntry>?,
    resumeTick: Int,
    panelRequest: Int,
    onChanged: () -> Unit,
) {
    var screen by remember { mutableStateOf(Screen.Home) }
    var menu by remember { mutableStateOf<MenuRequest?>(null) }
    val context = LocalContext.current
    var panelOpen by remember { mutableStateOf(false) }

    LaunchedEffect(resumeTick) { screen = Screen.Home }
    LaunchedEffect(panelRequest) { if (panelRequest > 0) panelOpen = true }

    Box(Modifier.fillMaxSize().background(Colors.backgroundBrush()).arrowSoundTracker().panelKey { panelOpen = !panelOpen }) {
        if (Colors.isXmb) XmbBackground()
        AmbientWash()
        Box {
        when (screen) {
            Screen.Home -> HomeScreen(
                repo, apps, resumeTick,
                onOpenAll = { screen = Screen.AllApps },
                // The overlay service's panel when it runs, so the remote key and this button
                // always toggle the same single panel.
                onOpenPanel = { if (!PanelOverlay.show()) panelOpen = true },
                onMenu = { menu = it },
                onChanged = onChanged,
            )
            Screen.AllApps -> AllAppsScreen(
                repo, apps.orEmpty(),
                onBack = { screen = Screen.Home },
                // All apps is sorted by name, so its menu has no "Move".
                onOptions = { menu = appMenu(context, repo, it, onChanged, onMove = null) },
            )
        }
        }
        if (panelOpen) SettingsPanel(onDismiss = { panelOpen = false })
        menu?.let { request ->
            OptionsDialog(request, onDismiss = { menu = null })
        }
    }
}
