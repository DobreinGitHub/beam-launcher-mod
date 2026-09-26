package com.home.tiles

import android.media.AudioManager
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Details
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.material.icons.rounded.VolumeOff
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// XGIMI's own panel: no sheet over the evenly dimmed picture, see-through grey tiles,
// saturated blue focus with white text.
private val CardBg = Color(0x38FFFFFF)
private val FocusBg = Color(0xFF3B7CF5)
private val FocusText = Color.White
/** Filled part of a focused slider or switch, drawn on [FocusBg]. */
private val FocusFill = Color.White
private val Accent = Color(0xFF8AB4F8)
/** Text on a tile that is switched on (light [Accent] fill). */
private val OnText = Color(0xFF0B1D36)
private val PanelText = Color(0xFFE8EAED)
private val PanelDim = Color(0xFFA8ACB3)
/**
 * Projector: like XGIMI's panel, darkest along the panel's edge and fading across the picture
 * (theirs is on the left, ours on the right).
 */
private val TvScrim = Brush.horizontalGradient(
    0f to Color(0x26000000),
    0.5f to Color(0x8C000000),
    1f to Color(0xD9000000),
)

/** Sub-pages opened from the tile grid. */
private enum class PanelPage(val title: String) {
    Picture("Изображение"),
    Sound("Звук"),
    Appearance("Оформление"),
    Home("Главный экран"),
    Remote("Кнопки пульта"),
    Xgimi("Настройки XGIMI"),
    Bluetooth("Bluetooth"),
    Screensaver("Заставка"),
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
    LaunchedEffect(Unit) { Sounds.popup() }
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
            .background(TvScrim)
            .arrowSoundTracker()
            .panelKey(onDismiss)
            // The overlay window has no back dispatcher, so Back is handled here for both hosts.
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.keyCode != AndroidKeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) back()
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
                    MainPage(firstTile, tileRequesters, onDismiss) { lastPage = null; page = it }
                } else {
                    SubPage(current, pageFirst, onDismiss, ::back)
                }
            }
        }
    }
}

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
    firstTile: FocusRequester,
    tileRequesters: Map<PanelPage, FocusRequester>,
    onDismiss: () -> Unit,
    open: (PanelPage) -> Unit,
) {
    val context = LocalContext.current
    val inputs = remember { Xgimi.hdmiInputs(context) }
    var eco by remember { mutableStateOf(Eco.enabled()) }
    val soundOutput = remember { SoundOutput.output()?.let(::soundOutputName) }
    // Name of the connected speaker/headphones, shown under the Bluetooth tile.
    val bluetoothAudio by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) { XgimiBluetooth.devices(context).firstOrNull { it.audio && it.connected }?.name }
    }
    val pictureMode = remember {
        PictureMode.current()?.let { mode -> Xgimi.pictureModes.firstOrNull { it.second == mode }?.first }
    }
    // Projector actions close the panel first so it doesn't cover the picture (keystone photographs it).
    fun projector(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }
    val items = buildList {
        // Everyday actions first as wide labelled tiles, then setup and settings as square
        // icon tiles that show their name only when focused (like XGIMI's own panel).
        add(QuickItem(Icons.Rounded.CenterFocusStrong, "Автофокус", action = projector { Xgimi.autoFocus(context) }, wide = true))
        add(QuickItem(Icons.Rounded.CropFree, "Трапеция", action = projector { Xgimi.autoKeystone(context) }, wide = true))
        add(QuickItem(Icons.Rounded.Wifi, "Wi‑Fi", action = projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_WIFI) }, wide = true))
        add(QuickItem(Icons.Rounded.Bluetooth, "Bluetooth", PanelPage.Bluetooth, subtitle = bluetoothAudio, wide = true))
        add(QuickItem(Icons.Rounded.VolumeUp, "Звук", PanelPage.Sound, subtitle = soundOutput, wide = true))
        add(QuickItem(Icons.Rounded.Tonality, "Изображение", PanelPage.Picture, subtitle = pictureMode, wide = true))
        // One HDMI port: switch straight to it; with several, number them.
        inputs.forEachIndexed { i, input ->
            val label = input.device ?: if (inputs.size == 1) "HDMI" else "HDMI ${i + 1}"
            add(QuickItem(Icons.Rounded.SettingsInputHdmi, label, action = projector { Xgimi.openInput(context, input) }))
        }
        eco?.let { on ->
            // Stays open: the change is visible behind the panel.
            add(QuickItem(Icons.Rounded.Eco, "Эко-режим", active = on, action = { if (Eco.set(!on)) eco = Eco.enabled() }))
        }
        add(QuickItem(Icons.Rounded.Landscape, "Заставка", PanelPage.Screensaver))
        add(QuickItem(Icons.Rounded.PowerSettingsNew, "Питание", action = projector { Xgimi.powerMenu(context) }))
        add(QuickItem(Icons.Rounded.FilterCenterFocus, "Ручной фокус", action = projector { Xgimi.manualFocus(context) }))
        add(QuickItem(Icons.Rounded.Crop, "Ручная трапеция", action = projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_KEYSTONE) }))
        add(QuickItem(Icons.Rounded.ZoomOutMap, "Зум и сдвиг", action = projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_ZOOM) }))
        add(QuickItem(Icons.Rounded.ScreenRotation, "Поворот экрана", action = projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_ROTATE) }))
        add(QuickItem(Icons.Rounded.Palette, "Оформление", PanelPage.Appearance))
        add(QuickItem(Icons.Rounded.Dashboard, "Главный экран", PanelPage.Home))
        add(QuickItem(Icons.Rounded.SettingsRemote, "Кнопки пульта", PanelPage.Remote))
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
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("EE, d MMMM", Locale("ru")) }
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(1000 - System.currentTimeMillis() % 1000)
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
    val status = item.active?.let { if (it) "Вкл." else "Выкл." } ?: item.subtitle
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
            Section("Громкость")
            VolumeSlider(Modifier.fillMaxWidth())
            if (SoundOutput.available) SoundOutputSection()
            SoundModeSection()
            EarcToggle()
            Section("Интерфейс")
            Toggle("Звуки навигации", LauncherSettings.sounds, Modifier.fillMaxWidth()) {
                LauncherSettings.sounds = !LauncherSettings.sounds
            }
            if (ScreensaverTimeout.canWrite(context)) {
                var keyTones by remember { mutableStateOf(KeyTones.enabled(context)) }
                Spacer(Modifier.height(10.dp))
                Toggle("Системный звук нажатий", keyTones, Modifier.fillMaxWidth()) {
                    if (KeyTones.set(context, !keyTones)) keyTones = KeyTones.enabled(context)
                }
            }
        }
        PanelPage.Appearance -> AppearancePage()
        PanelPage.Home -> HomePage(onHdmiPage = { projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_HDMI) } })
        PanelPage.Screensaver -> ScreensaverPage(onScenes = { projector { context.launchPackage(Xgimi.SCREENSAVER_APP) } })
        PanelPage.Remote -> RemoteButtonsSection()
        PanelPage.Bluetooth -> BluetoothPage(onXgimiPage = { projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_BLUETOOTH) } })
        PanelPage.Xgimi -> {
            SensorToggles()
            Section("Разделы настроек проектора")
            ListRow("Коррекция, фокус, сброс") { projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_CORRECTION) } }
            ListRow("Звуковой выход") { projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_SOUND_OUTPUT) } }
            ListRow("Все настройки") { projector { context.openSettings() } }
        }
    }
}

/**
 * Picture modes with the current one ticked. The panel stays open so the change can be judged
 * against the picture behind it. Performance asks first, like XGIMI does (heat warning).
 */
@Composable
private fun PicturePage(onXgimiPage: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(PictureMode.current()) }
    LaunchedEffect(Unit) { XgimiService.bind(context) }
    var confirmPerformance by remember { mutableStateOf(false) }
    fun apply(mode: Int) {
        Xgimi.setPictureMode(context, mode)
        current = mode
        // The firmware switches asynchronously; read back what it actually applied.
        scope.launch {
            delay(1500)
            PictureMode.current()?.let { current = it }
        }
    }
    Section("Режим изображения")
    Xgimi.pictureModes.forEach { (label, mode) ->
        Chip(label, current == mode, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            if (mode == Xgimi.PICTURE_PERFORMANCE && current != mode) confirmPerformance = true else apply(mode)
        }
        if (mode == Xgimi.PICTURE_PERFORMANCE && confirmPerformance) {
            PerformanceWarning(
                onConfirm = {
                    confirmPerformance = false
                    apply(mode)
                },
                onCancel = { confirmPerformance = false },
            )
        }
    }
    GameModeSection()
    Section("Пользовательский режим")
    if (current == CUSTOM_PICTURE) {
        CustomPictureControls()
    } else {
        // XGIMI keeps these values per mode and only saves them in the custom one.
        ListRow("Перейти в пользовательский режим") { apply(CUSTOM_PICTURE) }
    }
    Section("Ещё")
    ListRow("Настройки AI и режимов XGIMI", onXgimiPage)
}

private val GameModes = listOf(GameMode.AUTO to "Авто", GameMode.ON to "Вкл", GameMode.OFF to "Выкл")
private val GameLevels = listOf("Базовый", "Максимальный")

/** XGIMI's game mode: lower input lag for consoles; it only takes effect with an HDMI signal. */
@Composable
private fun GameModeSection() {
    val context = LocalContext.current
    var state by remember { mutableStateOf(GameMode.read()) }
    var level by remember { mutableStateOf(GameMode.level(context)) }
    val current = state ?: return
    Section("Игровой режим · для HDMI")
    val index = GameModes.indexOfFirst { it.first == current.mode }.coerceAtLeast(0)
    Selector("Режим", GameModes[index].second, Modifier.fillMaxWidth().padding(bottom = 8.dp)) { delta ->
        val mode = GameModes[(index + delta).mod(GameModes.size)].first
        GameMode.setMode(mode)
        state = GameMode.read() ?: GameMode.State(mode)
    }
    // The level (basic / top speed) applies when game mode is forced on.
    if (current.mode == GameMode.ON) {
        Selector("Уровень", GameLevels[level], Modifier.fillMaxWidth().padding(bottom = 8.dp)) { delta ->
            level = (level + delta).mod(GameLevels.size)
            GameMode.setLevel(context, level)
        }
    }
}

private const val CUSTOM_PICTURE = 3

/** Everything XGIMI's custom picture mode page offers, read once XGIMI's service is bound. */
private data class CustomPicture(
    val items: Map<Int, Int>,
    val colorTemp: Int,
    val noise: Int,
    val motion: Int,
    val gamma: Int,
    val dynamicContrast: Boolean,
    val localContrast: Int,
    val hdr: Boolean,
)

private fun readCustomPicture(): CustomPicture? {
    val items = listOf(PictureAdjust.BRIGHTNESS, PictureAdjust.CONTRAST, PictureAdjust.SATURATION, PictureAdjust.SHARPNESS)
        .associateWith { PictureAdjust.get(it) ?: return null }
    return CustomPicture(
        items,
        PictureAdjust.colorTemp() ?: return null,
        PictureAdjust.noiseReduction() ?: return null,
        PictureAdjust.motion() ?: return null,
        PictureAdjust.gamma() ?: return null,
        PictureAdjust.dynamicContrast() ?: return null,
        PictureAdjust.localContrast() ?: return null,
        PictureAdjust.hdr() ?: return null,
    )
}

private val NoiseLevels = listOf("Выкл", "Низкое", "Среднее", "Высокое", "Авто")
private val MotionLevels = listOf("Выкл", "Слабая", "Средняя", "Сильная")
private val LocalContrastLevels = listOf("Выкл", "Низкий", "Средний", "Высокий")
private val GammaLevels = listOf("1.8", "1.9", "2.0", "2.1", "2.2", "2.3", "2.4", "2.5", "2.6")

/** XGIMI's defaults for the custom mode, as the projector came. */
private val CustomDefaults = CustomPicture(
    items = mapOf(PictureAdjust.BRIGHTNESS to 50, PictureAdjust.CONTRAST to 50, PictureAdjust.SATURATION to 50, PictureAdjust.SHARPNESS to 50),
    colorTemp = 1,
    noise = 2,
    motion = 3,
    gamma = 4,
    dynamicContrast = true,
    localContrast = 2,
    hdr = true,
)

/** The custom picture mode's settings, laid out like XGIMI's page (basic, then advanced). */
@Composable
private fun CustomPictureControls() {
    val context = LocalContext.current
    // XGIMI's service binds asynchronously on first use, so poll briefly for the values.
    val loaded by produceState<CustomPicture?>(null) {
        XgimiService.bind(context)
        repeat(20) {
            readCustomPicture()?.let {
                value = it
                return@produceState
            }
            delay(250)
        }
    }
    val initial = loaded
    if (initial == null) {
        T("Загрузка…", 14.sp, color = PanelDim)
        return
    }
    var values by remember(initial) { mutableStateOf(initial) }
    fun update(apply: () -> Unit, next: CustomPicture) {
        apply()
        values = next
    }
    val sliders = listOf(
        Triple(PictureAdjust.BRIGHTNESS, "Яркость", Icons.Rounded.WbSunny),
        Triple(PictureAdjust.CONTRAST, "Контраст", Icons.Rounded.Contrast),
        Triple(PictureAdjust.SATURATION, "Насыщенн.", Icons.Rounded.WaterDrop),
        Triple(PictureAdjust.SHARPNESS, "Резкость", Icons.Rounded.Details),
    )
    sliders.forEach { (item, label, icon) ->
        LevelSlider(icon, values.items.getValue(item), 100, Modifier.fillMaxWidth().padding(bottom = 8.dp), label) {
            update({ PictureAdjust.set(item, it) }, values.copy(items = values.items + (item to it)))
        }
    }
    Selector("Шумоподавление", NoiseLevels.getOrElse(values.noise) { "?" }, Modifier.fillMaxWidth().padding(bottom = 8.dp)) { delta ->
        val next = (values.noise + delta).mod(NoiseLevels.size)
        update({ PictureAdjust.setNoiseReduction(next) }, values.copy(noise = next))
    }
    T("Цветовая температура", 14.sp, color = PanelDim)
    Spacer(Modifier.height(8.dp))
    PairRow {
        listOf("Холодная" to 0, "Станд." to 1, "Тёплая" to 2).forEach { (label, temp) ->
            Chip(label, values.colorTemp == temp, Modifier.weight(1f)) {
                update({ PictureAdjust.setColorTemp(temp) }, values.copy(colorTemp = temp))
            }
        }
    }

    Section("Расширенные")
    Selector("Плавность (MEMC)", MotionLevels.getOrElse(values.motion) { "?" }, Modifier.fillMaxWidth().padding(bottom = 8.dp)) { delta ->
        val next = (values.motion + delta).mod(MotionLevels.size)
        update({ PictureAdjust.setMotion(next) }, values.copy(motion = next))
    }
    Selector("Гамма", GammaLevels.getOrElse(values.gamma) { "?" }, Modifier.fillMaxWidth().padding(bottom = 8.dp)) { delta ->
        val next = (values.gamma + delta).coerceIn(0, GammaLevels.lastIndex)
        update({ PictureAdjust.setGamma(next) }, values.copy(gamma = next))
    }
    Toggle("Динамический контраст", values.dynamicContrast, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        val next = !values.dynamicContrast
        update({ PictureAdjust.setDynamicContrast(next) }, values.copy(dynamicContrast = next))
    }
    Toggle("HDR (авто)", values.hdr, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        val next = !values.hdr
        update({ PictureAdjust.setHdr(next) }, values.copy(hdr = next))
    }
    Selector("Локальный контраст", LocalContrastLevels.getOrElse(values.localContrast) { "?" }, Modifier.fillMaxWidth().padding(bottom = 8.dp)) { delta ->
        val next = (values.localContrast + delta).mod(LocalContrastLevels.size)
        update({ PictureAdjust.setLocalContrast(next) }, values.copy(localContrast = next))
    }
    ListRow("Сбросить по умолчанию") {
        val d = CustomDefaults
        update({
            d.items.forEach { (item, v) -> PictureAdjust.set(item, v) }
            PictureAdjust.setColorTemp(d.colorTemp)
            PictureAdjust.setNoiseReduction(d.noise)
            PictureAdjust.setMotion(d.motion)
            PictureAdjust.setGamma(d.gamma)
            PictureAdjust.setDynamicContrast(d.dynamicContrast)
            PictureAdjust.setLocalContrast(d.localContrast)
            PictureAdjust.setHdr(d.hdr)
        }, d)
    }
}

@Composable
private fun PerformanceWarning(onConfirm: () -> Unit, onCancel: () -> Unit) {
    val confirm = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { confirm.requestFocus() }
    }
    Column(
        Modifier
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .border(1.dp, Color(0x66FFB74D), RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        T("Режим производительности", 16.sp, color = PanelText, weight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        BasicText(
            "Максимальная яркость. Вентиляция не должна быть закрыта, в комнате — не выше 25 °C. " +
                "Долгое использование может перегреть проектор и сократить срок службы.",
            style = TextStyle(color = PanelDim, fontSize = 13.sp),
        )
        Spacer(Modifier.height(10.dp))
        PairRow {
            Chip("Включить", false, Modifier.weight(1f).focusRequester(confirm), onClick = onConfirm)
            Chip("Отмена", false, Modifier.weight(1f), onClick = onCancel)
        }
    }
}

/**
 * Paired Bluetooth devices; a click connects or disconnects one (e.g. switching sound between a
 * speaker and the projector). Refreshed every two seconds while open, since connecting takes a
 * moment and XGIMI's calls don't report the outcome.
 */
@Composable
private fun BluetoothPage(onXgimiPage: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<XgimiBluetooth.Device>?>(null) }
    // Addresses we just asked to (dis)connect, shown as "…" until the state changes.
    var pending by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    LaunchedEffect(Unit) {
        while (true) {
            val list = withContext(Dispatchers.IO) { XgimiBluetooth.devices(context) }
            devices = list
            pending = pending.filter { (address, wantConnected) ->
                list.firstOrNull { it.address == address }?.connected != wantConnected
            }
            delay(2000)
        }
    }
    val list = devices
    Section("Устройства")
    when {
        list == null -> T("Загрузка…", 14.sp, color = PanelDim)
        list.none { !it.remote } -> T("Нет сопряжённых устройств", 14.sp, color = PanelDim)
        else -> list.filter { !it.remote }.forEach { device ->
            val waiting = device.address in pending
            val state = when {
                waiting && pending.getValue(device.address) -> "Подключение…"
                waiting -> "Отключение…"
                device.connecting -> "Подключение…"
                device.connected -> "Подключено"
                else -> "Не подключено"
            }
            Chip(
                device.name.ifBlank { device.address },
                device.connected,
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                note = if (device.audio) "$state · колонка/наушники" else state,
            ) {
                if (waiting) return@Chip
                val connect = !device.connected
                pending = pending + (device.address to connect)
                scope.launch(Dispatchers.IO) {
                    if (connect) XgimiBluetooth.connect(context, device) else XgimiBluetooth.disconnect(context, device)
                }
            }
        }
    }
    var visible by remember { mutableStateOf(BluetoothOptions.discoverable(context)) }
    var absolute by remember { mutableStateOf(BluetoothOptions.absoluteVolume()) }
    Section("Настройки")
    Toggle("Видимость для других устройств", visible, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        BluetoothOptions.setDiscoverable(context, !visible)
        visible = BluetoothOptions.discoverable(context)
    }
    Toggle("Абсолютная громкость", absolute, Modifier.fillMaxWidth()) {
        BluetoothOptions.setAbsoluteVolume(context, !absolute)
        absolute = BluetoothOptions.absoluteVolume()
    }
    Section("Ещё")
    ListRow("Добавить устройство", onXgimiPage)
}

/** XGIMI's sensor switches: keystone when moved, refocus on tilt, eye protection. */
@Composable
private fun SensorToggles() {
    var realtime by remember { mutableStateOf(Sensors.realtimeKeystone()) }
    var motionFocus by remember { mutableStateOf(Sensors.motionFocus()) }
    var eyes by remember { mutableStateOf(Sensors.eyeProtection()) }
    var bootKeystone by remember { mutableStateOf(Sensors.bootKeystone()) }
    if (realtime == null && motionFocus == null && eyes == null && bootKeystone == null) return
    Section("Датчики")
    bootKeystone?.let { on ->
        Toggle("Коррекция при включении", on, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Sensors.setBootKeystone(!on)
            bootKeystone = Sensors.bootKeystone() ?: !on
        }
    }
    realtime?.let { on ->
        Toggle("Коррекция при сдвиге", on, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Sensors.setRealtimeKeystone(!on)
            realtime = Sensors.realtimeKeystone() ?: !on
        }
    }
    motionFocus?.let { on ->
        Toggle("Автофокус при сдвиге", on, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Sensors.setMotionFocus(!on)
            motionFocus = Sensors.motionFocus() ?: !on
        }
    }
    eyes?.let { on ->
        Toggle("Защита глаз", on, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Sensors.setEyeProtection(!on)
            eyes = Sensors.eyeProtection() ?: !on
        }
    }
}

/** eARC for an HDMI 2.1 sound system (XGIMI's "eARC mode": Auto / Off). */
@Composable
private fun EarcToggle() {
    var earc by remember { mutableStateOf(Earc.enabled()) }
    earc?.let { on ->
        Section("HDMI")
        Toggle("eARC", on, Modifier.fillMaxWidth()) {
            Earc.set(!on)
            earc = Earc.enabled() ?: !on
        }
    }
}

/** XGIMI's sound modes: AI, movie, music, sport, karaoke. */
@Composable
private fun SoundModeSection() {
    var current by remember { mutableStateOf(SoundMode.current()) }
    if (current == null) return
    Section("Звуковой режим")
    SoundMode.modes.chunked(2).forEach { pair ->
        PairRow {
            pair.forEach { (mode, label) ->
                Chip(label, current == mode, Modifier.weight(1f)) {
                    SoundMode.set(mode)
                    current = SoundMode.current() ?: mode
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun AppearancePage() {
    val dark = LauncherSettings.dark
    Section("Тема")
    PairRow {
        Chip("Светлая", !dark, Modifier.weight(1f)) { LauncherSettings.dark = false }
        Chip("Тёмная", dark, Modifier.weight(1f)) { LauncherSettings.dark = true }
    }
    Section("Фон · ${Backgrounds[LauncherSettings.background].name}")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Backgrounds.forEachIndexed { i, preset ->
            Swatch(Colors.presetBrush(preset), LauncherSettings.background == i) { LauncherSettings.background = i }
        }
    }
    if (Colors.isXmb) XmbOptions()
    Section("Раскладка")
    PairRow {
        Chip("Крупная плитка", LauncherSettings.layout == LAYOUT_FOCUS, Modifier.weight(1f)) {
            LauncherSettings.layout = LAYOUT_FOCUS
        }
        Chip("Как на Switch", LauncherSettings.layout == LAYOUT_CLASSIC, Modifier.weight(1f)) {
            LauncherSettings.layout = LAYOUT_CLASSIC
        }
    }
    Section("Размер плиток")
    PairRow {
        Chip("Обычные", !LauncherSettings.largeTiles, Modifier.weight(1f)) { LauncherSettings.largeTiles = false }
        Chip("Крупные", LauncherSettings.largeTiles, Modifier.weight(1f)) { LauncherSettings.largeTiles = true }
    }
}

/**
 * XGIMI's screensaver: how long the projector waits before starting it, and its "Any Door"
 * scenes app, where the scene itself is chosen.
 */
@Composable
private fun ScreensaverPage(onScenes: () -> Unit) {
    val context = LocalContext.current
    var timeout by remember { mutableStateOf(ScreensaverTimeout.current(context)) }
    val options = ScreensaverTimeout.options
    Section("Включать через")
    if (ScreensaverTimeout.canWrite(context)) {
        // Unknown values (set elsewhere) show as the nearest longer option.
        val index = options.indices.filter { options[it].first >= timeout }.minBy { options[it].first }
        Selector("Бездействие", options[index].second, Modifier.fillMaxWidth()) { step ->
            val next = options[(index + step).coerceIn(0, options.lastIndex)].first
            if (ScreensaverTimeout.set(context, next)) timeout = ScreensaverTimeout.current(context)
        }
    } else {
        T("Нет разрешения менять время (appops WRITE_SETTINGS)", 14.sp, color = PanelDim)
    }
    Section("Сцены")
    ListRow("Выбрать заставку", onScenes)
}

/** Where the projector starts, the firmware's HDMI auto switch, and a link to its HDMI page (CEC). */
@Composable
private fun HdmiSection(onHdmiPage: () -> Unit) {
    var autoSwitch by remember { mutableStateOf(Hdmi.autoSwitch()) }
    Section("При включении")
    PairRow {
        Chip("Главный экран", LauncherSettings.bootSource == Hdmi.BOOT_HOME, Modifier.weight(1f)) {
            LauncherSettings.bootSource = Hdmi.BOOT_HOME
        }
        Chip("HDMI", LauncherSettings.bootSource == Hdmi.BOOT_HDMI, Modifier.weight(1f), note = "если подключено") {
            LauncherSettings.bootSource = Hdmi.BOOT_HDMI
        }
    }
    autoSwitch?.let { on ->
        Spacer(Modifier.height(10.dp))
        Toggle("HDMI при подключении", on, Modifier.fillMaxWidth()) {
            Hdmi.setAutoSwitch(!on)
            autoSwitch = Hdmi.autoSwitch() ?: !on
        }
    }
    Spacer(Modifier.height(10.dp))
    ListRow("HDMI‑CEC и другие настройки HDMI", onHdmiPage)
}

@Composable
private fun HomePage(onHdmiPage: () -> Unit) {
    val context = LocalContext.current
    val channels by produceState(emptyList<TvChannel>()) {
        value = withContext(Dispatchers.IO) { queryTvChannels(context).filter { it.items.isNotEmpty() } }
    }
    Section("Показывать")
    Toggle("Сейчас играет", LauncherSettings.nowPlaying, Modifier.fillMaxWidth()) {
        LauncherSettings.nowPlaying = !LauncherSettings.nowPlaying
    }
    Spacer(Modifier.height(10.dp))
    Toggle("Плитка флешки", LauncherSettings.usbTile, Modifier.fillMaxWidth()) {
        LauncherSettings.usbTile = !LauncherSettings.usbTile
    }
    Spacer(Modifier.height(10.dp))
    Toggle("Плитка HDMI", LauncherSettings.hdmiTile, Modifier.fillMaxWidth()) {
        LauncherSettings.hdmiTile = !LauncherSettings.hdmiTile
    }
    HdmiSection(onHdmiPage)
    Section("Второй ряд")
    PairRow {
        Chip("Авто", LauncherSettings.secondRow == SECOND_ROW_AUTO, Modifier.weight(1f)) {
            LauncherSettings.secondRow = SECOND_ROW_AUTO
        }
        Chip("Выкл", LauncherSettings.secondRow == SECOND_ROW_OFF, Modifier.weight(1f)) {
            LauncherSettings.secondRow = SECOND_ROW_OFF
        }
    }
    channels.forEach { channel ->
        Spacer(Modifier.height(10.dp))
        Chip(channel.name, LauncherSettings.secondRow == channel.key, Modifier.fillMaxWidth()) {
            LauncherSettings.secondRow = channel.key
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(18.dp))
    T(title, 14.sp, color = PanelDim)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun PairRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** A full-width row that runs an action, with a chevron like Google TV's list entries. */
@Composable
private fun ListRow(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fg = if (focused) FocusText else PanelText
    Row(
        Modifier
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .height(56.dp)
            .background(if (focused) FocusBg else CardBg, RoundedCornerShape(16.dp))
            .panelControl({ focused = it }, onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(text, 17.sp, Modifier.weight(1f), color = fg)
        Image(Icons.Rounded.ChevronRight, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(fg))
    }
}

/** Focus handling shared by every panel control: nav/activate sounds. */
@Composable
private fun Modifier.panelControl(onFocus: (Boolean) -> Unit, onClick: () -> Unit): Modifier =
    onFocusChanged {
        onFocus(it.isFocused)
        if (it.isFocused) Sounds.navigate()
    }.clickable(remember { MutableInteractionSource() }, null) {
        Sounds.activate()
        onClick()
    }

@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    modifier: Modifier,
    note: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val fg = if (focused) FocusText else if (selected) PanelText else PanelDim
    Row(
        modifier
            .height(52.dp)
            .background(if (focused) FocusBg else CardBg, RoundedCornerShape(16.dp))
            // Disabled chips stay focusable so the grid doesn't jump, but do nothing.
            .panelControl({ focused = it }) { if (enabled) onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            T(text, 16.sp, color = if (enabled) fg else fg.copy(alpha = 0.5f))
            note?.let { T(it, 12.sp, color = if (focused) FocusText else PanelDim) }
        }
        if (selected) {
            Image(Icons.Rounded.Check, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(if (focused) FocusText else Accent))
        }
    }
}

@Composable
private fun Swatch(brush: Brush, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, tween(120), label = "swatch")
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .size(50.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(brush, shape)
            .then(
                when {
                    focused -> Modifier.border(3.dp, FocusBg, shape)
                    selected -> Modifier.border(3.dp, Accent, shape)
                    else -> Modifier.border(1.dp, Color(0x33FFFFFF), shape)
                },
            )
            .panelControl({ focused = it }, onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(Modifier.size(22.dp).background(Accent, CircleShape), contentAlignment = Alignment.Center) {
                Image(Icons.Rounded.Check, null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(FocusText))
            }
        }
    }
}

@Composable
private fun Toggle(text: String, checked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val knob by animateFloatAsState(if (checked) 1f else 0f, tween(150), label = "knob")
    Row(
        modifier
            .height(56.dp)
            .background(if (focused) FocusBg else CardBg, RoundedCornerShape(16.dp))
            .panelControl({ focused = it }, onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(text, 16.sp, Modifier.weight(1f), color = if (focused) FocusText else PanelText)
        // Material 3 switch: filled track when on.
        Box(
            Modifier
                .size(width = 50.dp, height = 30.dp)
                .background(
                    when {
                        checked && focused -> FocusFill
                        checked -> Accent
                        else -> Color(0xFF5F6368)
                    },
                    CircleShape,
                )
                .padding(4.dp),
        ) {
            Box(
                Modifier
                    .offset(x = 20.dp * knob)
                    .size(22.dp)
                    .background(if (checked) (if (focused) FocusBg else OnText) else Color(0xFFC4C7C5), CircleShape),
            )
        }
    }
}


/** The remote's voice key (F5 on the XGIMI remote, unused by the firmware) toggles the panel. */
fun Modifier.panelKey(onToggle: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.nativeKeyEvent.keyCode != AndroidKeyEvent.KEYCODE_F5) return@onPreviewKeyEvent false
    // With the overlay service running it owns the key; a copy that slips through must not
    // open a second panel.
    if (PanelOverlay.running) return@onPreviewKeyEvent true
    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) onToggle()
    true
}

/** Media volume, changed with left/right like the XGIMI sliders. */
@Composable
private fun VolumeSlider(modifier: Modifier) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    LevelSlider(if (volume == 0) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, volume, max, modifier) {
        volume = it
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
    }
}

/** The projector's light-source brightness (0..10), the same setting as XGIMI's Brightness page. */
@Composable
private fun BrightnessSlider(modifier: Modifier) {
    var level by remember { mutableStateOf(Lumens.level()) }
    val current = level ?: return
    LevelSlider(Icons.Rounded.BrightnessMedium, current, Lumens.MAX, modifier) {
        // Level 0 would leave a nearly black picture; keep the image usable.
        val value = it.coerceAtLeast(1)
        level = value
        Lumens.setLevel(value)
    }
}

/** A focusable bar: left/right step it, taps and drags set it directly. */
@Composable
private fun LevelSlider(
    icon: ImageVector,
    value: Int,
    max: Int,
    modifier: Modifier,
    label: String? = null,
    onSet: (Int) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    fun set(target: Int) {
        val clamped = target.coerceIn(0, max)
        if (clamped != value) onSet(clamped)
    }
    Row(
        modifier
            .height(54.dp)
            .background(if (focused) FocusBg else CardBg, RoundedCornerShape(16.dp))
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                if (code != AndroidKeyEvent.KEYCODE_DPAD_LEFT && code != AndroidKeyEvent.KEYCODE_DPAD_RIGHT) {
                    return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) set(value + if (code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                true
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.navigate()
            }
            .focusable()
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(icon, null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(if (focused) FocusText else PanelText))
        Spacer(Modifier.width(14.dp))
        label?.let {
            T(it, 15.sp, Modifier.width(96.dp), color = if (focused) FocusText else PanelText)
        }
        fun setFraction(fraction: Float) = set((fraction.coerceIn(0f, 1f) * max).roundToInt())
        Box(
            Modifier
                .weight(1f)
                // Generous touch target around the thin bar.
                .height(40.dp)
                .pointerInput(max) { detectTapGestures { setFraction(it.x / size.width) } }
                .pointerInput(max) {
                    detectHorizontalDragGestures { change, _ -> setFraction(change.position.x / size.width) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(if (focused) FocusFill.copy(alpha = 0.3f) else Color(0xFF4A4E55), CircleShape),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(value / max.toFloat())
                        .height(8.dp)
                        .background(if (focused) FocusFill else Accent, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        T("$value", 19.sp, color = if (focused) FocusText else PanelText)
    }
}

private fun soundOutputName(device: Int) = when (device) {
    SoundOutput.SPEAKER -> "Динамик"
    SoundOutput.SPDIF -> "Оптика"
    SoundOutput.ARC -> "HDMI ARC"
    SoundOutput.BLUETOOTH -> "Bluetooth"
    else -> "Другой выход"
}

/** Where the sound goes, like XGIMI's page: automatic on/off, and the devices to pick when off. */
@Composable
private fun SoundOutputSection() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { SoundOutput.prepare(context) }
    var auto by remember { mutableStateOf(SoundOutput.auto() ?: true) }
    var output by remember { mutableStateOf(SoundOutput.output()) }
    val connected = remember { listOf(SoundOutput.SPEAKER, SoundOutput.ARC, SoundOutput.BLUETOOTH).filter(SoundOutput::connected) }
    val scope = rememberCoroutineScope()
    var recheck by remember { mutableStateOf<Job?>(null) }
    // The firmware applies a switch asynchronously, so show the choice right away and read the
    // real state back a moment later.
    fun recheckSoon() {
        recheck?.cancel()
        recheck = scope.launch {
            delay(1200)
            auto = SoundOutput.auto() ?: auto
            output = SoundOutput.output()
        }
    }
    fun select(device: Int) {
        SoundOutput.setOutput(device)
        output = device
        recheckSoon()
    }
    Section("Выход звука")
    Toggle("Автовыбор", auto, Modifier.fillMaxWidth()) {
        auto = !auto
        SoundOutput.setAuto(auto)
        recheckSoon()
    }
    if (auto) {
        output?.let {
            Spacer(Modifier.height(8.dp))
            T("Сейчас: ${soundOutputName(it)}", 14.sp, color = PanelDim)
        }
    } else {
        Spacer(Modifier.height(10.dp))
        PairRow {
            OutputChip(SoundOutput.SPEAKER, output, connected, ::select)
            OutputChip(SoundOutput.ARC, output, connected, ::select)
        }
        Spacer(Modifier.height(10.dp))
        PairRow {
            OutputChip(SoundOutput.BLUETOOTH, output, connected, ::select)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun RowScope.OutputChip(device: Int, output: Int?, connected: List<Int>, select: (Int) -> Unit) {
    val available = device in connected
    Chip(
        soundOutputName(device),
        output == device,
        Modifier.weight(1f),
        note = if (available) null else "не подключено",
        enabled = available,
    ) { select(device) }
}

/** Assigns the remote's four shortcut keys; left/right cycles the action, like XGIMI's selectors. */
@Composable
private fun RemoteButtonsSection() {
    val context = LocalContext.current
    val options by produceState(listOf("" to "Ничего", RemoteButtons.PANEL to "Эта панель", RemoteButtons.HOME to "Главный экран")) {
        val apps = withContext(Dispatchers.IO) { AppRepository(context).loadApps().sortedBy { it.label.lowercase() } }
        value = value + apps.map { RemoteButtons.app(it.pkg) to it.label }
    }
    val requesters = remember { List(4) { FocusRequester() } }
    val pressed = RemoteButtons.lastPressed.intValue
    LaunchedEffect(pressed) {
        if (pressed >= 0) {
            runCatching { requesters[pressed].requestFocus() }
            RemoteButtons.lastPressed.intValue = -1
        }
    }

    Spacer(Modifier.height(8.dp))
    T("Нажмите кнопку на пульте, чтобы перейти к ней. ← → — действие", 14.sp, color = PanelDim)
    for (i in 0..3) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            val current = RemoteButtons.actions[i]
            val index = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
            Selector(
                label = "Кнопка ${i + 1}",
                value = options.getOrNull(index)?.second ?: "Ничего",
                modifier = Modifier.weight(1f).focusRequester(requesters[i]),
            ) { delta ->
                RemoteButtons.set(i, options[(index + delta).mod(options.size)].first)
            }
        }
    }
}

@Composable
private fun Selector(label: String, value: String, modifier: Modifier, onChange: (Int) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .height(54.dp)
            .background(if (focused) FocusBg else CardBg, RoundedCornerShape(16.dp))
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                if (code != AndroidKeyEvent.KEYCODE_DPAD_LEFT && code != AndroidKeyEvent.KEYCODE_DPAD_RIGHT) {
                    return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) {
                    Sounds.activate()
                    onChange(if (code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                }
                true
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.navigate()
            }
            .focusable()
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(label, 16.sp, color = if (focused) FocusText else PanelDim)
        Spacer(Modifier.weight(1f))
        T("‹  $value  ›", 17.sp, color = if (focused) FocusText else PanelText)
    }
}

/** XMB colour (by month like the PS3, or fixed) and the animation switch. */
@Composable
private fun XmbOptions() {
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth()) {
        Chip("Цвет по месяцу", LauncherSettings.xmbColor < 0, Modifier.weight(1f)) { LauncherSettings.xmbColor = -1 }
    }
    // Two rows of six: January-June, July-December.
    XmbColors.chunked(6).forEachIndexed { row, colors ->
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            colors.forEachIndexed { col, color ->
                val index = row * 6 + col
                ColorDot(color, LauncherSettings.xmbColor == index) { LauncherSettings.xmbColor = index }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth()) {
        Toggle("Анимация фона", LauncherSettings.bgAnimation, Modifier.weight(1f)) {
            LauncherSettings.bgAnimation = !LauncherSettings.bgAnimation
        }
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.15f else 1f, tween(120), label = "dot")
    Box(
        Modifier
            .size(46.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                when {
                    focused -> Modifier.border(3.dp, FocusBg, CircleShape)
                    selected -> Modifier.border(3.dp, Accent, CircleShape)
                    else -> Modifier
                },
            )
            .padding(5.dp)
            .background(color, CircleShape)
            .panelControl({ focused = it }, onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Image(Icons.Rounded.Check, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(Color.White))
        }
    }
}
