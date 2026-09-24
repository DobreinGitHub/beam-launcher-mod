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
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.FilterCenterFocus
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.Lightbulb
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
import androidx.compose.ui.text.style.TextAlign
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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// Google TV quick-settings palette: dark sheet, dark cards, light-blue focus with dark text.
private val PanelBg = Color(0xFF1F2227)
private val CardBg = Color(0xFF2E3238)
private val FocusBg = Color(0xFFD3E3FD)
private val FocusText = Color(0xFF0B1D36)
private val Accent = Color(0xFF8AB4F8)
private val PanelText = Color(0xFFE8EAED)
private val PanelDim = Color(0xFFA8ACB3)

/** Sub-pages opened from the tile grid. */
private enum class PanelPage(val title: String) {
    Picture("Изображение"),
    Sound("Звук"),
    Appearance("Оформление"),
    Home("Главный экран"),
    Remote("Кнопки пульта"),
    Xgimi("Настройки XGIMI"),
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
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x99000000))))
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
                    .padding(16.dp)
                    .width(if (Device.isTv) 480.dp else 400.dp)
                    .fillMaxHeight()
                    .background(PanelBg, RoundedCornerShape(28.dp))
                    // Taps on the panel itself must not reach the close-on-tap backdrop.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 22.dp),
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
    /** On the projector: a double-width tile with its label, instead of a square icon tile. */
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
    val inputs = remember { if (Device.isTv) Xgimi.hdmiInputs(context) else emptyList() }
    var eco by remember { mutableStateOf(if (Device.isTv) Eco.enabled() else null) }
    val soundOutput = remember { if (Device.isTv) SoundOutput.output()?.let(::soundOutputName) else null }
    // Projector actions close the panel first so it doesn't cover the picture (keystone photographs it).
    fun projector(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }
    val items = buildList {
        if (Device.isTv) {
            // Everyday actions first as wide labelled tiles, then setup and settings as square
            // icon tiles that show their name only when focused (like XGIMI's own panel).
            add(QuickItem(Icons.Rounded.CenterFocusStrong, "Автофокус", action = projector { Xgimi.autoFocus(context) }, wide = true))
            add(QuickItem(Icons.Rounded.CropFree, "Трапеция", action = projector { Xgimi.autoKeystone(context) }, wide = true))
            add(QuickItem(Icons.Rounded.Wifi, "Wi‑Fi", action = projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_WIFI) }, wide = true))
            add(QuickItem(Icons.Rounded.Bluetooth, "Bluetooth", action = projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_BLUETOOTH) }, wide = true))
            add(QuickItem(Icons.Rounded.VolumeUp, "Звук", PanelPage.Sound, subtitle = soundOutput, wide = true))
            add(QuickItem(Icons.Rounded.Tonality, "Изображение", PanelPage.Picture, wide = true))
            // One HDMI port: switch straight to it; with several, number them.
            inputs.forEachIndexed { i, input ->
                val label = if (inputs.size == 1) "HDMI" else "HDMI ${i + 1}"
                add(QuickItem(Icons.Rounded.SettingsInputHdmi, label, action = projector { Xgimi.openInput(context, input) }))
            }
            eco?.let { on ->
                // Stays open: the change is visible behind the panel.
                add(QuickItem(Icons.Rounded.Eco, "Эко-режим", active = on, action = { if (Eco.set(!on)) eco = Eco.enabled() }))
            }
            // XGIMI's "Any Door" scenes, the app behind the default screensaver.
            add(QuickItem(Icons.Rounded.Landscape, "Заставки", action = projector { context.launchPackage(Xgimi.SCREENSAVER_APP) }))
            add(QuickItem(Icons.Rounded.PowerSettingsNew, "Питание", action = projector { Xgimi.powerMenu(context) }))
            add(QuickItem(Icons.Rounded.FilterCenterFocus, "Ручной фокус", action = projector { Xgimi.manualFocus(context) }))
            add(QuickItem(Icons.Rounded.Crop, "Ручная трапеция", action = projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_KEYSTONE) }))
            add(QuickItem(Icons.Rounded.ZoomOutMap, "Зум и сдвиг", action = projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_ZOOM) }))
            add(QuickItem(Icons.Rounded.ScreenRotation, "Поворот экрана", action = projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_ROTATE) }))
            add(QuickItem(Icons.Rounded.Palette, "Оформление", PanelPage.Appearance))
            add(QuickItem(Icons.Rounded.Dashboard, "Главный экран", PanelPage.Home))
            add(QuickItem(Icons.Rounded.SettingsRemote, "Кнопки пульта", PanelPage.Remote))
            add(QuickItem(Icons.Rounded.SettingsApplications, "XGIMI", PanelPage.Xgimi))
        } else {
            add(QuickItem(Icons.Rounded.VolumeUp, "Звук", PanelPage.Sound))
            add(QuickItem(Icons.Rounded.Palette, "Оформление", PanelPage.Appearance))
            add(QuickItem(Icons.Rounded.Dashboard, "Главный экран", PanelPage.Home))
        }
    }

    // The projector fits every tile on one screen: a 4-unit grid of wide (2 units) and square
    // (1 unit) tiles, no tip card. The tablet keeps two big tiles per row.
    val compact = Device.isTv
    val units = if (compact) 4 else 2
    val gap = if (compact) 8.dp else 12.dp
    fun span(item: QuickItem) = if (compact && item.wide) 2 else 1
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
    Spacer(Modifier.height(if (compact) 14.dp else 20.dp))
    if (Device.isTv) {
        BrightnessSlider(Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
    }
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
                        when {
                            !compact -> QuickTile(item, modifier, onClick)
                            item.wide -> WideTile(item, modifier, onClick)
                            else -> IconTile(item, modifier, onClick)
                        }
                    }
                }
            }
        }
    }
    if (!compact) {
        Spacer(Modifier.height(24.dp))
        TipCard()
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

/** Google TV style tile: icon and label on a dark card, light-blue when focused. */
@Composable
private fun QuickTile(item: QuickItem, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val on = item.active == true
    val fg = if (focused || on) FocusText else PanelText
    Column(
        modifier
            .height(96.dp)
            .background(
                when {
                    focused -> FocusBg
                    on -> Accent
                    else -> CardBg
                },
                RoundedCornerShape(20.dp),
            )
            .panelControl({ focused = it }, onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Image(item.icon, null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(fg))
        Column {
            T(item.label, 16.sp, color = fg)
            item.active?.let { T(if (it) "Вкл." else "Выкл.", 13.sp, color = if (focused || it) FocusText else PanelDim) }
            item.subtitle?.let { T(it, 13.sp, color = if (focused) FocusText else PanelDim) }
        }
    }
}

private fun tileColor(focused: Boolean, on: Boolean) = when {
    focused -> FocusBg
    on -> Accent
    else -> CardBg
}

private val TileHeight = 72.dp

/** Projector: double-width tile with icon, label and optional state (sound output). */
@Composable
private fun WideTile(item: QuickItem, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val on = item.active == true
    val fg = if (focused || on) FocusText else PanelText
    val status = item.active?.let { if (it) "Вкл." else "Выкл." } ?: item.subtitle
    Row(
        modifier
            .height(TileHeight)
            .background(tileColor(focused, on), RoundedCornerShape(16.dp))
            .panelControl({ focused = it }, onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(item.icon, null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(fg))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(item.label, 16.sp, color = fg)
            status?.let { T(it, 12.sp, color = if (focused || on) FocusText else PanelDim) }
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
    val fg = if (focused || on) FocusText else PanelText
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
                    .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 700),
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

/** "Tip of the day" card at the bottom, like Google TV's. */
@Composable
private fun TipCard() {
    val tips = if (Device.isTv) TvTips else TabletTips
    val tip = tips[Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % tips.size]
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        T("Совет дня", 15.sp, color = PanelDim)
        Spacer(Modifier.height(12.dp))
        Image(Icons.Rounded.Lightbulb, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(PanelText))
        Spacer(Modifier.height(8.dp))
        BasicText(
            tip,
            style = TextStyle(color = PanelText, fontSize = 16.sp, textAlign = TextAlign.Center),
        )
    }
}

private val TvTips = listOf(
    "Голосовая кнопка пульта открывает эту панель из любого приложения",
    "Удерживайте OK на плитке, чтобы закрепить, скрыть или удалить приложение",
    "Кнопки пульта с китайскими сервисами можно назначить в «Кнопки пульта»",
    "Режим изображения меняется сразу — панель можно не закрывать",
)
private val TabletTips = listOf(
    "Удерживайте плитку, чтобы закрепить, скрыть или удалить приложение",
    "Поверните планшет вертикально — плитки выстроятся сеткой",
    "Фон XMB меняет цвет каждый месяц, как на PS3",
)

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
        PanelPage.Picture -> {
            // Stays open so the change can be judged against the picture behind the panel.
            Section("Режим изображения")
            Xgimi.pictureModes.forEach { (label, mode) ->
                ListRow(label) { Xgimi.setPictureMode(context, mode) }
            }
        }
        PanelPage.Sound -> {
            Section("Громкость")
            VolumeSlider(Modifier.fillMaxWidth())
            if (Device.isTv && SoundOutput.available) SoundOutputSection()
            Section("Интерфейс")
            Toggle("Звуки навигации", LauncherSettings.sounds, Modifier.fillMaxWidth()) {
                LauncherSettings.sounds = !LauncherSettings.sounds
            }
        }
        PanelPage.Appearance -> AppearancePage()
        PanelPage.Home -> HomePage()
        PanelPage.Remote -> RemoteButtonsSection()
        PanelPage.Xgimi -> {
            Section("Разделы настроек проектора")
            ListRow("Звуковой выход") { projector { Xgimi.openSettingsPage(context, Xgimi.PAGE_SOUND_OUTPUT) } }
            ListRow("Все настройки") { projector { context.openSettings() } }
        }
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

@Composable
private fun HomePage() {
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
    if (Device.isTv) {
        Spacer(Modifier.height(10.dp))
        Toggle("Плитка HDMI", LauncherSettings.hdmiTile, Modifier.fillMaxWidth()) {
            LauncherSettings.hdmiTile = !LauncherSettings.hdmiTile
        }
    }
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
                        checked && focused -> Color(0xFF0B57D0)
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
                    .background(if (checked) (if (focused) Color.White else FocusText) else Color(0xFFC4C7C5), CircleShape),
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
private fun LevelSlider(icon: ImageVector, value: Int, max: Int, modifier: Modifier, onSet: (Int) -> Unit) {
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
                    .background(if (focused) Color(0x330B1D36) else Color(0xFF4A4E55), CircleShape),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(value / max.toFloat())
                        .height(8.dp)
                        .background(if (focused) Color(0xFF0B57D0) else Accent, CircleShape),
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
