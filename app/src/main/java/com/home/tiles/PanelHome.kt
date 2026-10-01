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
import androidx.compose.runtime.mutableStateOf
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

/** Where the projector starts, the firmware's HDMI auto switch, and a link to its HDMI page (CEC). */
@Composable
private fun HdmiSection(onHdmiPage: () -> Unit) {
    val context = LocalContext.current
    var autoSwitch by remember { mutableStateOf(Hdmi.autoSwitch()) }
    var bootHdmi by remember { mutableStateOf(Hdmi.bootToHdmi()) }
    var cec by remember { mutableStateOf(Cec.control(context)) }
    var cecWake by remember { mutableStateOf(Cec.wakeUp()) }
    Section("При включении")
    PairRow {
        Chip("Главный экран", !bootHdmi, Modifier.weight(1f)) {
            Hdmi.setBootToHdmi(context, false)
            bootHdmi = Hdmi.bootToHdmi()
        }
        Chip("HDMI", bootHdmi, Modifier.weight(1f), note = "если подключено") {
            Hdmi.setBootToHdmi(context, true)
            bootHdmi = Hdmi.bootToHdmi()
        }
    }
    autoSwitch?.let { on ->
        Spacer(Modifier.height(10.dp))
        Toggle("HDMI при подключении", on, Modifier.fillMaxWidth()) {
            Hdmi.setAutoSwitch(!on)
            autoSwitch = Hdmi.autoSwitch() ?: !on
        }
    }
    cec?.let { on ->
        Section("HDMI‑CEC")
        Toggle("Управление устройствами", on, Modifier.fillMaxWidth()) {
            Cec.setControl(context, !on)
            cec = Cec.control(context) ?: !on
            cecWake = Cec.wakeUp()
        }
        T("Нужно для ARC и пульта проектора на консоли", 14.sp, color = PanelDim)
        if (on) cecWake?.let { wake ->
            Spacer(Modifier.height(10.dp))
            Toggle("HDMI включает проектор", wake, Modifier.fillMaxWidth()) {
                Cec.setWakeUp(context, !wake)
                cecWake = Cec.wakeUp() ?: !wake
            }
            T("Консоль включает и выключает проектор", 14.sp, color = PanelDim)
        }
    }
    Spacer(Modifier.height(10.dp))
    ListRow("Другие настройки HDMI", onHdmiPage)
}

@Composable
internal fun HomePage(onHdmiPage: () -> Unit) {
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

/** Assigns the remote's four shortcut keys; left/right cycles the action, like XGIMI's selectors. */
@Composable
internal fun RemoteButtonsSection() {
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

    Section("Кнопка настроек")
    Toggle("Открывает панель Beam", LauncherSettings.settingsKeyPanel, Modifier.fillMaxWidth()) {
        LauncherSettings.settingsKeyPanel = !LauncherSettings.settingsKeyPanel
    }
    T("Вместо быстрых настроек XGIMI (они на миг мелькнут и закроются)", 14.sp, color = PanelDim)
    Section("Кнопки приложений")
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
