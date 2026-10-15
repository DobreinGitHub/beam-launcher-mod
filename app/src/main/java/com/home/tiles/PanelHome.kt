package com.home.tiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

@Composable
internal fun HomePage() {
    val context = LocalContext.current
    val channels by produceState(emptyList<TvChannel>()) {
        value = withContext(Dispatchers.IO) { queryTvChannels(context).filter { it.items.isNotEmpty() } }
    }
    Section(tr(R.string.show))
    Toggle(tr(R.string.now_playing), LauncherSettings.nowPlaying, Modifier.fillMaxWidth()) {
        LauncherSettings.nowPlaying = !LauncherSettings.nowPlaying
    }
    Section(tr(R.string.second_row))
    PairRow {
        Chip(tr(R.string.auto), LauncherSettings.secondRow == SECOND_ROW_AUTO, Modifier.weight(1f)) {
            LauncherSettings.secondRow = SECOND_ROW_AUTO
        }
        Chip(tr(R.string.off), LauncherSettings.secondRow == SECOND_ROW_OFF, Modifier.weight(1f)) {
            LauncherSettings.secondRow = SECOND_ROW_OFF
        }
    }
    // Any number of channels, each its own row. The chosen ones come first, in their order on
    // the home screen (changed there: hold OK on a card → "Move row"); new ones join the end.
    val chosen = channelRowKeys(LauncherSettings.secondRow)
    T(tr(R.string.channel_rows_hint), 14.sp, color = PanelDim, maxLines = 3)
    channels.sortedBy { chosen.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }.forEach { channel ->
        Spacer(Modifier.height(10.dp))
        Toggle(channel.name, channel.key in chosen, Modifier.fillMaxWidth()) {
            LauncherSettings.secondRow = toggleChannelRow(LauncherSettings.secondRow, channel.key)
        }
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
