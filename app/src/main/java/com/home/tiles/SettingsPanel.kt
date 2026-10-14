package com.home.tiles

import androidx.annotation.StringRes
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * The categories of the settings page, in XGIMI's full-settings layout. The first two hand over to
 * XGIMI's own quick panel and settings (this page closes, so the two never stand side by side);
 * the rest are Beam's.
 */
private enum class Category(@StringRes val titleRes: Int, val icon: ImageVector, val xgimi: Boolean = false) {
    Quick(R.string.quick_settings, Icons.Rounded.Tune, xgimi = true),
    Xgimi(R.string.xgimi_settings, Icons.Rounded.Settings, xgimi = true),
    Appearance(R.string.appearance, Icons.Rounded.Palette),
    Home(R.string.home_screen, Icons.Rounded.Dashboard),
    Remote(R.string.remote_buttons, Icons.Rounded.SettingsRemote),
    Screensaver(R.string.screensaver, Icons.Rounded.Landscape),
    About(R.string.about_beam, Icons.Rounded.Info);

    val title: String get() = tr(titleRes)
}

/** The settings page as a dialog over the launcher, when the overlay service isn't running. */
@Composable
fun SettingsPanel(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            // Back first leaves the content column; PanelScreen decides.
            dismissOnBackPress = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        PanelScreen(onDismiss)
    }
}

/**
 * Beam's settings page, laid out like XGIMI's full settings so the two read as one system: two
 * dark cards on the left, categories with round icons, and the focused category's content beside
 * them. Moving through the categories shows each one's content; right (or OK) goes into it, Back
 * returns to the category, and Back again closes. Hosted by [SettingsPanel] in the launcher and by
 * [PanelOverlay] over other apps; appears at once, like XGIMI's.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PanelScreen(
    onDismiss: () -> Unit,
    /** Opens XGIMI's panel or settings in this page's place; the overlay keeps its darkening until XGIMI's is up. */
    handOver: (open: () -> Unit) -> Unit = { open -> onDismiss(); open() },
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var selected by remember { mutableStateOf(Category.Quick) }
    var inContent by remember { mutableStateOf(false) }
    // Handing over to XGIMI: the cards fade, the darkening stays until XGIMI's own is on screen.
    var leaving by remember { mutableStateOf(false) }
    val cardsAlpha by animateFloatAsState(if (leaving) 0f else 1f, tween(120), label = "leave")
    val requesters = remember { Category.entries.associateWith { FocusRequester() } }
    DisposableEffect(Unit) {
        PanelState.open = true
        onDispose { PanelState.open = false }
    }
    LaunchedEffect(Unit) {
        repeat(10) {
            withFrameNanos {}
            if (runCatching { requesters.getValue(selected).requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    fun back() {
        if (inContent) runCatching { requesters.getValue(selected).requestFocus() } else onDismiss()
    }
    fun open(category: Category) {
        when (category) {
            Category.Quick, Category.Xgimi -> {
                leaving = true
                handOver { if (category == Category.Quick) context.openQuickPanel() else context.openSettings() }
            }
            else -> focusManager.moveFocus(FocusDirection.Right)
        }
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
            // Touch: a tap beside the cards closes the page.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        Row(
            Modifier
                // Where XGIMI's settings cards sit.
                .padding(21.dp)
                .fillMaxHeight()
                .graphicsLayer { alpha = cardsAlpha }
                // Taps on the cards themselves must not reach the close-on-tap backdrop.
                .pointerInput(Unit) { detectTapGestures { } },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                Modifier
                    .width(272.dp)
                    .fillMaxHeight()
                    .background(SheetBg, RoundedCornerShape(16.dp))
                    // Coming back from the content lands on its category, not the nearest one.
                    .focusProperties { enter = { requesters.getValue(selected) } }
                    .focusGroup()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Category.entries.forEach { category ->
                    // XGIMI's two first, then Beam's own.
                    if (category == Category.Appearance) {
                        Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(Color(0x22FFFFFF)))
                    }
                    CategoryRow(
                        category,
                        selected = category == selected,
                        modifier = Modifier.focusRequester(requesters.getValue(category)),
                        onFocus = {
                            selected = category
                            inContent = false
                        },
                        onClick = { open(category) },
                    )
                }
            }
            // One scroll position per category: each opens at its top.
            key(selected) {
                Column(
                    Modifier
                        .width(390.dp)
                        .fillMaxHeight()
                        .background(SheetBg, RoundedCornerShape(16.dp))
                        .onFocusChanged { if (it.hasFocus) inContent = true }
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                ) {
                    T(selected.title, 22.sp, color = PanelText)
                    Spacer(Modifier.height(4.dp))
                    Content(selected, onDismiss) { open(it) }
                }
            }
        }
    }
}

@Composable
private fun Content(category: Category, onDismiss: () -> Unit, open: (Category) -> Unit) {
    val context = LocalContext.current
    fun projector(action: () -> Unit) {
        onDismiss()
        action()
    }
    when (category) {
        Category.Quick, Category.Xgimi -> {
            Spacer(Modifier.height(8.dp))
            T(
                tr(if (category == Category.Quick) R.string.quick_settings_hint else R.string.xgimi_settings_hint),
                15.sp,
                color = PanelDim,
                maxLines = 4,
            )
            Spacer(Modifier.height(16.dp))
            ListRow(tr(R.string.menu_open)) { open(category) }
        }
        Category.Appearance -> AppearancePage()
        Category.Home -> HomePage()
        Category.Remote -> RemoteButtonsSection()
        // XGIMI's screensaver is set up in its scenes app; others in their own app.
        Category.Screensaver -> ScreensaverPage(onSetup = { component ->
            projector {
                context.launchPackage(if (component == Screensavers.XGIMI) Xgimi.SCREENSAVER_APP else component.packageName)
            }
        })
        Category.About -> AboutBeamPage()
    }
}

/**
 * A category like XGIMI's settings list: a round icon and the name. Focused: white with dark text
 * and a light disc; selected while its content has the focus: a faint fill.
 */
@Composable
private fun CategoryRow(
    category: Category,
    selected: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val fg = if (focused) FocusText else PanelText
    Row(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(
                when {
                    focused -> FocusBg
                    selected -> CardBg
                    else -> Color.Transparent
                },
                RoundedCornerShape(16.dp),
            )
            .panelControl({
                focused = it
                if (it) onFocus()
            }, onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).background(if (focused) Color(0xFFE5E5E5) else Color(0xFF474747), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(category.icon, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(if (focused) FocusText else PanelText))
        }
        Spacer(Modifier.width(12.dp))
        T(category.title, 16.sp, color = fg)
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
