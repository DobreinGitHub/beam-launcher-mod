package com.home.tiles

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val StartPad = 110.dp

// The row is centred in the space under the top bar; this lifts it to just above the screen's middle.
private val RowLift = 150.dp
private val Gap = 26.dp
private val RowPad = 14.dp

/** One tile in the home row. Special tiles (drive, HDMI) come before the apps. */
internal sealed class RowItem(val key: String, val title: String, val subtitle: String = "") {
    class App(val entry: AppEntry) : RowItem(entry.pkg, entry.label)
    class Usb(val drive: UsbDrive) : RowItem("usb:${drive.key}", "Флешка", drive.label)
    class Hdmi(val input: Xgimi.Input) : RowItem("hdmi:${input.id}", input.label, "Подключено устройство")
    class All(count: Int) : RowItem("__all__", "Все приложения", "Приложений: $count")
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    repo: AppRepository,
    apps: List<AppEntry>,
    resumeTick: Int,
    onOpenAll: () -> Unit,
    onOpenPanel: () -> Unit,
    onOptions: (AppEntry) -> Unit,
) {
    val context = LocalContext.current
    val channels by rememberTvChannels(resumeTick)
    val secondRow = pickSecondRow(channels, LauncherSettings.secondRow)
    val showContinue = secondRow != null
    LaunchedEffect(resumeTick) { context.requestChannelRefresh() }
    // With the second row there is only room for slightly smaller large tiles.
    val bigTile = if (LauncherSettings.largeTiles) (if (showContinue) 330.dp else 360.dp) else 320.dp
    val smallTile = if (LauncherSettings.largeTiles) (if (showContinue) 200.dp else 220.dp) else 190.dp
    val drives by rememberUsbDrives()
    val hdmi by rememberLiveHdmi()
    val items = buildList {
        if (LauncherSettings.usbTile) drives.forEach { add(RowItem.Usb(it)) }
        if (LauncherSettings.hdmiTile) hdmi.forEach { add(RowItem.Hdmi(it)) }
        apps.filter { !it.hidden }.forEach { add(RowItem.App(it)) }
        add(RowItem.All(apps.size))
    }
    val keys = items.map { it.key }
    fun clickFor(item: RowItem): () -> Unit = when (item) {
        is RowItem.App -> { { context.launchApp(item.entry) } }
        is RowItem.Usb -> { { context.launchPackage(FILE_MANAGER) } }
        is RowItem.Hdmi -> { { Xgimi.openInput(context, item.input) } }
        is RowItem.All -> onOpenAll
    }
    fun longClickFor(item: RowItem): () -> Unit = { if (item is RowItem.App) onOptions(item.entry) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    var selected by remember { mutableIntStateOf(0) }
    var rowFocused by remember { mutableStateOf(false) }

    BackHandler {}

    // Coming home, the list reordering, or a drive/HDMI tile appearing puts focus on the first tile.
    LaunchedEffect(resumeTick, keys) {
        selected = 0
        // The tile may not be attached until a frame or two after the list changes.
        repeat(10) {
            withFrameNanos {}
            if (runCatching { requester(keys.first()).requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Switch layout: equal tiles in a normally scrolling row.
        val classic = LauncherSettings.layout == LAYOUT_CLASSIC
        // With a channel row below there is no spare height to lift into.
        val lift = if (secondRow != null) 0.dp else RowLift
        TopBar(onOpenAll, onOpenPanel)

        if (classic) {
            ClassicHome(repo, items, resumeTick, secondRow, Modifier.weight(1f).padding(bottom = lift), ::clickFor, ::longClickFor)
        } else Column(Modifier.weight(1f).fillMaxWidth().padding(bottom = lift), verticalArrangement = Arrangement.Center) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(bigTile + RowPad * 2),
            ) {
                // Not a lazy list: the whole strip slides so the selected tile always sits at StartPad
                // (every tile before it is small), instead of fighting focus bring-into-view scrolling.
                val offset by animateDpAsState(StartPad - (smallTile + Gap) * selected, tween(180), label = "slide")
                Row(
                    Modifier
                        .fillMaxHeight()
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .offset(x = offset)
                        .padding(vertical = RowPad)
                        .onFocusChanged { rowFocused = it.hasFocus }
                        .focusProperties { enter = { requester(keys.getOrElse(selected) { keys.first() }) } }
                        .focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(Gap),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    items.forEachIndexed { i, item ->
                        key(item.key) {
                            RowTile(
                                i, selected, rowFocused, bigTile, smallTile, Modifier.focusRequester(requester(item.key)),
                                onFocused = { selected = i },
                                onClick = clickFor(item),
                                onLongClick = longClickFor(item),
                            ) { RowItemArt(repo, item) }
                        }
                    }
                }

                // Title sits to the right of the big tile, above the small ones.
                val item = items.getOrNull(selected)
                val titleAlpha by animateFloatAsState(if (rowFocused) 1f else 0f, tween(150), label = "title")
                Column(
                    Modifier
                        .padding(start = StartPad + bigTile + Gap + 4.dp, top = RowPad + 6.dp, end = 48.dp)
                        .graphicsLayer { alpha = titleAlpha },
                ) {
                    T(item?.title.orEmpty(), 46.sp, weight = FontWeight.Light)
                    Spacer(Modifier.height(6.dp))
                    T(item?.subtitle.orEmpty(), 20.sp, color = Colors.TextDim)
                }
            }
            if (secondRow != null) {
                Spacer(Modifier.height(6.dp))
                ChannelRow(secondRow, StartPad)
            }
        }

    }
}

@Composable
internal fun RowItemArt(repo: AppRepository, item: RowItem) {
    when (item) {
        is RowItem.App -> {
            val art by rememberArt(repo, item.entry)
            AppArt(art, item.entry.label)
        }
        is RowItem.Usb -> UsbArt()
        is RowItem.Hdmi -> HdmiArt()
        is RowItem.All -> AllAppsArt()
    }
}

@Composable
private fun RowTile(
    index: Int,
    selected: Int,
    rowFocused: Boolean,
    bigTile: Dp,
    smallTile: Dp,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val isSelected = index == selected
    val size by animateDpAsState(if (isSelected) bigTile else smallTile, tween(160), label = "size")
    // Tiles that scrolled past the big one fade out so the left edge stays clean.
    val alpha by animateFloatAsState(if (index < selected) 0f else 1f, tween(160), label = "fade")
    Tile(
        size = size,
        highlighted = isSelected && rowFocused,
        modifier = modifier
            .graphicsLayer { this.alpha = alpha }
            .onFocusChanged { if (it.isFocused) onFocused() },
        onClick = onClick,
        onLongClick = onLongClick,
        content = content,
    )
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
        RoundButton(Icons.Rounded.Apps, Color(0xFF1E88E5), "Все приложения", onOpenAll)
        // Prefer the TV build of RuStore; fall back to the phone one.
        listOf(RUSTORE_TV, RUSTORE).firstOrNull { context.isInstalled(it) }?.let { store ->
            RoundButton(Icons.Rounded.ShoppingBag, Color(0xFFF5A623), "RuStore") { context.launchPackage(store) }
        }
        RoundButton(Icons.Rounded.Tune, Color(0xFF2EB85C), "Быстрые настройки") { context.openQuickPanel() }
        RoundButton(Icons.Rounded.Settings, Color(0xFF8A8A8A), "Настройки") { context.openSettings() }
        RoundButton(Icons.Rounded.Palette, Color(0xFF8E44AD), "Оформление", onOpenPanel)
    }
}

private const val RUSTORE = "ru.vk.store"
private const val RUSTORE_TV = "ru.vk.store.tv"
private const val FILE_MANAGER = "com.xgimi.filemanager"
