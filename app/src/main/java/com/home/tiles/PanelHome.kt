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

/** Where the projector starts, the firmware's HDMI auto switch, and a link to its HDMI page (CEC). */
@Composable
private fun HdmiSection(onHdmiPage: () -> Unit) {
    val context = LocalContext.current
    val autoSwitch = rememberFirmwareState { Hdmi.autoSwitch() }
    val bootState = rememberFirmwareState { Hdmi.bootToHdmi() }
    val cec = rememberFirmwareState { Cec.control(context) }
    val cecWake = rememberFirmwareState { Cec.wakeUp() }
    val bootHdmi = bootState.value ?: false
    Section(tr(R.string.on_power_on))
    PairRow {
        Chip(tr(R.string.home_screen), !bootHdmi, Modifier.weight(1f)) {
            bootState.change(false) { Hdmi.setBootToHdmi(context, false) }
        }
        Chip("HDMI", bootHdmi, Modifier.weight(1f), note = tr(R.string.if_connected)) {
            bootState.change(true) { Hdmi.setBootToHdmi(context, true) }
        }
    }
    autoSwitch.value?.let { on ->
        Spacer(Modifier.height(10.dp))
        Toggle(tr(R.string.hdmi_on_connect), on, Modifier.fillMaxWidth()) {
            autoSwitch.change(!on) { Hdmi.setAutoSwitch(!on) }
        }
    }
    cec.value?.let { on ->
        Section("HDMI‑CEC")
        Toggle(tr(R.string.hdmi_cec_control), on, Modifier.fillMaxWidth()) {
            cec.change(!on) { Cec.setControl(context, !on) }
            // Turning the control off turns the wake-up off too: ask the firmware what it ended up with.
            cecWake.value?.let { wake -> cecWake.change(if (on) false else wake) {} }
        }
        T(tr(R.string.hdmi_cec_hint), 14.sp, color = PanelDim)
        if (on) cecWake.value?.let { wake ->
            Spacer(Modifier.height(10.dp))
            Toggle(tr(R.string.hdmi_wakes), wake, Modifier.fillMaxWidth()) {
                cecWake.change(!wake) { Cec.setWakeUp(context, !wake) }
            }
            T(tr(R.string.hdmi_wakes_hint), 14.sp, color = PanelDim)
        }
    }
    Spacer(Modifier.height(10.dp))
    ListRow(tr(R.string.hdmi_more), onHdmiPage)
}

@Composable
internal fun HomePage(onHdmiPage: () -> Unit) {
    val context = LocalContext.current
    val channels by produceState(emptyList<TvChannel>()) {
        value = withContext(Dispatchers.IO) { queryTvChannels(context).filter { it.items.isNotEmpty() } }
    }
    Section(tr(R.string.show))
    Toggle(tr(R.string.now_playing), LauncherSettings.nowPlaying, Modifier.fillMaxWidth()) {
        LauncherSettings.nowPlaying = !LauncherSettings.nowPlaying
    }
    Spacer(Modifier.height(10.dp))
    Toggle(tr(R.string.usb_tile), LauncherSettings.usbTile, Modifier.fillMaxWidth()) {
        LauncherSettings.usbTile = !LauncherSettings.usbTile
    }
    Spacer(Modifier.height(10.dp))
    Toggle(tr(R.string.hdmi_tile), LauncherSettings.hdmiTile, Modifier.fillMaxWidth()) {
        LauncherSettings.hdmiTile = !LauncherSettings.hdmiTile
    }
    HdmiSection(onHdmiPage)
    Section(tr(R.string.second_row))
    PairRow {
        Chip(tr(R.string.auto), LauncherSettings.secondRow == SECOND_ROW_AUTO, Modifier.weight(1f)) {
            LauncherSettings.secondRow = SECOND_ROW_AUTO
        }
        Chip(tr(R.string.off), LauncherSettings.secondRow == SECOND_ROW_OFF, Modifier.weight(1f)) {
            LauncherSettings.secondRow = SECOND_ROW_OFF
        }
    }
    // Any number of channels, each its own row, in the order they were switched on.
    val chosen = channelRowKeys(LauncherSettings.secondRow)
    channels.forEach { channel ->
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

    Section(tr(R.string.settings_button))
    Toggle(tr(R.string.opens_beam_panel), LauncherSettings.settingsKeyPanel, Modifier.fillMaxWidth()) {
        LauncherSettings.settingsKeyPanel = !LauncherSettings.settingsKeyPanel
    }
    T(tr(R.string.settings_button_hint), 14.sp, color = PanelDim)
    Section(tr(R.string.app_buttons))
    T(tr(R.string.app_buttons_hint), 14.sp, color = PanelDim)
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
