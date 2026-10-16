package com.home.tiles

import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.text.font.FontWeight
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider


/** Beam's own sections of the settings page; XGIMI's panel and settings are the tiles above them. */
private enum class Page(@StringRes val titleRes: Int, val icon: ImageVector) {
    // Outlined, like the line icons of XGIMI's quick panel and settings beside this page; the
    // screensaver's is the flower XGIMI's «Заставки» tile has.
    Appearance(R.string.appearance, Icons.Outlined.Palette),
    Home(R.string.home_screen, Icons.Outlined.Home),
    Remote(R.string.remote_buttons, Icons.Outlined.SettingsRemote),
    Screensaver(R.string.screensaver, Icons.Outlined.FilterVintage),
    About(R.string.about_beam, Icons.Outlined.Info);

    val title: String get() = tr(titleRes)
}

/**
 * The settings page as a dialog over the launcher, when the overlay service isn't running; with
 * [rows], the home screen's section alone, for its rows list (from the button under the rows).
 */
@Composable
fun SettingsPanel(onDismiss: () -> Unit, rows: Boolean = false) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            // Back first leaves a section; PanelScreen decides.
            dismissOnBackPress = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        PanelScreen(onDismiss, rows = rows)
    }
}

/**
 * Beam's settings page, laid out like XGIMI's quick panel, so the two read as one: no card, tiles
 * over the darkened screen. A header with the page's name and, at its right, a round gear that
 * opens XGIMI's full settings (as the gear on XGIMI's panel does); a full-width pill to XGIMI's
 * quick panel; wide tiles for Beam's sections; small square tiles for quick switches. These hand
 * over to XGIMI (this page closes, so the two never stand side by side). A section opens in the
 * panel's place; Back returns to its tile, and Back again closes. Hosted by [SettingsPanel] in the
 * launcher and by [PanelOverlay] over other apps; appears at once.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PanelScreen(
    onDismiss: () -> Unit,
    /** Opens XGIMI's panel or settings in this page's place; the overlay keeps its darkening until XGIMI's is up. */
    handOver: (open: () -> Unit) -> Unit = { open -> onDismiss(); open() },
    /** Open on the home screen's section; Back then closes, back to the home screen it came from. */
    rows: Boolean = false,
) {
    val context = LocalContext.current
    // The section shown, or null for the panel.
    // Opened straight on a section (the rows list, or «Кнопки пульта» from XGIMI's settings): Back closes.
    val direct = remember { rows || PanelOpenOn.takeRemoteButtons() }
    var page by remember { mutableStateOf(if (rows) Page.Home else if (direct) Page.Remote else null) }
    // The tile to put the selection on when the panel shows: the first, or the section just left.
    var lastPage by remember { mutableStateOf<Page?>(null) }
    // Handing over to XGIMI: the tiles fade, the darkening stays until XGIMI's own is on screen.
    var leaving by remember { mutableStateOf(false) }
    val tilesAlpha by animateFloatAsState(if (leaving) 0f else 1f, tween(120), label = "leave")
    val firstFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    val pageHold = remember { FocusRequester() }
    var contentHasFocus by remember { mutableStateOf(false) }
    val pageFocus = remember { Page.entries.associateWith { FocusRequester() } }
    DisposableEffect(Unit) {
        PanelState.open = true
        onDispose { PanelState.open = false }
    }
    fun back() {
        // A view inside the section (the remote keys' action list) closes first.
        if (page != null && PanelSubBack.handler?.invoke() == true) return
        if (page != null && !direct) page = null else onDismiss()
    }
    fun xgimi(quick: Boolean) {
        leaving = true
        handOver { if (quick) context.openQuickPanel() else context.openSettings() }
    }
    // Back with nothing focused (after a touch) goes through the dialog's dispatcher, not key events.
    if (LocalOnBackPressedDispatcherOwner.current != null) BackHandler { back() }

    Box(
        Modifier
            .fillMaxSize()
            .background(TvScrimLeft)
            .arrowSoundTracker()
            .panelKey(onDismiss)
            // The overlay window has no back dispatcher, so Back is handled here for both hosts.
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.keyCode != AndroidKeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) back()
                true
            }
            // Touch: a tap beside the tiles closes the page.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            // Holds the selection when a section has nothing to select; after the key handlers
            // above, so its keys still reach them.
            .focusRequester(pageHold)
            .focusable(),
    ) {
        // One scroll position per view: each opens at its top.
        key(page) {
            val shown = page
            Column(
                Modifier
                    // Where XGIMI's quick panel sits, as wide.
                    // Down to the screen's edge, so a long list runs off it rather than being cut
                    // on a line above it; the end of the content has its own room (below).
                    .padding(start = PanelStart, top = PanelTop)
                    .width(PanelWidth)
                    .fillMaxHeight()
                    .graphicsLayer { alpha = tilesAlpha }
                    // Taps on the tiles themselves must not reach the close-on-tap backdrop.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .verticalScroll(rememberScrollState()),
            ) {
                if (shown == null) {
                    QuickPanel(firstFocus, pageFocus, onXgimi = ::xgimi) {
                        lastPage = it
                        page = it
                    }
                } else {
                    Header(shown.title) { StatusLine() }
                    Spacer(Modifier.height(20.dp))
                    Column(
                        Modifier
                            .onFocusChanged { contentHasFocus = it.hasFocus }
                            .focusRequester(contentFocus)
                            .focusGroup(),
                    ) { Content(shown, onDismiss) }
                }
                // Room after the last item, so it scrolls clear of the screen's edge.
                Spacer(Modifier.height(48.dp))
            }
            // The selection goes to the first tile on opening, back to the section's tile on
            // leaving it, and into a section's first control on entering it (a group: its first).
            // A section with nothing to select («О Beam») would leave nothing selected, and keys
            // (Back included) would reach nothing: the page itself takes the selection then.
            LaunchedEffect(Unit) {
                repeat(10) {
                    withFrameNanos {}
                    val target = when {
                        shown != null -> contentFocus
                        lastPage != null -> pageFocus.getValue(lastPage!!)
                        else -> firstFocus
                    }
                    if (runCatching { target.requestFocus() }.isSuccess) {
                        if (shown != null) {
                            withFrameNanos {}
                            if (!contentHasFocus) runCatching { pageHold.requestFocus() }
                        }
                        return@LaunchedEffect
                    }
                }
            }
        }
    }
}

// XGIMI's quick panel, measured on the projector: 53dp in from the left and top, 350dp wide;
// tiles 80dp tall, 10dp apart, two wide or four square to a row.
private val PanelStart = 53.dp
private val PanelTop = 40.dp
private val PanelWidth = 350.dp
private val TileHeight = 80.dp
private val TileGap = 10.dp
private val TileShape = RoundedCornerShape(16.dp)

/** The panel: header with the gear, the pill to XGIMI's quick panel, sections, quick switches. */
@Composable
private fun QuickPanel(
    firstFocus: FocusRequester,
    pageFocus: Map<Page, FocusRequester>,
    onXgimi: (quick: Boolean) -> Unit,
    onPage: (Page) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Time, network and battery, as XGIMI's panel has them: the home screen's top bar steps
        // aside under this page, and over another app there is none.
        Box(Modifier.weight(1f)) { StatusHeader() }
        RoundTile(Icons.Outlined.Settings, tr(R.string.tile_all)) { onXgimi(false) }
    }
    Spacer(Modifier.height(18.dp))
    // Where XGIMI's panel has its brightness bar.
    WideTile(Icons.Outlined.Tune, tr(R.string.tile_quick), Modifier.fillMaxWidth().height(64.dp).focusRequester(firstFocus), chevron = true) { onXgimi(true) }
    Spacer(Modifier.height(TileGap + 6.dp))
    // Quick actions first, where XGIMI's panel has its own tiles. The theme and sounds follow XGIMI's
    // own settings, so no switches for them.
    Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
        MemoryTile(Modifier.weight(1f))
        FastModeTile(Modifier.weight(1f))
    }
    Spacer(Modifier.height(TileGap))
    // Then Beam Mod's sections, each saying how it is set: on the left how it looks, on the right
    // how it works.
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
        SectionTile(Page.Appearance, appearanceStatus(), pageFocus, onPage)
        SectionTile(Page.Home, tr(R.string.home_status), pageFocus, onPage)
    }
    Spacer(Modifier.height(TileGap))
    Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
        SectionTile(Page.Screensaver, remember { screensaverStatus(context) }, pageFocus, onPage)
        SectionTile(Page.Remote, remoteStatus(), pageFocus, onPage)
    }
    Spacer(Modifier.height(TileGap))
    // «О Beam Mod» wide, like the pill above: the version, or the update waiting.
    StatusTile(
        Page.About.icon, Page.About.title, aboutStatus(context),
        Modifier.fillMaxWidth().focusRequester(pageFocus.getValue(Page.About)).updateDot(),
        height = 64.dp,
    ) { onPage(Page.About) }
}

@Composable
private fun RowScope.SectionTile(page: Page, status: String, pageFocus: Map<Page, FocusRequester>, onPage: (Page) -> Unit) {
    StatusTile(page.icon, page.title, status, Modifier.weight(1f).focusRequester(pageFocus.getValue(page)), statusLines = 1) { onPage(page) }
}

/** «Как в XGIMI · Обычный»: the theme and the background. */
@Composable
private fun appearanceStatus(): String {
    val theme = when {
        LauncherSettings.followXgimi -> tr(R.string.theme_xgimi)
        LauncherSettings.dark -> tr(R.string.theme_dark)
        else -> tr(R.string.theme_light)
    }
    return theme + " · " + tr(Backgrounds[LauncherSettings.background].nameRes)
}

/** «Aerial Views · 10 мин»: the screensaver and when it starts. */
private fun screensaverStatus(context: android.content.Context): String {
    val current = Screensavers.current(context) ?: Screensavers.XGIMI
    val name = Screensavers.installed(context).firstOrNull { it.component == current }?.label ?: tr(R.string.screensaver_xgimi)
    val after = ScreensaverTimeout.current(context)
    val time = ScreensaverTimeout.options.firstOrNull { it.first == after }?.second
    return if (time != null) "$name · $time" else name
}

/** How many of the remote's app keys have an action of their own. */
@Composable
private fun remoteStatus(): String {
    val set = RemoteKeys.model.keys.count { RemoteKeys.actions.containsKey(it.code) }
    return if (set == 0) tr(R.string.action_firmware) else tr(R.string.remote_set, set)
}

private fun aboutStatus(context: android.content.Context): String =
    Updates.release?.let { tr(R.string.update_available, it.tag.removePrefix("v")) }
        ?: (tr(R.string.version) + " " + runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty())

/**
 * The panel's top, like XGIMI's: the time large, network and battery beside it, the date under
 * it. Always the panel's dark colours, whatever the launcher's theme.
 */
@Composable
private fun StatusHeader() {
    Column(Modifier.padding(start = 6.dp)) {
        // Sized to leave the gear its room even with the battery stand's charge in the row.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Clock(34.sp, ScrimText)
            Spacer(Modifier.width(12.dp))
            VpnIcon(ScrimText, 20.dp, 8.dp)
            NetworkIcon(ScrimText, 20.dp)
            Spacer(Modifier.width(8.dp))
            BatteryIndicator(16.sp, ScrimText, iconScale = 0.65f)
        }
        // On battery or the stand, how long the charge lasts follows the date (the top bar has no room).
        val left = batteryTimeLeft()
        T(if (left != null) "${today()} · $left" else today(), 14.sp, color = ScrimDim)
    }
}

/** Inside a section: the time, network and battery in one small line under its name. */
@Composable
private fun StatusLine() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Clock(14.sp, ScrimDim, weight = FontWeight.Normal)
        Spacer(Modifier.width(10.dp))
        VpnIcon(ScrimDim, 16.dp, 8.dp)
        NetworkIcon(ScrimDim, 16.dp)
        Spacer(Modifier.width(8.dp))
        BatteryIndicator(14.sp, ScrimDim, iconScale = 0.55f)
    }
}

/** "15 октября 2026 · Чт", as under the clock on XGIMI's panel. */
@Composable
private fun today(): String {
    val locale = AppLanguage.locale
    return remember(locale) {
        SimpleDateFormat("d MMMM yyyy · EE", locale).format(Date()).replaceFirstChar { it.titlecase(locale) }
            .let { s -> s.substringBeforeLast("· ") + "· " + s.substringAfterLast("· ").replaceFirstChar { it.titlecase(locale) } }
    }
}

/** A section's name large, and a line under it. */
@Composable
private fun Header(title: String, under: @Composable () -> Unit) {
    Column(Modifier.padding(start = 6.dp)) {
        T(title, 34.sp, color = ScrimText, weight = FontWeight.Light)
        under()
    }
}

/**
 * «Память»: free memory, updated while the panel is open; OK closes the apps idle in the background
 * and says how much that freed (see [Memory]).
 */
@Composable
private fun MemoryTile(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var free by remember { mutableLongStateOf(Memory.availableMb(context)) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            free = Memory.availableMb(context)
            delay(2_000)
        }
    }
    LaunchedEffect(note) {
        if (note == null) return@LaunchedEffect
        delay(3_000)
        note = null
    }
    val status = when {
        busy -> tr(R.string.memory_clearing)
        note != null -> note!!
        else -> tr(R.string.memory_free, tr(R.string.megabytes, free))
    }
    StatusTile(Icons.Outlined.Memory, tr(R.string.tile_memory), status, modifier) {
        if (busy) return@StatusTile
        busy = true
        scope.launch {
            val freed = Memory.clear(context)
            free = Memory.availableMb(context)
            busy = false
            note = if (freed >= 10) tr(R.string.memory_freed, tr(R.string.megabytes, freed)) else tr(R.string.memory_nothing)
        }
    }
}

/** «Быстрый режим»: window animations off or back on (see [FastMode]); on, the tile is filled. */
@Composable
private fun FastModeTile(modifier: Modifier) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(FastMode.on(context)) }
    var denied by remember { mutableStateOf(false) }
    val status = when {
        denied -> tr(R.string.fast_denied)
        on -> tr(R.string.fast_on)
        else -> tr(R.string.fast_off)
    }
    StatusTile(Icons.Outlined.Bolt, tr(R.string.tile_fast), status, modifier, on = on) {
        if (FastMode.set(context, !on)) on = !on else denied = true
    }
}

/**
 * A wide tile with a status line under its name, for the quick tiles. [on]: filled with the
 * accent, as XGIMI fills a switched-on tile.
 */
@Composable
private fun StatusTile(
    icon: ImageVector,
    title: String,
    status: String,
    modifier: Modifier,
    on: Boolean = false,
    height: Dp = TileHeight,
    statusLines: Int = 2,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val fg = when {
        focused -> FocusText
        on -> OnText
        else -> PanelText
    }
    val dim = when {
        focused -> FocusText.copy(alpha = 0.7f)
        on -> OnText.copy(alpha = 0.75f)
        else -> PanelDim
    }
    Row(
        modifier
            .height(height)
            .background(if (focused) FocusBg else if (on) Accent else CardBg, TileShape)
            .panelControl({ focused = it }, onClick)
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(icon, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(fg))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            T(title, 16.sp, color = fg, maxLines = 2)
            T(status, 14.sp, Modifier.padding(top = 2.dp), color = dim, maxLines = statusLines)
        }
    }
}

/** A tile with an icon and its name side by side, like XGIMI's wide tiles. Focused: white. */@Composable
private fun WideTile(icon: ImageVector, title: String, modifier: Modifier, chevron: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fg = if (focused) FocusText else PanelText
    Row(
        modifier
            .background(if (focused) FocusBg else CardBg, TileShape)
            .panelControl({ focused = it }, onClick)
            .padding(start = 16.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(icon, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(fg))
        Spacer(Modifier.width(12.dp))
        // Two lines break between words ("Главный / экран"), like XGIMI's "Bluetooth" tile.
        T(title, 16.sp, Modifier.weight(1f), color = fg, maxLines = 2)
        if (chevron) Image(Icons.Rounded.ChevronRight, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(fg))
    }
}

/**
 * A small square tile, like XGIMI's icon tiles, with a short caption under the icon (XGIMI's have
 * none, but its icons are its own). [off]: drawn dimmer.
 */
@Composable
private fun SquareTile(icon: ImageVector, caption: String, modifier: Modifier, off: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fg = when {
        focused -> FocusText
        off -> PanelDim
        else -> PanelText
    }
    Column(
        modifier
            .height(TileHeight)
            .background(if (focused) FocusBg else CardBg, TileShape)
            .panelControl({ focused = it }, onClick)
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(icon, caption, Modifier.size(26.dp), colorFilter = ColorFilter.tint(fg))
        Spacer(Modifier.height(5.dp))
        T(caption, 12.sp, color = fg)
    }
}

/** A round icon button, like the gear on XGIMI's panel. */
@Composable
private fun RoundTile(icon: ImageVector, description: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(48.dp)
            .background(if (focused) FocusBg else CardBg, CircleShape)
            .panelControl({ focused = it }, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(icon, description, Modifier.size(26.dp), colorFilter = ColorFilter.tint(if (focused) FocusText else PanelText))
    }
}
@Composable
private fun Content(section: Page, onDismiss: () -> Unit) {
    val context = LocalContext.current
    when (section) {
        Page.Appearance -> AppearancePage(onDismiss)
        Page.Home -> HomePage()
        Page.Remote -> RemoteButtonsPage()
        // XGIMI's screensaver is set up in its scenes app; others in their own app.
        Page.Screensaver -> ScreensaverPage(onSetup = { component ->
            onDismiss()
            context.launchPackage(if (component == Screensavers.XGIMI) Xgimi.SCREENSAVER_APP else component.packageName)
        })
        Page.About -> AboutBeamPage(onDismiss)
    }
}

/** The remote's voice key (F5 on the XGIMI remote, unused by the firmware) toggles the page. */
fun Modifier.panelKey(onToggle: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.nativeKeyEvent.keyCode != AndroidKeyEvent.KEYCODE_F5) return@onPreviewKeyEvent false
    // With the overlay service running it owns the key; a copy that slips through must not
    // open a second page.
    if (PanelOverlay.running) return@onPreviewKeyEvent true
    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) onToggle()
    true
}
