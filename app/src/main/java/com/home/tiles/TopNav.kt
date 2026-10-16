package com.home.tiles

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** The screens the top bar's tabs lead to. */
enum class NavTab { Home, AllApps }

/**
 * The top bar over the home and All apps screens, Google TV style: tabs for the two screens (the
 * current one marked), the settings button, and whatever is plugged in; on the right the clock,
 * network and battery. One bar for both screens, always in view (the screens change under it);
 * it steps aside only under XGIMI's quick panel and settings (see hiddenUnderPanels).
 *
 * Highlighting a tab is enough to show its screen ([onTabHighlighted]); OK on it goes into the
 * screen ([onTabClick]). The buttons beside the tabs act only on OK. [tabFocus] are the tabs'
 * focus requesters, so the screens can send the selection up to their own tab; [onFocusInside]
 * tells whether the selection is somewhere in the bar.
 */
@Composable
fun TopNav(
    current: NavTab,
    tabFocus: List<FocusRequester>,
    onTabHighlighted: (NavTab) -> Unit,
    onTabClick: (NavTab) -> Unit,
    onSettings: () -> Unit,
    onFocusInside: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // The same margin on both sides, so the bar sits evenly on the screen.
            .padding(start = 48.dp, end = 48.dp, top = 30.dp)
            .height(100.dp)
            .hiddenUnderPanels()
            // A group, so moving between its buttons doesn't report the selection leaving.
            .onFocusChanged { onFocusInside(it.hasFocus) }
            .focusGroup(),
    ) {
        NavTabs(
            listOf(tr(R.string.home_tab), tr(R.string.all_apps)),
            current = current.ordinal,
            focus = tabFocus,
            onHighlight = { onTabHighlighted(NavTab.entries[it]) },
            onClick = { onTabClick(NavTab.entries[it]) },
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.width(18.dp))
        // Beam's settings page, which also leads to XGIMI's quick panel and settings.
        val gearFocus = remember { FocusRequester() }
        NavIconButton(Icons.Outlined.DashboardCustomize, tr(R.string.settings), onSettings, Modifier.padding(top = 2.dp).focusRequester(gearFocus).updateDot())
        val nowPlaying by rememberNowPlaying()
        val playing = nowPlaying?.takeIf { LauncherSettings.nowPlaying }
        // A device unplugged while selected hands the selection to the settings button beside it.
        // With more beside them than one pill's room, the devices show as round icons.
        ConnectedDevices(
            onGone = { runCatching { gearFocus.requestFocus() } },
            crowded = playing != null,
            modifier = Modifier.padding(start = 30.dp, top = 2.dp),
        )
        // What's playing takes the room left, at the right: measured after the clock and the batteries,
        // so with devices plugged in as well it shortens its title instead of pushing them off the screen.
        Box(Modifier.weight(1f).padding(start = 24.dp, end = 32.dp), contentAlignment = Alignment.CenterEnd) {
            playing?.let { NowPlayingBar(it) }
        }
        Row(Modifier.height(66.dp), verticalAlignment = Alignment.CenterVertically) {
            // Clock, network and battery share one text size, icon height and spacing.
            // Regular, like the tab labels: Light strokes go thin on a projected picture at this size.
            Clock(StatusTextSize, weight = FontWeight.Normal)
            Spacer(Modifier.width(StatusGap))
            VpnIcon()
            NetworkIcon()
            Spacer(Modifier.width(StatusGap))
            BatteryIndicator(iconScale = 0.8f, weight = FontWeight.Normal)
        }
    }
}

/**
 * Text tabs in one pill, like Google TV's. The [current] screen's tab keeps a faint fill; the
 * selection is a white capsule (dark in the light theme, where white wouldn't show) that slides
 * and stretches from tab to tab. Coming to the tabs from elsewhere, it appears on the tab
 * reached, without travelling there from wherever it last was.
 *
 * Both fills are drawn behind the tabs in the draw phase, from the tabs' measured places: moving
 * the selection redraws the pill without recomposing or laying anything out; only the two
 * labels' colours recompose, for the 150 ms they change.
 */
@Composable
private fun NavTabs(
    tabs: List<String>,
    current: Int,
    focus: List<FocusRequester>,
    onHighlight: (Int) -> Unit,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // The selected tab; -1 while the selection is elsewhere (set when it leaves the whole pill,
    // not when it moves between tabs, so moving along the tabs always slides).
    var focused by remember { mutableIntStateOf(-1) }
    // Each tab's place in the pill, as laid out.
    val places = remember(tabs.size) { List(tabs.size) { mutableStateOf(Offset.Zero to Size.Zero) } }
    val x = remember { Animatable(0f) }
    val w = remember { Animatable(0f) }
    val shown by animateFloatAsState(if (focused >= 0) 1f else 0f, motion(150), label = "tabs")
    val dark = LauncherSettings.dark
    val focusFill = FocusBg
    val focusText = FocusText
    val currentFill = Colors.Text.copy(alpha = if (dark) 0.12f else 0.09f)
    Row(
        modifier
            .height(TabHeight + TabInset * 2)
            .background(Colors.Button, CircleShape)
            // The tabs are one focus group: it reports the selection leaving only when it leaves
            // the pill. Watching the tabs themselves, moving from tab to tab read as leaving and
            // arriving again, and the selection jumped instead of sliding.
            .onFocusChanged { if (!it.hasFocus) focused = -1 }
            .focusGroup()
            .padding(TabInset)
            .drawBehind {
                val radius = CornerRadius(size.height / 2)
                val (at, room) = places[current].value
                // The current screen's mark, only while the selection is elsewhere (as on Google TV):
                // on the tabs, the selection itself shows where you are, and a mark left on the tab
                // being switched away from would linger there until the screen had changed.
                if (shown < 1f) drawRoundRect(currentFill.copy(alpha = currentFill.alpha * (1f - shown)), at, room, radius)
                if (shown > 0f) {
                    drawRoundRect(focusFill.copy(alpha = focusFill.alpha * shown), Offset(x.value, 0f), Size(w.value, size.height), radius)
                }
            },
    ) {
        tabs.forEachIndexed { i, label ->
            val textColor by animateColorAsState(if (focused == i) focusText else Colors.Text, motion(150), label = "tab")
            Box(
                Modifier
                    .height(TabHeight)
                    .onPlaced { places[i].value = it.positionInParent() to Size(it.size.width.toFloat(), it.size.height.toFloat()) }
                    .focusRequester(focus[i])
                    .onFocusChanged {
                        if (!it.isFocused) return@onFocusChanged
                        val arriving = focused < 0
                        focused = i
                        val (at, room) = places[i].value
                        scope.launch { if (arriving) x.snapTo(at.x) else x.animateTo(at.x, TabSlide) }
                        scope.launch { if (arriving) w.snapTo(room.width) else w.animateTo(room.width, TabSlide) }
                        Sounds.navigate()
                        onHighlight(i)
                    }
                    .clickable(remember { MutableInteractionSource() }, null) {
                        Sounds.activate()
                        onClick(i)
                    }
                    .padding(horizontal = 26.dp),
                contentAlignment = Alignment.Center,
            ) {
                T(label, 20.sp, color = textColor)
            }
        }
    }
}

private val TabHeight = 56.dp
// The pill's edge around the tabs: 64dp tall in all, like the buttons and chips beside it.
private val TabInset = 4.dp
private val TabSlide: AnimationSpec<Float>
    get() = if (Effects.reduced) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 900f)

/**
 * A round top-bar button with only an icon, like Google TV's: no caption. Selected, it fills like
 * the tabs' selection.
 */
@Composable
private fun NavIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val dark = LauncherSettings.dark
    val fill by animateColorAsState(if (focused) FocusBg else Colors.Button, motion(150), label = "fill")
    val tint by animateColorAsState(if (focused) FocusText else Colors.Text, motion(150), label = "tint")
    Box(
        modifier
            .size(TabHeight + TabInset * 2)
            .background(fill, CircleShape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.navigate()
            }
            .clickable(remember { MutableInteractionSource() }, null) {
                Sounds.activate()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(icon, description, Modifier.size(30.dp), colorFilter = ColorFilter.tint(tint))
    }
}

/**
 * What is plugged in, as chips after the buttons, like the inputs entry on Google TV: drives open
 * the file manager, HDMI devices their input. Nothing shows while nothing is connected. With two
 * or more, or [crowded] (music playing beside them), each is a round icon that opens out into its
 * pill while selected: a full pill each didn't fit the bar beside the clock and the batteries.
 */
@Composable
private fun ConnectedDevices(onGone: () -> Unit, crowded: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val realDrives by rememberUsbDrives()
    val realHdmi by rememberLiveHdmi()
    // Pretend devices for screenshots, switched on only over adb (see DemoDevices).
    val demo = DemoDevices.on.value
    val drives = if (demo) realDrives + DemoDevices.drive else realDrives
    val hdmi = if (demo) realHdmi + DemoDevices.hdmi else realHdmi
    val compact = crowded || drives.size + hdmi.size >= 2
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = modifier) {
        hdmi.forEach { key("hdmi:${it.id}") { HdmiChip(it, compact, onGone) { Xgimi.openInput(context, it) } } }
        drives.forEach { key("usb:${it.key}") { UsbChip(it, compact, onGone) { context.launchPackage(FILE_MANAGER) } } }
    }
}

private const val FILE_MANAGER = "com.xgimi.filemanager"
