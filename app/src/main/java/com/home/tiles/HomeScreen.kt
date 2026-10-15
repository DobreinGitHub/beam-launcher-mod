package com.home.tiles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp


// The row is centred in the space under the top bar; this lifts it to just above the screen's middle.
private val RowLift = 150.dp

/** One tile in the home row: the apps, then "All apps". Connected devices are in the top bar. */
internal sealed class RowItem(val key: String, val title: String, val subtitle: String = "") {
    class App(val entry: AppEntry) : RowItem(entry.pkg, entry.label)
    class All(count: Int) : RowItem("__all__", tr(R.string.all_apps), tr(R.string.apps_count, count))
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    repo: AppRepository,
    loadedApps: List<AppEntry>?,
    resumeTick: Int,
    /** Put the selection on the first tile (coming home, or OK on the tab); not when only shown under the tab. */
    takeFocus: Boolean,
    onFocusTaken: () -> Unit,
    onOpenAll: () -> Unit,
    onMenu: (MenuRequest) -> Unit,
    onChanged: () -> Unit,
    /** Opens the rows list (Settings → Home screen). */
    onRows: () -> Unit,
) {
    val context = LocalContext.current
    val apps = loadedApps.orEmpty()
    val channels by rememberTvChannels(resumeTick)
    val channelRows = pickChannelRows(channels.orEmpty(), LauncherSettings.secondRow)
    // Channels that turned up since the rows list was last open, and aren't on the page already.
    LaunchedEffect(channels) {
        val read = channels ?: return@LaunchedEffect
        if (LauncherSettings.seenChannels == null) LauncherSettings.markChannelsSeen(read.map { it.key })
    }
    val seen = LauncherSettings.seenChannels
    val newRows = if (seen == null) emptyList() else channels.orEmpty().filter {
        it.items.isNotEmpty() && it.key !in seen && channelRows.none { row -> row.key == it.key }
    }
    // Until the channels are read, the chosen rows' places are held by placeholders, so the
    // apps row doesn't jump when they arrive.
    val placeholderRows = if (channels != null) 0 else when (LauncherSettings.secondRow) {
        SECOND_ROW_OFF -> 0
        SECOND_ROW_AUTO -> 1
        else -> channelRowKeys(LauncherSettings.secondRow).size
    }
    LaunchedEffect(resumeTick) { context.requestChannelRefresh() }
    val rowItems = apps.filter { !it.hidden }.map { RowItem.App(it) }
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

    // Moving a channel row: its key and the rows to go back to if it's cancelled.
    var movingRow by remember { mutableStateOf<String?>(null) }
    var rowsBeforeMove by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resumeTick) { movingRow = null }
    fun rowStep(key: String, step: Int) {
        val setting = LauncherSettings.secondRow
        val next = moveChannelRow(setting, key, step, channelRows.map { it.key })
        if (next != setting) {
            LauncherSettings.secondRow = next
            Sounds.navigate()
        }
    }
    fun rowDone() {
        movingRow = null
        rowsBeforeMove = null
        Sounds.activate()
    }
    fun rowCancel() {
        rowsBeforeMove?.let { LauncherSettings.secondRow = it }
        movingRow = null
        rowsBeforeMove = null
    }
    fun rowMenu(channel: TvChannel, item: TvItem) = onMenu(
        MenuRequest(
            title = channel.label,
            art = { TvItemArt(item) },
            options = buildList<Pair<String, () -> Unit>> {
                add(tr(R.string.menu_open) to { context.openTvItem(item) })
                // One row (or the automatic one) has nothing to swap with.
                if (channelRows.size > 1) add(tr(R.string.menu_move_row) to {
                    rowsBeforeMove = LauncherSettings.secondRow
                    movingRow = channel.key
                })
                // It comes back with its switch in the rows list.
                add(tr(R.string.menu_hide_row) to {
                    LauncherSettings.secondRow = hideChannelRow(LauncherSettings.secondRow, channel.key)
                })
                add(tr(R.string.rows_customize) to onRows)
            },
        ),
    )

    fun clickFor(item: RowItem): () -> Unit = when (item) {
        is RowItem.App -> { { context.launchApp(item.entry) } }
        is RowItem.All -> onOpenAll
    }
    fun longClickFor(item: RowItem): () -> Unit = {
        when (item) {
            is RowItem.App -> onMenu(appMenu(context, repo, item.entry, onChanged, onMove = { startMove(item.key) }))
            is RowItem.All -> {}
        }
    }
    BackHandler {}

    Column(Modifier.fillMaxSize()) {
        // With a channel row below there is no spare height to lift into.
        val lift = if (channelRows.isNotEmpty() || placeholderRows > 0) 0.dp else RowLift
        // The Switch layout: equal tiles in a normally scrolling row (the top bar is over it, see LauncherApp).
        ClassicHome(
            repo, items, loadedApps != null, resumeTick, takeFocus, onFocusTaken, channelRows, placeholderRows,
            Modifier.weight(1f).padding(bottom = lift), ::clickFor, ::longClickFor,
            MoveControl(moving, ::moveStep, ::moveDone, ::moveCancel),
            MoveControl(movingRow, ::rowStep, ::rowDone, ::rowCancel),
            ::rowMenu,
            newRows,
            onRows,
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

/**
 * The colour a row tile glows in while selected: its app's (once its picture has loaded), or neutral.
 */
internal fun glowFor(repo: AppRepository, item: RowItem): Color = when (item) {
    is RowItem.App -> repo.cachedArt(item.entry)?.glow ?: Colors.Text
    is RowItem.All -> Colors.Text
}

@Composable
internal fun RowItemArt(repo: AppRepository, item: RowItem) {
    when (item) {
        is RowItem.App -> AppTileArt(repo, item.entry)
        is RowItem.All -> AllAppsArt(item.title, item.subtitle)
    }
}

