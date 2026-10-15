@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.home.tiles

import androidx.compose.ui.focus.focusRestorer
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.composed
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val ClassicPad = 108.dp
// Wide enough that a selected tile, grown by FOCUS_SCALE, keeps some room from its neighbours.
private val ClassicGap = 28.dp
private val RowTop = 8.dp
private val NameHeight = 40.dp
private const val MoveSlideMs = 160
// Rows are taller than tiles: a little longer, so the slide reads the same.
private const val RowSlideMs = 220
// A row switched on or off folds open or shut over this long.
private const val RowFoldMs = 260
// Rows without the selection fade back to this, as Android TV's channel home does, so the one
// you're in is plain to see.
private const val DimAlpha = 0.5f
private const val RowDimMs = 220
// focusedRow's values besides channel keys.
private const val APPS_ROW = "__apps__"
private const val ROWS_BUTTON = "__rows__"

/**
 * The Switch home screen: equal-size tiles in a normally scrolling row (tiles to the left stay in
 * view), the selected one framed with its name above; the chosen channel rows underneath, and a
 * button to choose them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ClassicHome(
    repo: AppRepository,
    items: List<RowItem>,
    /** False until the apps are read: the row shows placeholder tiles meanwhile. */
    appsLoaded: Boolean,
    resumeTick: Int,
    /** See HomeScreen: whether to put the selection on the first tile. */
    takeFocus: Boolean,
    onFocusTaken: () -> Unit,
    channelRows: List<TvChannel>,
    /** Channel rows still being read, held as placeholders. */
    placeholderRows: Int,
    modifier: Modifier,
    clickFor: (RowItem) -> () -> Unit,
    longClickFor: (RowItem) -> () -> Unit,
    move: MoveControl,
    /** Moving a channel row: the row's key, ▲ ▼ steps, done and cancel. */
    rowMove: MoveControl,
    /** Holding OK on a channel's card. */
    rowMenu: (TvChannel, TvItem) -> Unit,
    /** Channels not yet seen in the rows list: the button at the end names them. */
    newRows: List<TvChannel>,
    /** Opens the rows list. */
    onRows: () -> Unit,
) {
    val listState = rememberLazyListState()
    val pageScroll = rememberScrollState()
    val scrolls = channelRows.size + placeholderRows > 1
    // A focused card settles this far under the top bar, its row's name just clear of fadeTopEdge.
    val keylinePx = with(LocalDensity.current) { Keyline.toPx() }
    val keyline = remember(keylinePx) { rowKeyline(keylinePx) }
    val first = remember { FocusRequester() }
    val movingFocus = remember { FocusRequester() }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    // The tile last selected in the row, to come back to with Back from the channel rows.
    var lastAppKey by remember { mutableStateOf<String?>(null) }
    val lastApp = remember { FocusRequester() }
    // Which row has the selection: APPS_ROW, a channel's key or ROWS_BUTTON; null while it is
    // elsewhere (the top bar), when no row is dimmed. Not read by the page itself, only by each
    // row's dimming (see rememberRowDim) and through the derived states below: moving between
    // rows doesn't recompose the whole page.
    val focusedRow = remember { mutableStateOf<String?>(null) }
    val inChannels by remember { derivedStateOf { focusedRow.value.let { it != null && it != APPS_ROW && it != ROWS_BUTTON } } }
    val onRowsButton by remember { derivedStateOf { focusedRow.value == ROWS_BUTTON } }
    // Each tile's light, in its app's colour (see TileGlow).
    val glows = rememberTileGlows()
    // The background leans towards the selected tile's colour; it fades back when the selection
    // leaves the rows or the home screen closes. In the channel rows the selected card sets it
    // itself (onTint below). Followed outside composition, so a move along a row recomposes only
    // the two tiles it changes, not the page.
    LaunchedEffect(Unit) {
        snapshotFlow { inChannels to if (inChannels) null else AmbientTint.of(focusedKey?.let { glows[it] }?.target?.value) }
            .collect { (channels, tint) -> if (!channels) AmbientTint.target.value = tint }
    }
    DisposableEffect(Unit) { onDispose { AmbientTint.target.value = null } }

    // Coming home puts focus back on the first tile, like the row layout; shown only under the
    // highlighted tab, the selection stays on the tab. Keyed on the set of tiles, not their order,
    // so moving a tile keeps the focus on it; and once the apps are read, a pending request (made
    // before they were) is carried out.
    LaunchedEffect(resumeTick, takeFocus, items.map { it.key }.toSet()) {
        if (!takeFocus || move.key != null) return@LaunchedEffect
        pageScroll.scrollTo(0)
        listState.scrollToItem(0)
        repeat(10) {
            withFrameNanos {}
            if (runCatching { first.requestFocus() }.isSuccess) {
                onFocusTaken()
                return@LaunchedEffect
            }
        }
    }

    // Back down in the channel rows (or on the button under them) goes back up to the apps, to
    // the tile last selected there, as Back does on Google TV; the page scrolls up with it.
    // Not while a row is being moved: Back cancels that (see moveKeys).
    BackHandler(enabled = inChannels || onRowsButton) {
        val target = if (lastAppKey != null && items.any { it.key == lastAppKey }) lastApp else first
        if (runCatching { target.requestFocus() }.isFailure) runCatching { first.requestFocus() }
    }

    // The moved tile keeps the focus and stays in view as it travels along the row. The list
    // anchors its scroll on the first visible tile's key, so a swap with that tile would jump the
    // whole row for a frame; the scroll from before the step is requested for the very next
    // layout instead, and the tiles slide into their new places (animateItem below). The row
    // scrolls along with each step, not after it, so fast steps don't leave it behind.
    val movingIndex = items.indexOfFirst { it.key == move.key }
    val scope = rememberCoroutineScope()
    val follower = remember(listState) { MoveFollower(listState, scope) }
    val lastMovable = items.lastIndex - 1 // "All apps" closes the row and doesn't move
    val stepping = MoveControl(move.key, { key, step ->
        val from = items.indexOfFirst { it.key == key }
        val index = listState.firstVisibleItemIndex
        val offset = listState.firstVisibleItemScrollOffset
        move.step(key, step)
        listState.requestScrollToItem(index, offset)
        if (from + step in 0..lastMovable) follower.follow(from + step)
    }, move.done, move.cancel)
    // The last tile moved: a cancelled move sends it back to where it was, maybe out of view.
    var lastMoved by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(move.key, movingIndex) {
        if (move.key == null) {
            val key = lastMoved ?: return@LaunchedEffect
            lastMoved = null
            withFrameNanos {}
            listState.bringIntoRow(items.indexOfFirst { it.key == key })
            return@LaunchedEffect
        }
        if (movingIndex < 0) return@LaunchedEffect
        lastMoved = move.key
        withFrameNanos {}
        runCatching { movingFocus.requestFocus() }
    }

    // The rows on the page, with those switched off still folding away.
    val shownRows = rememberShownRows(channelRows, loaded = placeholderRows == 0)
    // Each row's way in for the selection (its last card, see ChannelRow's focusRestorer).
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    fun rowRequester(key: String) = rowFocus.getOrPut(key) { FocusRequester() }
    // A row taken off with the selection in it (its menu's «Убрать ряд») hands the selection to
    // the row that takes its place, or the one above, or the apps. The menu is still closing then:
    // the selection is moved once the home screen has the keys again, or the system would put it
    // on the first thing it finds (the tab).
    val view = LocalView.current
    val leavingKeys = shownRows.filter { it.leaving }.map { it.channel.key }
    LaunchedEffect(leavingKeys) {
        val selectedRow = focusedRow.value
        val i = shownRows.indexOfFirst { it.leaving && it.channel.key == selectedRow }
        if (i < 0) return@LaunchedEffect
        val next = shownRows.drop(i + 1).firstOrNull { !it.leaving } ?: shownRows.take(i).lastOrNull { !it.leaving }
        var frames = 0
        do withFrameNanos {} while (!view.hasWindowFocus() && ++frames < 60)
        val target = next?.let { rowRequester(it.channel.key) } ?: if (lastAppKey != null) lastApp else first
        if (runCatching { target.requestFocus() }.isFailure) runCatching { first.requestFocus() }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        // A little over four 16:9 tiles across, 280dp wide at most on a 1280dp screen.
        val tile = ((maxWidth - ClassicPad) / 4.3f).coerceAtMost(MAX_TILE_WIDTH_DP.dp)
        // One channel row fits under the tiles; with more the page scrolls down to them (the
        // focused card scrolls itself into view, see rowKeyline) and back up when coming home.
        // The keyline is only for the page: the rows inside keep their own sideways scrolling.
        val rowSpec = LocalBringIntoViewSpec.current
        CompositionLocalProvider(LocalBringIntoViewSpec provides if (scrolls) keyline else rowSpec) {
        Column(
            if (scrolls) Modifier.fillMaxSize().verticalScroll(pageScroll) else Modifier.fillMaxSize(),
            verticalArrangement = if (scrolls) Arrangement.Top else Arrangement.Center,
        ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
            val appsDim = rememberRowDim(APPS_ROW, focusedRow)
            LazyRow(
                state = listState,
                // The tiles' lights, behind the whole row so the neighbours stay on top of them;
                // they follow the tiles as the row scrolls (read at draw time).
                // Coming back to the row (down from the top bar, up from a channel row) returns to
                // the tile last selected in it, as on Google TV, not to whichever is nearest: that
                // was often the tile half out of view at the edge, one before.
                modifier = Modifier
                    .fadeAtTop(pageScroll, alpha = { appsDim.value })
                    .onFocusChanged {
                        if (it.hasFocus) focusedRow.value = APPS_ROW else if (focusedRow.value == APPS_ROW) focusedRow.value = null
                    }
                    .focusRestorer()
                    .drawBehind {
                        val w = tile.toPx()
                        val h = tileHeight(tile).toPx()
                        for (info in listState.layoutInfo.visibleItemsInfo) {
                            val glow = glows[info.key] ?: continue
                            val s = if (move.key == info.key) FOCUS_SCALE_LIFTED else FOCUS_SCALE
                            // Item offsets count from the content's start: add the start padding back.
                            val x = info.offset - listState.layoutInfo.viewportStartOffset
                            val center = Offset(x + w / 2f, (RowTop + NameHeight).toPx() + h / 2f)
                            drawFocusGlow(glow.color.value, glow.level.value, center, w * s / 2f, h * s / 2f, FocusGlowSpread.toPx())
                        }
                    },
                contentPadding = PaddingValues(start = ClassicPad, end = ClassicPad, top = RowTop, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(ClassicGap),
            ) {
                // Before the apps are read: placeholder tiles where the row will be (under the
                // same 40dp as the name above a selected tile), so it fills in instead of popping.
                if (!appsLoaded) {
                    items(5) {
                        Column {
                            Spacer(Modifier.height(40.dp))
                            SkeletonTile(tile)
                        }
                    }
                }
                itemsIndexed(items, key = { _, item -> item.key }) { i, item ->
                    // Derived, so a move recomposes the tile it leaves and the one it reaches, not all.
                    val focused by remember(item.key) { derivedStateOf { focusedKey == item.key } }
                    val last by remember(item.key) { derivedStateOf { lastAppKey == item.key } }
                    val moving = move.key == item.key
                    // Usually known at once (the tile's picture is cached), else once it has loaded.
                    val glowColor by produceState(glowFor(repo, item), item.key) {
                        if (item is RowItem.App) repo.loadArt(item.entry)?.glow?.let { value = it }
                    }
                    RegisterTileGlow(glows, item.key, focused || moving, glowColor)
                    Column(
                        Modifier
                            // Tiles slide to their new places when the order changes; the moved
                            // one is drawn above the neighbour it passes.
                            .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = tween(MoveSlideMs))
                            .zIndex(if (moving) 1f else 0f),
                    ) {
                        // Name above the selected tile; may run wider than the tile. Not over
                        // "All apps", whose tile already says it.
                        Box(Modifier.width(tile).height(NameHeight)) {
                            if ((focused && item !is RowItem.All) || moving) {
                                T(
                                    if (moving) tr(R.string.move_hint, item.title) else item.title,
                                    22.sp,
                                    Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
                                    color = Colors.Text,
                                )
                            }
                        }
                        Tile(
                            width = tile,
                            highlighted = focused || moving,
                            lifted = moving,
                            newMark = item is RowItem.App && item.entry.isNew,
                            modifier = Modifier
                                .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                                .then(if (last) Modifier.focusRequester(lastApp) else Modifier)
                                .then(if (moving) Modifier.focusRequester(movingFocus).moveKeys(item.key, stepping) else Modifier)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        focusedKey = item.key
                                        lastAppKey = item.key
                                    } else if (focusedKey == item.key) {
                                        focusedKey = null
                                    }
                                },
                            onClick = clickFor(item),
                            onLongClick = longClickFor(item),
                        ) { RowItemArt(repo, item) }
                    }
                }
            }
            shownRows.forEachIndexed { i, shown ->
                val channel = shown.channel
                // Keyed, so a moved row takes its state (and the focused card) along; it slides to
                // its new place like a moved tile, above the row it passes.
                key(channel.key) {
                    // Opening as it's switched on, closing as it's switched off, gap and all.
                    val fold = remember { Animatable(if (shown.entering) 0f else 1f) }
                    LaunchedEffect(shown.leaving) {
                        fold.animateTo(if (shown.leaving) 0f else 1f, tween(RowFoldMs, easing = FastOutSlowInEasing))
                    }
                    val dim = rememberRowDim(channel.key, focusedRow)
                    // A clear gap between the apps and the first channel, a smaller one between channels
                    // (the selected card's title sits in it).
                    Spacer(Modifier.foldHeight { fold.value }.height(if (i == 0) 28.dp else 4.dp))
                    // Made once (per move state): a new chain each time would make the row recompose
                    // with the page.
                    val rowsMoving = rowMove.key != null
                    val moved = rowMove.key == channel.key
                    val rowModifier = remember(rowsMoving, moved) {
                        Modifier
                            .fadeAtTop(pageScroll, alpha = { dim.value * fold.value }, clip = { fold.value < 1f })
                            .foldHeight { fold.value }
                            .zIndex(if (moved) 1f else 0f)
                            .slideToPlace(enabled = rowsMoving)
                            .onFocusChanged {
                                if (it.hasFocus) focusedRow.value = channel.key else if (focusedRow.value == channel.key) focusedRow.value = null
                            }
                    }
                    // A row on its way out keeps the selection it has until it's handed on (above):
                    // taken away at once, the system would put it on the tab meanwhile.
                    ChannelRow(
                        channel, ClassicPad, i, rowMove,
                        modifier = rowModifier,
                        focus = rowRequester(channel.key),
                        onTint = { AmbientTint.target.value = AmbientTint.of(it) },
                        onMenu = { rowMenu(channel, it) },
                    )
                }
            }
            repeat(placeholderRows) { i ->
                Spacer(Modifier.height(if (i == 0 && channelRows.isEmpty()) 28.dp else 4.dp))
                ChannelRowSkeleton(ClassicPad)
            }
            Spacer(Modifier.height(if (shownRows.isEmpty() && placeholderRows == 0) 28.dp else 12.dp))
            RowsButton(
                newRows,
                rowCount = shownRows.size,
                onClick = onRows,
                modifier = Modifier
                    .padding(start = ClassicPad)
                    .onFocusChanged {
                        if (it.hasFocus) focusedRow.value = ROWS_BUTTON else if (focusedRow.value == ROWS_BUTTON) focusedRow.value = null
                    },
            )
            if (scrolls) Spacer(Modifier.height(40.dp))
        }
        }
        }
    }
}

// Where a focused card settles, from the top of the page (just under the top bar): its row's
// name (about 46dp above the card) is in full view, and the row above is just out of it.
private val Keyline = 88.dp
// A row fades out over this much of its way past the page's top (see fadeAtTop).
private val TopFade = 48.dp

/**
 * A row's alpha: full while it has the selection or no row does, [DimAlpha] otherwise. It follows
 * [focusedRow] by itself, read outside composition, so the page isn't recomposed for it; set at
 * once the first time (no fade as the home screen comes up).
 */
@Composable
private fun rememberRowDim(key: String, focusedRow: State<String?>): State<Float> {
    val dim = remember { Animatable(1f) }
    LaunchedEffect(key) {
        var first = true
        snapshotFlow { focusedRow.value.let { it == null || it == key } }.collectLatest { lit ->
            val target = if (lit) 1f else DimAlpha
            if (first) dim.snapTo(target) else dim.animateTo(target, tween(RowDimMs))
            first = false
        }
    }
    return dim.asState()
}

/**
 * The button under the rows that opens the rows list, as Android TV's «Customize channels» at the
 * bottom of its home screen; it names channels that have appeared since the list was last open.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowsButton(newRows: List<TvChannel>, rowCount: Int, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val dark = LauncherSettings.dark
    val fill = if (focused) (if (dark) FocusBg else Colors.Text) else Colors.Button
    val fg = if (focused) (if (dark) FocusText else Colors.Background) else Colors.Text
    val screenShown = LocalScreenShown.current
    // A row switched on from the list opened here pushes the button down: the page follows it.
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(rowCount) {
        if (!focused) return@LaunchedEffect
        delay(RowFoldMs.toLong())
        runCatching { bring.bringIntoView() }
    }
    Row(
        modifier
            .bringIntoViewRequester(bring)
            .height(52.dp)
            .background(fill, CircleShape)
            .focusProperties { canFocus = screenShown }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.navigate()
            }
            .clickable(remember { MutableInteractionSource() }, null) {
                Sounds.activate()
                onClick()
            }
            .padding(start = 18.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(Icons.Rounded.Tune, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(fg))
        Spacer(Modifier.width(10.dp))
        T(tr(R.string.rows_customize), 18.sp, color = fg)
        if (newRows.isNotEmpty()) {
            Spacer(Modifier.width(14.dp))
            Box(Modifier.size(8.dp).background(MarkOrange, CircleShape))
            Spacer(Modifier.width(8.dp))
            T(
                if (newRows.size == 1) tr(R.string.rows_new_one, newRows[0].label) else tr(R.string.rows_new_many, newRows.size),
                18.sp,
                color = fg,
            )
        }
    }
}

/** A channel row on the page; [leaving] ones are folding away, [entering] ones fold open. */
private class ShownRow(val channel: TvChannel, val leaving: Boolean, val entering: Boolean)

private class RowHistory(var last: List<TvChannel>, var settled: Boolean) {
    /** Rows switched off, still folding away, with the place they had. */
    val leaving = mutableListOf<Pair<Int, TvChannel>>()
    val seen = last.mapTo(HashSet()) { it.key }
}

/**
 * [rows] as the page shows them: a row switched off stays in its place while it folds shut, and
 * one switched on (not the first rows read at the start) folds open.
 */
@Composable
private fun rememberShownRows(rows: List<TvChannel>, loaded: Boolean): List<ShownRow> {
    val history = remember { RowHistory(rows, loaded) }
    var cleared by remember { mutableIntStateOf(0) }
    val shown = remember(rows, loaded, cleared) {
        val keys = rows.mapTo(HashSet()) { it.key }
        history.last.forEachIndexed { i, row ->
            if (row.key !in keys && history.leaving.none { it.second.key == row.key }) history.leaving += i to row
        }
        history.leaving.removeAll { it.second.key in keys }
        val entering = if (history.settled) keys - history.seen else emptySet()
        history.seen += keys
        if (loaded) history.settled = true
        history.last = rows
        val out = rows.mapTo(ArrayList()) { ShownRow(it, leaving = false, entering = it.key in entering) }
        history.leaving.sortedBy { it.first }.forEach { (i, row) ->
            out.add(i.coerceAtMost(out.size), ShownRow(row, leaving = true, entering = false))
        }
        out
    }
    LaunchedEffect(shown) {
        if (shown.none { it.leaving }) return@LaunchedEffect
        delay(RowFoldMs.toLong())
        // Gone: switched on again later, it folds open again.
        history.leaving.forEach { history.seen -= it.second.key }
        history.leaving.clear()
        cleared++
    }
    return shown
}

/** Lays out at [fold] of its height (1 whole, 0 none), for rows folding open and shut. */
private fun Modifier.foldHeight(fold: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val f = fold()
    val height = if (f >= 1f) placeable.height else (placeable.height * f.coerceAtLeast(0f)).roundToInt()
    layout(placeable.width, height) { placeable.place(0, 0) }
}

/**
 * Google TV style paging between rows: the page glides so the focused card's row settles on a
 * line just under the top bar ([keyline]), instead of scrolling just enough to show it. That line
 * is high enough that the row above goes all the way under the bar, not half of it. Near the top
 * it can't go above the start, so the apps row stays where it is.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun rowKeyline(keyline: Float) = object : BringIntoViewSpec {
    // A spring, not a tween: the scroll is re-aimed every frame, and a tween restarted each time
    // only creeps (it took about 2 s). Critically damped, settles in about 0.4 s.
    override val scrollAnimationSpec: AnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 600f)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - keyline
}

/**
 * A row rising past the top of the page (under the top bar) fades out as a whole over the first
 * [TopFade] of its way out, like Google TV's rows, instead of being cut on a line. Each row's own
 * alpha, applied to its drawing as it goes: a mask over the whole page needed an offscreen layer
 * every frame, and the projector's GPU missed 60% of frames on the channel rows with it. [alpha]
 * (a row without the selection, or folding) goes into the same layer, read at draw time; [clip]
 * cuts a folding row to its shrinking height.
 */
private fun Modifier.fadeAtTop(scroll: ScrollState, alpha: () -> Float = { 1f }, clip: () -> Boolean = { false }): Modifier = composed {
    // The row's top in the page's content: the scroll is subtracted at draw time.
    var top by remember { mutableFloatStateOf(0f) }
    this
        .onPlaced { top = it.positionInParent().y }
        .graphicsLayer {
            val y = top - scroll.value
            val fade = if (y >= 0f) 1f else (1f + y / TopFade.toPx()).coerceIn(0f, 1f)
            this.alpha = fade * alpha()
            this.clip = clip()
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
}

/**
 * Scrolls the row along with a moving tile. Each step aims at where the row must be for the
 * tile's new place, counting from where an unfinished scroll was heading, so quick steps add up
 * instead of waiting for each other. Tiles are equal, so a place is index × (tile + gap).
 */
private class MoveFollower(private val list: LazyListState, private val scope: CoroutineScope) {
    private var job: Job? = null
    private var target: Float? = null

    fun follow(index: Int) {
        val info = list.layoutInfo
        val tile = info.visibleItemsInfo.firstOrNull()?.size ?: return
        val stride = (tile + info.mainAxisItemSpacing).toFloat()
        fun scrolled() = list.firstVisibleItemIndex * stride + list.firstVisibleItemScrollOffset
        val tileStart = index * stride
        // The least scroll that shows the tile's right edge, and the most that shows its left.
        val least = info.beforeContentPadding + tileStart + tile - info.viewportSize.width + info.afterContentPadding
        val base = target ?: scrolled()
        val wanted = base.coerceAtLeast(least).coerceAtMost(tileStart)
        if (target == null && wanted == base) return
        job?.cancel()
        target = wanted
        job = scope.launch {
            // Frame callbacks run before layout, so the scroll requested for this step is in
            // place only from the second frame on; measuring earlier aimed from a stale spot.
            withFrameNanos {}
            withFrameNanos {}
            list.animateScrollBy(wanted - scrolled(), tween(MoveSlideMs))
            target = null
            // Whatever cut the scroll short, the tile ends up fully in view.
            list.bringIntoRow(index)
        }
    }
}

/** Scrolls the row just enough to show the whole tile at [index] inside the row's padding. */
private suspend fun LazyListState.bringIntoRow(index: Int) {
    if (index < 0) return
    val info = layoutInfo
    val shown = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (shown == null) {
        animateScrollToItem(index)
        return
    }
    val start = info.viewportStartOffset + info.beforeContentPadding
    val end = info.viewportEndOffset - info.afterContentPadding
    when {
        shown.offset < start -> animateScrollBy((shown.offset - start).toFloat(), tween(MoveSlideMs))
        shown.offset + shown.size > end -> animateScrollBy((shown.offset + shown.size - end).toFloat(), tween(MoveSlideMs))
    }
}

/**
 * Glides to a new place in the parent instead of jumping there, like the tiles' animateItem (the
 * channel rows are a plain column, not a lazy list). Only while [enabled] (a row is being moved),
 * so rows loading in or going away don't drift into place.
 */
private fun Modifier.slideToPlace(enabled: Boolean): Modifier = composed {
    val scope = rememberCoroutineScope()
    val animate by rememberUpdatedState(enabled)
    // Where the layout puts it, and where it is drawn (animated towards that).
    var target by remember { mutableStateOf(IntOffset.Zero) }
    var drawn by remember { mutableStateOf<Animatable<IntOffset, AnimationVector2D>?>(null) }
    this
        // Called in the same pass, before the offset below is placed: the very frame the row
        // lands in its new place it is still drawn at the old one, with no frame in between.
        .onPlaced { target = it.positionInParent().round() }
        .offset {
            val current = drawn
            if (!animate && current?.isRunning != true) {
                drawn = null
                return@offset IntOffset.Zero
            }
            val anim = current ?: Animatable(target, IntOffset.VectorConverter).also { drawn = it }
            if (anim.targetValue != target) scope.launch { anim.animateTo(target, tween(RowSlideMs)) }
            anim.value - target
        }
}

/**
 * The keys of a tile being moved: left/right move it, OK puts it down, Back puts it back where it
 * was. Up/down are swallowed so the focus can't leave the tile mid-move.
 * Keys while moving [key]: ◀ ▶ step it (▲ ▼ for a [vertical] list, like the channel rows).
 */
internal fun Modifier.moveKeys(key: String, move: MoveControl, vertical: Boolean = false) = onPreviewKeyEvent { event ->
    val down = event.type == KeyEventType.KeyDown
    val up = event.type == KeyEventType.KeyUp
    val back = if (vertical) AndroidKeyEvent.KEYCODE_DPAD_UP else AndroidKeyEvent.KEYCODE_DPAD_LEFT
    val forth = if (vertical) AndroidKeyEvent.KEYCODE_DPAD_DOWN else AndroidKeyEvent.KEYCODE_DPAD_RIGHT
    when (event.nativeKeyEvent.keyCode) {
        back -> { if (down) move.step(key, -1); true }
        forth -> { if (down) move.step(key, 1); true }
        AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN,
        AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> true
        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
            if (up) move.done()
            true
        }
        AndroidKeyEvent.KEYCODE_BACK, AndroidKeyEvent.KEYCODE_ESCAPE -> {
            if (up) move.cancel()
            true
        }
        else -> false
    }
}
