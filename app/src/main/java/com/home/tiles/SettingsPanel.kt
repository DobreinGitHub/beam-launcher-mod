package com.home.tiles

import android.content.Context
import androidx.annotation.StringRes
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.EnterTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sub-pages opened from the tile grid. Only what is Beam's own: the projector's settings are in
 * XGIMI's quick panel and settings (translated by the russifier), one tile away.
 */
private enum class PanelPage(@StringRes val titleRes: Int) {
    Appearance(R.string.appearance),
    Home(R.string.home_screen),
    Remote(R.string.remote_buttons),
    Screensaver(R.string.screensaver),
    Power(R.string.power),
    About(R.string.about_beam);

    val title: String get() = tr(titleRes)
}

/** Beam's settings, styled after XGIMI's quick panel, on the right; no slide-in, like XGIMI's. */
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
            // Appears at once, like XGIMI's own quick panel.
            enter = EnterTransition.None,
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
    /** On/off tiles (the sleep timer) show their state; null for plain ones. */
    val active: Boolean? = null,
)

@Composable
private fun ColumnScope.MainPage(
    firstTile: FocusRequester,
    tileRequesters: Map<PanelPage, FocusRequester>,
    onDismiss: () -> Unit,
    open: (PanelPage) -> Unit,
) {
    val context = LocalContext.current
    val items = listOf(
        // The projector's own panel: closes this one first, so the two never stack.
        QuickItem(Icons.Rounded.Settings, tr(R.string.xgimi_settings), action = {
            onDismiss()
            context.openQuickPanel()
        }),
        QuickItem(Icons.Rounded.Palette, tr(R.string.appearance), PanelPage.Appearance),
        QuickItem(Icons.Rounded.Dashboard, tr(R.string.home_screen), PanelPage.Home),
        QuickItem(Icons.Rounded.SettingsRemote, tr(R.string.remote_buttons), PanelPage.Remote),
        QuickItem(Icons.Rounded.Landscape, tr(R.string.screensaver), PanelPage.Screensaver),
        QuickItem(Icons.Rounded.PowerSettingsNew, tr(R.string.power), PanelPage.Power, active = SleepTimer.endsAt.longValue > 0),
    )
    PanelHeader(Modifier.focusRequester(tileRequesters.getValue(PanelPage.About))) { open(PanelPage.About) }
    Spacer(Modifier.height(16.dp))
    // One full-width row each: the names fit, and all of them on one screen.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { i, item ->
            val requester = when {
                i == 0 -> firstTile
                item.page != null -> tileRequesters.getValue(item.page)
                else -> null
            }
            val modifier = Modifier.fillMaxWidth()
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            WideTile(item, modifier) {
                val target = item.page
                if (target != null) open(target) else item.action?.invoke()
            }
        }
    }
}

/**
 * Laid out like XGIMI's panel header: the time with the battery beside it, the date underneath
 * ("14 октября 2026 · Ср"), and a round grey button on the right (theirs is settings, ours About).
 */
@Composable
private fun PanelHeader(aboutModifier: Modifier, onAbout: () -> Unit) {
    val timeFormat = remember(AppLanguage.locale) { SimpleDateFormat("HH:mm", AppLanguage.locale) }
    val dateFormat = remember(AppLanguage.locale) { SimpleDateFormat("d MMMM yyyy", AppLanguage.locale) }
    val dayFormat = remember(AppLanguage.locale) { SimpleDateFormat("EE", AppLanguage.locale) }
    // Minute resolution: every change redraws this full-screen window over the video.
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    val day = dayFormat.format(now).replaceFirstChar { it.titlecase(AppLanguage.locale) }
    // As far from the top as XGIMI's.
    Row(Modifier.padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(timeFormat.format(now), 40.sp, color = PanelText)
                Spacer(Modifier.width(10.dp))
                BatteryIndicator(13.sp, PanelText, iconScale = 0.6f)
            }
            T(dateFormat.format(now) + " · " + day, 15.sp, color = PanelText)
        }
        Box(aboutModifier) { RoundIcon(Icons.Rounded.Info, filled = true, onClick = onAbout) }
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

private val TileHeight = 66.dp

/** A tile with icon, label and, for on/off ones, their state; like the wide tiles in XGIMI's panel. */
@Composable
private fun WideTile(item: QuickItem, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val on = item.active == true
    val fg = tileText(focused, on)
    val status = item.active?.let { if (it) tr(R.string.on_dot) else tr(R.string.off_dot) }
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

/** A round icon button; [filled] gives it the grey disc of XGIMI's header button when not focused. */
@Composable
private fun RoundIcon(icon: ImageVector, filled: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(48.dp)
            .background(if (focused) FocusBg else if (filled) CardBg else Color.Transparent, CircleShape)
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
        Box(Modifier.focusRequester(first)) { RoundIcon(Icons.Rounded.ArrowBack, onClick = onBack) }
        Spacer(Modifier.width(8.dp))
        T(page.title, 24.sp, color = PanelText)
    }
    Spacer(Modifier.height(8.dp))
    when (page) {
        PanelPage.Appearance -> AppearancePage()
        PanelPage.Home -> HomePage()
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
        PanelPage.About -> AboutBeamPage()
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
