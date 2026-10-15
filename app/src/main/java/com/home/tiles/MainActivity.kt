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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
        // The window's own background only covers the moment before the first frame (dark, so a
        // start never flashes white): Beam paints the whole screen itself, so it goes once that
        // first frame is up rather than being drawn, unseen, under every frame.
        window.decorView.post { window.setBackgroundDrawable(null) }
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

    // The projector has 1.4 GB: when the system runs short, the picture caches shrink rather
    // than Beam being stopped, which would cost a full start on the way home.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        repo.trimArt(level)
        trimPosters(level)
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

/**
 * Whether XGIMI's quick panel or settings is on screen; kept by [PanelOverlay] from the window
 * events it already watches.
 */
object XgimiScreens {
    val shown = mutableStateOf(false)
}

/**
 * The home screen's own top bar (buttons, clock, network, battery) steps aside while XGIMI's quick
 * panel or settings is open, so their clock and battery aren't shown twice and nothing peeks out
 * behind them. Beam's own settings page has no clock of its own, so the bar stays beside it.
 */
@Composable
fun Modifier.hiddenUnderPanels(): Modifier {
    val alpha by animateFloatAsState(if (XgimiScreens.shown.value) 0f else 1f, tween(150), label = "chrome")
    return graphicsLayer { this.alpha = alpha }
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LauncherApp(
    repo: AppRepository,
    apps: List<AppEntry>?,
    resumeTick: Int,
    panelRequest: Int,
    onChanged: () -> Unit,
) {
    var screen by remember { mutableStateOf(NavTab.Home) }
    // The screen puts the selection on its first tile once it is up: coming home, OK on a tab,
    // the All apps tile. Shown only because its tab is highlighted, it leaves the selection there.
    var takeFocus by remember { mutableStateOf(true) }
    // The last tab highlighted, counted so highlighting the same tab again still counts.
    var highlight by remember { mutableStateOf(NavTab.Home to 0) }
    var inBar by remember { mutableStateOf(false) }
    val tabFocus = remember { List(NavTab.entries.size) { FocusRequester() } }
    var menu by remember { mutableStateOf<MenuRequest?>(null) }
    val context = LocalContext.current
    var panelOpen by remember { mutableStateOf(false) }

    LaunchedEffect(resumeTick) {
        screen = NavTab.Home
        takeFocus = true
    }
    LaunchedEffect(panelRequest) { if (panelRequest > 0) panelOpen = true }
    // A highlighted tab shows its screen after a moment, as on Google TV: passing over it on the
    // way to the buttons beside it, or straight down, doesn't build a screen for nothing.
    LaunchedEffect(highlight) {
        delay(TabDwellMs)
        if (inBar) screen = highlight.first
    }
    fun open(tab: NavTab) {
        screen = tab
        takeFocus = true
    }
    // The overlay service's panel when it runs, so the remote key and this button always toggle
    // the same single panel.
    val openPanel = { if (!PanelOverlay.show()) panelOpen = true }
    // Back in All apps goes up to its tab first, then home (as on Google TV).
    BackHandler(enabled = screen == NavTab.AllApps) {
        if (inBar) {
            screen = NavTab.Home
            runCatching { tabFocus[NavTab.Home.ordinal].requestFocus() }
        } else {
            runCatching { tabFocus[NavTab.AllApps.ordinal].requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize().arrowSoundTracker().panelKey { panelOpen = !panelOpen }) {
        // The background with its tint of the selected app's colour.
        Backdrop()
        Column(Modifier.fillMaxSize()) {
            // One top bar over both screens: they change under it.
            TopNav(
                screen, tabFocus,
                onTabHighlighted = { highlight = it to highlight.second + 1 },
                onTabClick = ::open,
                onSettings = openPanel,
                onFocusInside = { inBar = it },
            )
            ScreenSwitch(
                screen,
                Modifier
                    .weight(1f)
                    // Up from a screen's top row goes to that screen's tab, whatever is nearest above.
                    .focusProperties { exit = { if (it == FocusDirection.Up) tabFocus[screen.ordinal] else FocusRequester.Default } }
                    .focusGroup(),
            ) { tab ->
                // Only the screen coming in takes the selection, not the one leaving.
                val focusHere = takeFocus && tab == screen
                when (tab) {
                    NavTab.Home -> HomeScreen(
                        repo, apps, resumeTick,
                        takeFocus = focusHere,
                        onFocusTaken = { takeFocus = false },
                        onOpenAll = { open(NavTab.AllApps) },
                        onMenu = { menu = it },
                        onChanged = onChanged,
                    )
                    NavTab.AllApps -> AllAppsScreen(
                        repo, apps.orEmpty(),
                        takeFocus = focusHere,
                        onFocusTaken = { takeFocus = false },
                        // All apps is sorted by name, so its menu has no "Move".
                        onOptions = { menu = appMenu(context, repo, it, onChanged, onMove = null) },
                    )
                }
            }
        }
        if (panelOpen) SettingsPanel(onDismiss = { panelOpen = false })
        menu?.let { request ->
            OptionsDialog(request, onDismiss = { menu = null })
        }
    }
}

private const val TabDwellMs = 150L
private const val ScreenSlideMs = 260
private val ScreenShift = 56.dp

/**
 * Whether the screen around is the one shown: a screen kept ready behind the other one can't
 * take the selection (its tiles and cards read this).
 */
val LocalScreenShown = compositionLocalOf { true }

/**
 * The screens under the top bar, changing with a short slide and fade, Google TV style: All apps
 * comes in from the right, home from the left, each moving a little way and fading as it goes.
 *
 * Both screens are kept, the one not shown hidden behind (All apps is built quietly a moment
 * after the start): building a screen when it was asked for, and its pictures first reaching the
 * GPU, stalled the change for some 450 ms, and the slide showed as a jump. A change now only
 * moves and fades two layers, alpha applied to their drawing (no offscreen copies).
 */
@Composable
private fun ScreenSwitch(screen: NavTab, modifier: Modifier, content: @Composable (NavTab) -> Unit) {
    var built by remember { mutableStateOf(setOf(screen)) }
    LaunchedEffect(screen) { built = built + screen }
    LaunchedEffect(Unit) {
        delay(PrebuildDelayMs)
        built = built + NavTab.entries
    }
    Box(modifier) {
        NavTab.entries.forEach { tab ->
            key(tab) {
                if (tab in built) {
                    val shown by animateFloatAsState(
                        if (tab == screen) 1f else 0f,
                        tween(ScreenSlideMs, easing = FastOutSlowInEasing),
                        label = "screen",
                    )
                    // All apps sits to the right of home: it comes and goes on the right, home on the left.
                    val side = if (tab == NavTab.AllApps) 1f else -1f
                    // A screen built behind is drawn once, at an alpha too faint to see: a layer at
                    // no alpha is never drawn, and its first drawing (with its pictures) then
                    // stalled the first change to it by some 110 ms.
                    var drawnOnce by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        withFrameNanos {}
                        withFrameNanos {}
                        drawnOnce = true
                    }
                    CompositionLocalProvider(LocalScreenShown provides (tab == screen)) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    // Fading through, not across: the leaving screen is gone by
                                    // halfway, the coming one shows from there (the two curves
                                    // mirror each other). A layer at no alpha isn't drawn at all,
                                    // so the GPU never draws both screens. The slide runs the
                                    // whole way.
                                    alpha = ((shown - 0.5f) * 2f).coerceIn(if (drawnOnce) 0f else 0.004f, 1f)
                                    translationX = (1f - shown) * side * ScreenShift.toPx()
                                    compositingStrategy = CompositingStrategy.ModulateAlpha
                                },
                        ) {
                            content(tab)
                        }
                    }
                }
            }
        }
    }
}

// After the start, once home is up, All apps is built behind it.
private const val PrebuildDelayMs = 2000L
