package com.home.tiles

import android.view.KeyEvent as AndroidKeyEvent
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ClassicPad = 108.dp
private val ClassicGap = 14.dp

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

    // The moved tile keeps the focus and stays in view as it travels along the row.
    val movingIndex = items.indexOfFirst { it.key == move.key }
    LaunchedEffect(move.key, movingIndex) {
        if (move.key == null || movingIndex < 0) return@LaunchedEffect
        withFrameNanos {}
        runCatching { movingFocus.requestFocus() }
        val info = listState.layoutInfo
        val shown = info.visibleItemsInfo.firstOrNull { it.index == movingIndex }
        when {
            shown == null || shown.offset < info.viewportStartOffset -> listState.animateScrollToItem(movingIndex)
            shown.offset + shown.size > info.viewportEndOffset -> listState.animateScrollToItem((movingIndex - 3).coerceAtLeast(0))
        }
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
                    Column {
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
                                .then(if (moving) Modifier.focusRequester(movingFocus).moveKeys(item.key, move) else Modifier)
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
