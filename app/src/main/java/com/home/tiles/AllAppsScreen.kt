@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.home.tiles

import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity

// The grid lines up with the home screen: the rows' left edge and their gap between tiles.
private val GridStart = 108.dp
private val GridEnd = 108.dp
private const val Columns = 5

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AllAppsScreen(
    repo: AppRepository,
    apps: List<AppEntry>,
    /** Put the selection on the first app (opened from the home row's tile, or OK on the tab). */
    takeFocus: Boolean,
    onFocusTaken: () -> Unit,
    onOptions: (AppEntry) -> Unit,
) {
    val context = LocalContext.current
    // The apps on the home screen first, then the hidden ones; each by name.
    val sorted = remember(apps) { apps.sortedWith(compareBy({ it.hidden }, { it.label.lowercase() })) }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    // Shown only under the highlighted tab, the selection stays on the tab.
    LaunchedEffect(takeFocus) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos {}
        runCatching { first.requestFocus() }
        onFocusTaken()
    }

    // The top bar is over this screen (see LauncherApp); Back is handled there too.
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        // Equal tiles filling the width between the home rows' margins.
        val tile = (maxWidth - GridStart - GridEnd - ColumnGap * (Columns - 1)) / Columns
        val tileH = tileHeight(tile)
        // The tiles' lights, behind the whole grid like on the home row.
        val glows = rememberTileGlows()
        val density = LocalDensity.current
        val keyline = remember(density, tile) { GridKeyline(with(density) { (GridTop + tileH + RowGap + tileH / 2).toPx() }) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides keyline) {
        LazyVerticalGrid(
            state = gridState,
            // Down from the top bar returns to the app last selected here.
            modifier = Modifier.focusRestorer().drawBehind {
                val w = tile.toPx()
                val h = tileH.toPx()
                for (info in gridState.layoutInfo.visibleItemsInfo) {
                    val glow = glows[info.key] ?: continue
                    // Item offsets count from the content's start: add the paddings back.
                    val center = Offset(
                        info.offset.x + GridStart.toPx() + info.size.width / 2f,
                        info.offset.y - gridState.layoutInfo.viewportStartOffset + h / 2f,
                    )
                    drawFocusGlow(glow.color.value, glow.level.value, center, w * FOCUS_SCALE / 2f, h * FOCUS_SCALE / 2f, FocusGlowSpread.toPx())
                }
            },
            columns = GridCells.Fixed(Columns),
            contentPadding = PaddingValues(GridStart, GridTop, GridEnd, 40.dp),
            horizontalArrangement = Arrangement.spacedBy(ColumnGap),
            verticalArrangement = Arrangement.spacedBy(RowGap),
        ) {
            itemsIndexed(sorted, key = { _, e -> e.pkg }) { i, entry ->
                val focused = focusedKey == entry.pkg
                val glowColor by produceState(repo.cachedArt(entry)?.glow ?: Colors.Text, entry.pkg) {
                    repo.loadArt(entry)?.glow?.let { value = it }
                }
                RegisterTileGlow(glows, entry.pkg, focused, glowColor)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Tile(
                        width = tile,
                        highlighted = focused,
                        hiddenMark = entry.hidden,
                        modifier = Modifier
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            // Cleared when the selection leaves for the top bar, so no tile stays lit.
                            .onFocusChanged {
                                if (it.isFocused) focusedKey = entry.pkg
                                else if (focusedKey == entry.pkg) focusedKey = null
                            },
                        onClick = { context.launchApp(entry) },
                        onLongClick = { onOptions(entry) },
                    ) { AppTileArt(repo, entry) }
                    // The name only under the selected tile, like Google TV, in the gap to the next
                    // row: offset, so it takes no room and the rows stay put.
                    if (focused) {
                        T(
                            entry.label,
                            17.sp,
                            Modifier.fillMaxWidth().offset(y = tileH + NameGap),
                            align = TextAlign.Center,
                        )
                    }
                }
            }
        }
        }
        }
    }
}

private val GridTop = 14.dp
// The home row's gap between tiles.
private val ColumnGap = 28.dp
// Room for the selected tile's name between the rows.
private val RowGap = 40.dp
private val NameGap = 9.dp

/**
 * Google TV style paging: the selected row settles on the second row's line, so the grid moves a
 * whole row at a time (scrolling just enough left the rows at odd heights, and they bobbed).
 * The first row stays at the top: the grid can't scroll above its start.
 */
@OptIn(ExperimentalFoundationApi::class)
private class GridKeyline(private val centerLine: Float) : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 600f)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset + size / 2 - centerLine
}
