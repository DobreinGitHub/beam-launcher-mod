package com.home.tiles

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ClassicPad = 108.dp
private val ClassicGap = 14.dp
private const val MoveSlideMs = 160

/**
 * The Switch home screen: equal-size tiles in a normally scrolling row (tiles to the left stay in
 * view), the selected one framed with its name above; the channel row, if any, underneath.
 */
@Composable
internal fun ClassicHome(
    repo: AppRepository,
    items: List<RowItem>,
    resumeTick: Int,
    secondRow: TvChannel?,
    modifier: Modifier,
    clickFor: (RowItem) -> () -> Unit,
    longClickFor: (RowItem) -> () -> Unit,
    move: MoveControl,
) {
    val listState = rememberLazyListState()
    val first = remember { FocusRequester() }
    val movingFocus = remember { FocusRequester() }
    var focusedKey by remember { mutableStateOf<String?>(null) }

    // Coming home puts focus back on the first tile, like the row layout. Keyed on the set of
    // tiles, not their order, so moving a tile keeps the focus on it.
    LaunchedEffect(resumeTick, items.map { it.key }.toSet()) {
        if (move.key != null) return@LaunchedEffect
        listState.scrollToItem(0)
        repeat(10) {
            withFrameNanos {}
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
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

    BoxWithConstraints(modifier.fillMaxWidth()) {
        // About four and a half tiles across, 256dp at most like the Switch on a 1280dp screen.
        val tile = ((maxWidth - ClassicPad) / 4.5f).coerceAtMost(256.dp)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(start = ClassicPad, end = ClassicPad, top = 8.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(ClassicGap),
            ) {
                itemsIndexed(items, key = { _, item -> item.key }) { i, item ->
                    val focused = focusedKey == item.key
                    val moving = move.key == item.key
                    Column(
                        Modifier
                            // Tiles slide to their new places when the order changes; the moved
                            // one is drawn above the neighbour it passes.
                            .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = tween(MoveSlideMs))
                            .zIndex(if (moving) 1f else 0f),
                    ) {
                        // Name above the selected tile; may run wider than the tile.
                        Box(Modifier.width(tile).height(40.dp)) {
                            if (focused || moving) {
                                T(
                                    if (moving) tr(R.string.move_hint, item.title) else item.title,
                                    22.sp,
                                    Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
                                    color = Colors.Accent,
                                )
                            }
                        }
                        Tile(
                            size = tile,
                            highlighted = focused || moving,
                            lifted = moving,
                            modifier = Modifier
                                .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                                .then(if (moving) Modifier.focusRequester(movingFocus).moveKeys(item.key, stepping) else Modifier)
                                .onFocusChanged {
                                    if (it.isFocused) focusedKey = item.key
                                    else if (focusedKey == item.key) focusedKey = null
                                },
                            onClick = clickFor(item),
                            onLongClick = longClickFor(item),
                        ) { RowItemArt(repo, item) }
                    }
                }
            }
            if (secondRow != null) {
                Spacer(Modifier.height(10.dp))
                ChannelRow(secondRow, ClassicPad)
            }
        }
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
            withFrameNanos {} // after the scroll requested for this step is in place
            list.animateScrollBy(wanted - scrolled(), tween(MoveSlideMs))
            target = null
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
 * The keys of a tile being moved: left/right move it, OK puts it down, Back puts it back where it
 * was. Up/down are swallowed so the focus can't leave the tile mid-move.
 */
private fun Modifier.moveKeys(key: String, move: MoveControl) = onPreviewKeyEvent { event ->
    val down = event.type == KeyEventType.KeyDown
    val up = event.type == KeyEventType.KeyUp
    when (event.nativeKeyEvent.keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (down) move.step(key, -1); true }
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) move.step(key, 1); true }
        AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> true
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
