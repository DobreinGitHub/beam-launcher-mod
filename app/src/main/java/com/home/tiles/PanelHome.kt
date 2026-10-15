package com.home.tiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AppearancePage() {
    val dark = LauncherSettings.dark
    Section(tr(R.string.theme))
    PairRow {
        Chip(tr(R.string.theme_light), !dark, Modifier.weight(1f)) { LauncherSettings.dark = false }
        Chip(tr(R.string.theme_dark), dark, Modifier.weight(1f)) { LauncherSettings.dark = true }
    }
    Section(tr(R.string.background_name, Backgrounds[LauncherSettings.background].name))
    // Gradients first, the animated XMB one last, six to a row.
    val order = Backgrounds.indices.sortedBy { Backgrounds[it].xmb }
    order.chunked(6).forEachIndexed { row, indices ->
        if (row > 0) Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            indices.forEach { i ->
                Swatch(Colors.presetBrush(Backgrounds[i]), LauncherSettings.background == i) { LauncherSettings.background = i }
            }
            // A short last row keeps the swatches in their columns.
            repeat(6 - indices.size) { Spacer(Modifier.size(50.dp)) }
        }
    }
    if (Colors.isXmb) XmbOptions()
    Spacer(Modifier.height(12.dp))
    Toggle(tr(R.string.ambient_tint), LauncherSettings.ambientTint, Modifier.fillMaxWidth()) {
        LauncherSettings.ambientTint = !LauncherSettings.ambientTint
    }
    T(tr(R.string.ambient_tint_hint), 14.sp, color = PanelDim, maxLines = 3)
    Section(tr(R.string.interface_section))
    Toggle(tr(R.string.nav_sounds), LauncherSettings.sounds, Modifier.fillMaxWidth()) {
        LauncherSettings.sounds = !LauncherSettings.sounds
    }
    // Each language is named in itself, so it can be found whatever the interface is set to.
    Section(tr(R.string.language))
    PairRow {
        Chip(tr(R.string.language_system), AppLanguage.current == AppLanguage.SYSTEM, Modifier.weight(1f)) { AppLanguage.current = AppLanguage.SYSTEM }
        Chip("Русский", AppLanguage.current == AppLanguage.RU, Modifier.weight(1f)) { AppLanguage.current = AppLanguage.RU }
    }
    Spacer(Modifier.height(10.dp))
    PairRow {
        Chip("English", AppLanguage.current == AppLanguage.EN, Modifier.weight(1f)) { AppLanguage.current = AppLanguage.EN }
    }
}

/**
 * The home screen's section: the now-playing bar, and the channel rows, every channel the apps
 * publish grouped under its app, with a few of its posters, so «Популярное» in one app can be
 * told from «Популярное» in another. A switch each; switched on, a row joins the home screen's end
 * (the order is changed there). Channels new since this list was last open are marked «Новый».
 */
@Composable
internal fun HomePage() {
    val context = LocalContext.current
    val channels by produceState(emptyList<TvChannel>()) {
        value = withContext(Dispatchers.IO) { queryTvChannels(context).filter { it.items.isNotEmpty() } }
    }
    // Shown here once: the marks go when the list closes (not as it opens, so they can be seen).
    val seen = remember { LauncherSettings.seenChannels }
    val latest by rememberUpdatedState(channels)
    DisposableEffect(Unit) {
        onDispose { if (latest.isNotEmpty()) LauncherSettings.markChannelsSeen(latest.map { it.key }) }
    }
    Section(tr(R.string.show))
    Toggle(tr(R.string.now_playing), LauncherSettings.nowPlaying, Modifier.fillMaxWidth()) {
        LauncherSettings.nowPlaying = !LauncherSettings.nowPlaying
    }
    Section(tr(R.string.second_row))
    T(tr(R.string.channel_rows_hint), 14.sp, color = PanelDim, maxLines = 3)
    // Auto shows the row it picks as switched on; switching any row turns auto into a plain list.
    val shown = shownChannelRows(LauncherSettings.secondRow, channels)
    // Watch Next (no app of its own) first, then the apps with rows on the home screen, then the
    // others, by name; each app's channels in its order. Settled as the list opens, so switching
    // rows doesn't reorder it under the selection.
    val onAtOpen = remember(channels.isNotEmpty()) { shownChannelRows(LauncherSettings.secondRow, channels).toSet() }
    val groups = channels.groupBy { it.pkg }.entries.sortedWith(
        compareBy(
            { it.key.isNotEmpty() },
            { group -> group.value.none { it.key in onAtOpen } },
            { it.value.first().app?.lowercase() ?: it.key },
        ),
    )
    groups.forEach { (pkg, list) ->
        if (pkg.isNotEmpty()) {
            Row(Modifier.padding(start = 4.dp, top = 16.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(pkg, 20.dp)
                Spacer(Modifier.width(8.dp))
                T(list.first().app ?: pkg, 14.sp, color = PanelDim)
            }
        }
        list.forEach { channel ->
            Spacer(Modifier.height(8.dp))
            ChannelToggle(channel, channel.key in shown, isNew = seen != null && channel.key !in seen) {
                LauncherSettings.secondRow = toggleChannelRow(LauncherSettings.secondRow, channel.key, channels)
            }
        }
    }
}

/** A channel's switch: its name (and «Новый»), its first posters under it, the switch at the right. */
@Composable
private fun ChannelToggle(channel: TvChannel, checked: Boolean, isNew: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fg = if (focused) FocusText else PanelText
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (focused) FocusBg else CardBg, RoundedCornerShape(16.dp))
            .panelControl({ focused = it }, onClick)
            .padding(start = 16.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(channel.name, 16.sp, Modifier.weight(1f, fill = false), color = fg)
                if (isNew) {
                    Spacer(Modifier.width(8.dp))
                    T(
                        tr(R.string.row_new),
                        11.sp,
                        Modifier.background(MarkOrange, CircleShape).padding(horizontal = 7.dp, vertical = 1.dp),
                        color = Color.White,
                        weight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ChannelThumbs(channel)
        }
        Spacer(Modifier.width(12.dp))
        SwitchTrack(checked, focused)
    }
}

/** Assigns the remote's four shortcut keys; left/right cycles the action, like XGIMI's selectors. */
@Composable
internal fun RemoteButtonsSection() {
    val context = LocalContext.current
    val choices by produceState(listOf("" to tr(R.string.nothing), RemoteButtons.PANEL to tr(R.string.this_panel), RemoteButtons.HOME to tr(R.string.home_screen))) {
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

    Section(tr(R.string.app_buttons))
    T(tr(R.string.app_buttons_hint), 14.sp, color = PanelDim, maxLines = 3)
    for (i in 0..3) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            val current = RemoteButtons.actions[i]
            // An assignment that isn't in the list yet (the apps are still loading) or any more
            // (the app was removed) is shown as it is, and ← → step from it: not from "Ничего".
            val options = if (choices.any { it.first == current }) choices else choices + (current to assignedLabel(context, current))
            val index = options.indexOfFirst { it.first == current }
            Selector(
                label = tr(R.string.button_n, i + 1),
                value = options[index].second,
                modifier = Modifier.weight(1f).focusRequester(requesters[i]),
            ) { delta ->
                RemoteButtons.set(i, options[(index + delta).mod(options.size)].first)
            }
        }
    }
}

/** What a button assigned to [action] is called: the app's label, or its package if it is gone. */
private fun assignedLabel(context: android.content.Context, action: String): String {
    val pkg = RemoteButtons.packageOf(action) ?: return tr(R.string.nothing)
    val pm = context.packageManager
    return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(tr(R.string.pkg_missing, pkg))
}

/** XMB colour (by month like the PS3, or fixed) and the animation switch. */
@Composable
private fun XmbOptions() {
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth()) {
        Chip(tr(R.string.color_by_month), LauncherSettings.xmbColor < 0, Modifier.weight(1f)) { LauncherSettings.xmbColor = -1 }
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
        Toggle(tr(R.string.background_animation), LauncherSettings.bgAnimation, Modifier.weight(1f)) {
            LauncherSettings.bgAnimation = !LauncherSettings.bgAnimation
        }
    }
}
