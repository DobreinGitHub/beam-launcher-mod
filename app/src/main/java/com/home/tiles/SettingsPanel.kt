package com.home.tiles

import android.content.Context
import androidx.annotation.StringRes
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.FilterCenterFocus
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsApplications
import androidx.compose.material.icons.rounded.SettingsInputHdmi
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Tonality
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sub-pages opened from the tile grid. */
private enum class PanelPage(@StringRes val titleRes: Int) {
    Picture(R.string.picture),
    Sound(R.string.sound),
    Appearance(R.string.appearance),
    Home(R.string.home_screen),
    Remote(R.string.remote_buttons),
    Xgimi(R.string.xgimi_settings),
    Bluetooth(R.string.bluetooth),
    Screensaver(R.string.screensaver),
    Power(R.string.power),
    Projection(R.string.projection),
    Keystone(R.string.kst_and_size);

    val title: String get() = tr(titleRes)
}

/** Our quick settings, styled after the Google TV panel; slides in from the right. */
@Composable
fun SettingsPanel(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            // Back first leaves a sub-page; PanelScreen decides.
            dismissOnBackPress = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        PanelScreen(onDismiss)
    }
}

/** The panel itself; hosted by [SettingsPanel] in the launcher and by [PanelOverlay] over other apps. */
@Composable
fun PanelScreen(onDismiss: () -> Unit) {
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    var page by remember { mutableStateOf<PanelPage?>(null) }
    // The tile that opened the current page gets focus back on return.
    var lastPage by remember { mutableStateOf<PanelPage?>(null) }
    val tileRequesters = remember { PanelPage.entries.associateWith { FocusRequester() } }
    val firstTile = remember { FocusRequester() }
    val pageFirst = remember { FocusRequester() }
    DisposableEffect(Unit) {
        PanelState.open = true
        onDispose { PanelState.open = false }
    }
    // Kept here, not in MainPage, so its tiles don't vanish and come back each time a sub-page is
    // left; re-read while the main page is showing, and so after a sub-page changed something.
    val context = LocalContext.current
    var main by remember { mutableStateOf<MainPageState?>(null) }
    LaunchedEffect(page) {
        while (page == null) {
            main = withContext(Dispatchers.IO) { MainPageState.read(context) }
            delay(MAIN_REFRESH_MS)
        }
    }
    LaunchedEffect(page) {
        val target = when {
            page != null -> pageFirst
            lastPage != null -> tileRequesters.getValue(lastPage!!)
            else -> firstTile
        }
        repeat(10) {
            withFrameNanos {}
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    fun back() {
        if (page != null) {
            lastPage = page
            page = null
        } else {
            onDismiss()
        }
    }
    // Back with nothing focused (after a touch) goes through the dialog's dispatcher, not key events.
    if (LocalOnBackPressedDispatcherOwner.current != null) BackHandler { back() }

    Box(
        Modifier
            .fillMaxSize()
            // No dimming while a corner is moved: the picture's edges must be seen.
            .background(if (KeystoneEdit.active.value != null) NoScrim else TvScrim)
            .arrowSoundTracker()
            .panelKey(onDismiss)
            // The overlay window has no back dispatcher, so Back is handled here for both hosts.
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.keyCode != AndroidKeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                // Back first ends moving a keystone corner.
                if (event.type == KeyEventType.KeyUp) {
                    if (KeystoneEdit.active.value != null) KeystoneEdit.active.value = null else back()
                }
                true
            }
            // Touch: a tap beside the panel closes it.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.CenterEnd,
    ) {
        AnimatedVisibility(
            visibleState = shown,
            enter = slideInHorizontally(tween(220)) { it } + fadeIn(tween(220)),
        ) {
            Column(
                Modifier
                    .padding(12.dp)
                    // Tiles the size of XGIMI's panel (352dp of tiles, 80dp squares).
                    .width(392.dp)
                    .fillMaxHeight()
                    // Taps on the panel itself must not reach the close-on-tap backdrop.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                val current = page
                if (current == null) {
                    MainPage(main, { main = main?.copy(eco = it) }, firstTile, tileRequesters, onDismiss) { lastPage = null; page = it }
                } else {
                    SubPage(current, pageFirst, onDismiss, ::back)
                }
            }
        }
    }
}

/** What the main page reads from the firmware; see [MainPage]. */
private data class MainPageState(
    val inputs: List<Xgimi.Input>,
    val eco: Boolean?,
    val soundOutput: String?,
    val pictureMode: String?,
    /** Name of the connected speaker/headphones, shown under the Bluetooth tile. */
    val bluetoothAudio: String?,
) {
    companion object {
        /** Blocking: binder calls into XGIMI's services, so off the main thread. */
        fun read(context: Context) = MainPageState(
            inputs = Xgimi.hdmiInputs(context),
            eco = Eco.enabled(),
            soundOutput = SoundOutput.output()?.let(::soundOutputName),
            pictureMode = PictureMode.current()?.let { mode -> Xgimi.pictureModes.firstOrNull { it.second == mode }?.first },
            bluetoothAudio = XgimiBluetooth.devices(context).firstOrNull { it.audio && it.connected }?.name,
        )
    }
}

/** How often the main page re-reads the firmware while it is open (an HDMI plug, a speaker connecting). */
private const val MAIN_REFRESH_MS = 5_000L

private class QuickItem(
    val icon: ImageVector,
    val label: String,
    val page: PanelPage? = null,
    val action: (() -> Unit)? = null,
    /** On/off tiles (eco mode) show their state; null for plain actions. */
    val active: Boolean? = null,
    /** A second line under the label, e.g. the current sound output. */
    val subtitle: String? = null,
    /** A double-width tile with its label, instead of a square icon tile. */
    val wide: Boolean = false,
)

@Composable
private fun ColumnScope.MainPage(
    state: MainPageState?,
    onEcoChanged: (Boolean?) -> Unit,
    firstTile: FocusRequester,
    tileRequesters: Map<PanelPage, FocusRequester>,
    onDismiss: () -> Unit,
    open: (PanelPage) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // What the firmware reports comes from PanelScreen (read off the main thread, refreshed while
    // this page is open); the tiles that depend on it appear once it is in.
    val inputs = state?.inputs.orEmpty()
    val eco = state?.eco
    val soundOutput = state?.soundOutput
    val bluetoothAudio = state?.bluetoothAudio
    val pictureMode = state?.pictureMode
    // Projector actions close the panel first so it doesn't cover the picture (keystone photographs it).
    fun projector(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }
    val items = buildList {
        // Everyday actions first as wide labelled tiles, then setup and settings as square
        // icon tiles that show their name only when focused (like XGIMI's own panel).
        add(QuickItem(Icons.Rounded.CenterFocusStrong, tr(R.string.autofocus), action = projector { Xgimi.autoFocus(context) }, wide = true))
        add(QuickItem(Icons.Rounded.CropFree, tr(R.string.keystone), action = projector { PanelIo.submit("auto-keystone") { Xgimi.autoKeystone(context) } }, wide = true))
        add(QuickItem(Icons.Rounded.Wifi, "Wi‑Fi", action = projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_WIFI) }, wide = true))
        add(QuickItem(Icons.Rounded.Bluetooth, "Bluetooth", PanelPage.Bluetooth, subtitle = bluetoothAudio, wide = true))
        add(QuickItem(Icons.Rounded.VolumeUp, tr(R.string.sound), PanelPage.Sound, subtitle = soundOutput, wide = true))
        add(QuickItem(Icons.Rounded.Tonality, tr(R.string.picture), PanelPage.Picture, subtitle = pictureMode, wide = true))
        // One HDMI port: switch straight to it; with several, number them.
        inputs.forEachIndexed { i, input ->
            val label = input.device ?: if (inputs.size == 1) "HDMI" else "HDMI ${i + 1}"
            add(QuickItem(Icons.Rounded.SettingsInputHdmi, label, action = projector { Xgimi.openInput(context, input) }))
        }
        eco?.let { on ->
            // Stays open: the change is visible behind the panel.
            add(QuickItem(Icons.Rounded.Eco, tr(R.string.eco_mode), active = on, action = {
                scope.launch(Dispatchers.IO) { if (Eco.set(!on)) onEcoChanged(Eco.enabled()) }
            }))
        }
        add(QuickItem(Icons.Rounded.Landscape, tr(R.string.screensaver), PanelPage.Screensaver))
        add(QuickItem(Icons.Rounded.PowerSettingsNew, tr(R.string.power), PanelPage.Power, active = SleepTimer.endsAt.longValue > 0))
        add(QuickItem(Icons.Rounded.FilterCenterFocus, tr(R.string.manual_focus), action = projector { Xgimi.manualFocus(context) }))
        add(QuickItem(Icons.Rounded.Crop, tr(R.string.kst_and_size), PanelPage.Keystone))
        add(QuickItem(Icons.Rounded.ScreenRotation, tr(R.string.projection), PanelPage.Projection))
        add(QuickItem(Icons.Rounded.Palette, tr(R.string.appearance), PanelPage.Appearance))
        add(QuickItem(Icons.Rounded.Dashboard, tr(R.string.home_screen), PanelPage.Home))
        add(QuickItem(Icons.Rounded.SettingsRemote, tr(R.string.remote_buttons), PanelPage.Remote))
        add(QuickItem(Icons.Rounded.SettingsApplications, "XGIMI", PanelPage.Xgimi))
    }

    // Every tile fits on one screen: a 4-unit grid of wide (2 units) and square (1 unit) tiles.
    val units = 4
    val gap = 10.dp
    fun span(item: QuickItem) = if (item.wide) 2 else 1
    val rows = buildList {
        var row = mutableListOf<QuickItem>()
        for (item in items) {
            if (row.sumOf(::span) + span(item) > units) {
                add(row)
                row = mutableListOf()
            }
            row += item
        }
        if (row.isNotEmpty()) add(row)
    }
    PanelHeader(onSettings = projector { context.openSettings() })
    Spacer(Modifier.height(12.dp))
    BrightnessSlider(Modifier.fillMaxWidth().height(48.dp))
    Spacer(Modifier.height(10.dp))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val unit = (maxWidth - gap * (units - 1)) / units
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            rows.forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEachIndexed { c, item ->
                        val requester = when {
                            r == 0 && c == 0 -> firstTile
                            item.page != null -> tileRequesters.getValue(item.page)
                            else -> null
                        }
                        // A double tile also covers the gap it spans, so columns line up.
                        val width = unit * span(item) + gap * (span(item) - 1)
                        val modifier = Modifier.width(width)
                            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                        val onClick: () -> Unit = {
                            val target = item.page
                            if (target != null) open(target) else item.action?.invoke()
                        }
                        if (item.wide) WideTile(item, modifier, onClick) else IconTile(item, modifier, onClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(onSettings: () -> Unit) {
    val timeFormat = remember(AppLanguage.locale) { SimpleDateFormat("HH:mm", AppLanguage.locale) }
    val dateFormat = remember(AppLanguage.locale) { SimpleDateFormat("EE, d MMMM", AppLanguage.locale) }
    // Minute resolution: every change redraws this full-screen window over the video.
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(dateFormat.format(now), 16.sp, color = PanelDim)
            T(timeFormat.format(now), 36.sp, color = PanelText, weight = FontWeight.Medium)
        }
        BatteryIndicator(20.sp, PanelText)
        Spacer(Modifier.width(8.dp))
        RoundIcon(Icons.Rounded.Settings, onSettings)
    }
}

private fun tileText(focused: Boolean, on: Boolean) = when {
    focused -> FocusText
    on -> OnText
    else -> PanelText
}

private fun tileColor(focused: Boolean, on: Boolean) = when {
    focused -> FocusBg
    on -> Accent
    else -> CardBg
}

private val TileHeight = 80.dp

/** Projector: double-width tile with icon, label and optional state (sound output). */
@Composable
private fun WideTile(item: QuickItem, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val on = item.active == true
    val fg = tileText(focused, on)
    val status = item.active?.let { if (it) tr(R.string.on_dot) else tr(R.string.off_dot) } ?: item.subtitle
    Row(
        modifier
            .height(TileHeight)
            .background(tileColor(focused, on), RoundedCornerShape(16.dp))
            .panelControl({ focused = it }, onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(item.icon, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(fg))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            T(item.label, 16.sp, color = fg)
            status?.let { T(it, 12.sp, color = if (focused || on) fg else PanelDim) }
        }
    }
}

/**
 * Projector: square icon-only tile. When focused the icon slides up and the name scrolls in
 * underneath, like the small buttons in XGIMI's panel. An "on" state fills the tile.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IconTile(item: QuickItem, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val on = item.active == true
    val fg = tileText(focused, on)
    val lift by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "lift")
    Box(
        modifier
            .height(TileHeight)
            .background(tileColor(focused, on), RoundedCornerShape(16.dp))
            .panelControl({ focused = it }, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            item.icon,
            item.label,
            Modifier
                .size(28.dp)
                .graphicsLayer { translationY = -12.dp.toPx() * lift },
            colorFilter = ColorFilter.tint(fg),
        )
        if (focused) {
            BasicText(
                item.label,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                    .graphicsLayer { alpha = lift }
                    // Twice, then it rests: every marquee frame makes the system re-blend the
                    // full-screen overlay with the video (measured ~40% CPU while it runs).
                    .basicMarquee(iterations = 2, initialDelayMillis = 700),
                style = TextStyle(color = fg, fontSize = 12.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(48.dp)
            .background(if (focused) FocusBg else Color.Transparent, CircleShape)
            .panelControl({ focused = it }, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(icon, null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(if (focused) FocusText else PanelText))
    }
}

@Composable
private fun ColumnScope.SubPage(page: PanelPage, first: FocusRequester, onDismiss: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    fun projector(action: () -> Unit) {
        onDismiss()
        action()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.focusRequester(first)) { RoundIcon(Icons.Rounded.ArrowBack, onBack) }
        Spacer(Modifier.width(8.dp))
        T(page.title, 24.sp, color = PanelText)
    }
    Spacer(Modifier.height(8.dp))
    when (page) {
        PanelPage.Picture -> PicturePage(onXgimiPage = { projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_PICTURE) } })
        PanelPage.Sound -> {
            Section(tr(R.string.volume))
            VolumeSlider(Modifier.fillMaxWidth())
            if (SoundOutput.available) SoundOutputSection()
            SoundModeSection()
            EarcToggle()
            Section(tr(R.string.interface_section))
            Toggle(tr(R.string.nav_sounds), LauncherSettings.sounds, Modifier.fillMaxWidth()) {
                LauncherSettings.sounds = !LauncherSettings.sounds
            }
            if (ScreensaverTimeout.canWrite(context)) {
                var keyTones by remember { mutableStateOf(KeyTones.enabled(context)) }
                Spacer(Modifier.height(10.dp))
                Toggle(tr(R.string.system_click_sound), keyTones, Modifier.fillMaxWidth()) {
                    if (KeyTones.set(context, !keyTones)) keyTones = KeyTones.enabled(context)
                }
            }
            val bootMusic = rememberFirmwareState { BootMusic.enabled() }
            bootMusic.value?.let { on ->
                Spacer(Modifier.height(10.dp))
                Toggle(tr(R.string.power_on_chime), on, Modifier.fillMaxWidth()) {
                    bootMusic.change(!on) { BootMusic.set(!on) }
                }
            }
        }
        PanelPage.Appearance -> AppearancePage()
        PanelPage.Home -> HomePage(onHdmiPage = { projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_HDMI) } })
        PanelPage.Keystone -> KeystonePage(onScreen = {
            projector {
                context.startActivity(
                    android.content.Intent(context, KeystoneActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        })
        PanelPage.Projection -> ProjectionPage(onRotatePage = { projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_ROTATE) } })
        PanelPage.Power -> PowerPage(
            onOff = { projector { Power.off(context) } },
            onXgimiMenu = { projector { Xgimi.powerMenu(context) } },
        )
        // XGIMI's screensaver is set up in its scenes app; others in their own app.
        PanelPage.Screensaver -> ScreensaverPage(onSetup = { component ->
            projector {
                context.launchPackage(if (component == Screensavers.XGIMI) Xgimi.SCREENSAVER_APP else component.packageName)
            }
        })
        PanelPage.Remote -> RemoteButtonsSection()
        PanelPage.Bluetooth -> BluetoothPage(onXgimiPage = { projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_BLUETOOTH) } })
        PanelPage.Xgimi -> {
            SensorToggles()
            Section(tr(R.string.projector_sections))
            ListRow(tr(R.string.correction_focus_reset)) { projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_CORRECTION) } }
            ListRow(tr(R.string.sound_output_page)) { projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_SOUND_OUTPUT) } }
            ListRow(tr(R.string.all_settings)) { projector { context.openSettings() } }
            AboutSection()
        }
    }
}

/** The remote's voice key (F5 on the XGIMI remote, unused by the firmware) toggles the panel. */
fun Modifier.panelKey(onToggle: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val code = event.nativeKeyEvent.keyCode
    val settingsKey = code == PanelOverlay.SETTINGS_KEY && LauncherSettings.settingsKeyPanel
    if (code != AndroidKeyEvent.KEYCODE_F5 && !settingsKey) return@onPreviewKeyEvent false
    // With the overlay service running it owns the key; a copy that slips through must not
    // open a second panel.
    if (PanelOverlay.running) return@onPreviewKeyEvent true
    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) onToggle()
    true
}
