package com.home.tiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp


// The row is centred in the space under the top bar; this lifts it to just above the screen's middle.
private val RowLift = 150.dp

/** One tile in the home row. Special tiles (drive, HDMI) come before the apps. */
internal sealed class RowItem(val key: String, val title: String, val subtitle: String = "") {
    class App(val entry: AppEntry) : RowItem(entry.pkg, entry.label)
    class Usb(val drive: UsbDrive) : RowItem("usb:${drive.key}", tr(R.string.usb_drive), drive.label)
    class Hdmi(val input: Xgimi.Input) : RowItem("hdmi:${input.id}", input.label, if (input.device != null) "HDMI" else tr(R.string.device_connected))
    class All(count: Int) : RowItem("__all__", tr(R.string.all_apps), tr(R.string.apps_count, count))
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    repo: AppRepository,
    loadedApps: List<AppEntry>?,
    resumeTick: Int,
    onOpenAll: () -> Unit,
    onOpenPanel: () -> Unit,
    onMenu: (MenuRequest) -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val apps = loadedApps.orEmpty()
    val channels by rememberTvChannels(resumeTick)
    val channelRows = pickChannelRows(channels.orEmpty(), LauncherSettings.secondRow)
    // Until the channels are read, the chosen rows' places are held by placeholders, so the
    // apps row doesn't jump when they arrive.
    val placeholderRows = if (channels != null) 0 else when (LauncherSettings.secondRow) {
        SECOND_ROW_OFF -> 0
        SECOND_ROW_AUTO -> 1
        else -> channelRowKeys(LauncherSettings.secondRow).size
    }
    LaunchedEffect(resumeTick) { context.requestChannelRefresh() }
    val drives by rememberUsbDrives()
    val hdmi by rememberLiveHdmi()
    val rowItems = buildList {
        if (LauncherSettings.usbTile) drives.forEach { add(RowItem.Usb(it)) }
        if (LauncherSettings.hdmiTile) hdmi.forEach { add(RowItem.Hdmi(it)) }
        apps.filter { !it.hidden }.forEach { add(RowItem.App(it)) }
    }
    // The order set with "Move"; "All apps" always closes the row. Before the apps are read the
    // row is placeholders (see ClassicHome).
    val byKey = rowItems.associateBy { it.key }
    val items = if (loadedApps == null) emptyList() else repo.arrange(rowItems.map { it.key }).mapNotNull { byKey[it] } + RowItem.All(apps.size)
    val keys = items.map { it.key }

    // Move mode: the tile being moved and the order to go back to if it's cancelled.
    var moving by remember { mutableStateOf<String?>(null) }
    var orderBeforeMove by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(resumeTick) { moving = null }
    fun startMove(key: String) {
        orderBeforeMove = repo.beginMove(keys.filter { it != ALL_KEY })
        moving = key
    }
    fun moveStep(key: String, step: Int) {
        if (repo.move(keys.filter { it != ALL_KEY }, key, step)) Sounds.navigate()
    }
    fun moveDone() {
        moving = null
        orderBeforeMove = null
        Sounds.activate()
    }
    fun moveCancel() {
        orderBeforeMove?.let { repo.restoreOrder(it) }
        moving = null
        orderBeforeMove = null
    }

    fun clickFor(item: RowItem): () -> Unit = when (item) {
        is RowItem.App -> { { context.launchApp(item.entry) } }
        is RowItem.Usb -> { { context.launchPackage(FILE_MANAGER) } }
        is RowItem.Hdmi -> { { Xgimi.openInput(context, item.input) } }
        is RowItem.All -> onOpenAll
    }
    fun longClickFor(item: RowItem): () -> Unit = {
        when (item) {
            is RowItem.App -> onMenu(appMenu(context, repo, item.entry, onChanged, onMove = { startMove(item.key) }))
            is RowItem.Usb, is RowItem.Hdmi -> onMenu(
                MenuRequest(
                    title = item.title,
                    art = { RowItemArt(repo, item) },
                    options = listOf(
                        tr(R.string.menu_open) to clickFor(item),
                        tr(R.string.menu_move) to { startMove(item.key) },
                        // These tiles come back with their switch in Beam settings, "Home screen".
                        tr(R.string.menu_hide_home) to {
                            if (item is RowItem.Usb) LauncherSettings.usbTile = false else LauncherSettings.hdmiTile = false
                        },
                    ),
                ),
            )
            is RowItem.All -> {}
        }
    }
    BackHandler {}

    Column(Modifier.fillMaxSize()) {
        // With a channel row below there is no spare height to lift into.
        val lift = if (channelRows.isNotEmpty() || placeholderRows > 0) 0.dp else RowLift
        TopBar(onOpenAll, onOpenPanel)
        // The Switch layout: equal tiles in a normally scrolling row.
        ClassicHome(
            repo, items, loadedApps != null, resumeTick, channelRows, placeholderRows,
            Modifier.weight(1f).padding(bottom = lift), ::clickFor, ::longClickFor,
            MoveControl(moving, ::moveStep, ::moveDone, ::moveCancel),
        )
    }
}

/** Move mode as the row sees it: [key] is the tile being moved, null when not moving. */
internal class MoveControl(
    val key: String?,
    val step: (key: String, step: Int) -> Unit,
    val done: () -> Unit,
    val cancel: () -> Unit,
)

private const val ALL_KEY = "__all__"

@Composable
internal fun RowItemArt(repo: AppRepository, item: RowItem) {
    when (item) {
        is RowItem.App -> AppTileArt(repo, item.entry)
        is RowItem.Usb -> UsbArt()
        is RowItem.Hdmi -> HdmiArt()
        is RowItem.All -> AllAppsArt()
    }
}

@Composable
private fun TopBar(onOpenAll: () -> Unit, onOpenPanel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 60.dp, top = 30.dp)
            .height(100.dp),
    ) {
        ActionButtons(onOpenAll, onOpenPanel, Modifier.padding(top = 2.dp))
        Spacer(Modifier.weight(1f))
        val nowPlaying by rememberNowPlaying()
        nowPlaying?.takeIf { LauncherSettings.nowPlaying }?.let {
            NowPlayingBar(it)
            Spacer(Modifier.width(32.dp))
        }
        Row(Modifier.height(66.dp), verticalAlignment = Alignment.CenterVertically) {
            // Clock, network and battery share one text size, icon height and spacing.
            Clock(StatusTextSize)
            Spacer(Modifier.width(StatusGap))
            VpnIcon()
            NetworkIcon()
            Spacer(Modifier.width(StatusGap))
            BatteryIndicator()
        }
    }
}

/** The round shortcut buttons in the top bar. */
@Composable
internal fun ActionButtons(onOpenAll: () -> Unit, onOpenPanel: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp), modifier = modifier) {
        RoundButton(Icons.Rounded.Apps, Color(0xFF1E88E5), tr(R.string.all_apps), onOpenAll)
        RoundButton(Icons.Rounded.Tune, Color(0xFF2EB85C), tr(R.string.quick_settings)) { context.openQuickPanel() }
        RoundButton(Icons.Rounded.Settings, Color(0xFF8A8A8A), tr(R.string.settings)) { context.openSettings() }
        RoundButton(Icons.Rounded.SettingsSuggest, Color(0xFF8E44AD), tr(R.string.beam_settings), onOpenPanel)
    }
}

private const val FILE_MANAGER = "com.xgimi.filemanager"
