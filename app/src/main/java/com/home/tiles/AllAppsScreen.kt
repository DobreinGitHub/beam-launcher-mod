package com.home.tiles

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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity

private val TileWidth = 200.dp
private val GridPad = 12.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AllAppsScreen(
    repo: AppRepository,
    apps: List<AppEntry>,
    onBack: () -> Unit,
    onOptions: (AppEntry) -> Unit,
) {
    val context = LocalContext.current
    val sorted = remember(apps) { apps.sortedBy { it.label.lowercase() } }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { first.requestFocus() }
    }

    Column(Modifier.fillMaxSize().padding(start = 64.dp, end = 64.dp, top = 36.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T(tr(R.string.all_apps), 36.sp, weight = FontWeight.Light)
            Spacer(Modifier.width(20.dp))
            T("${apps.size}", 24.sp, color = Colors.TextDim)
            Spacer(Modifier.weight(1f))
            Clock(30.sp)
        }
        Spacer(Modifier.height(18.dp))
        // The tiles' lights, behind the whole grid like on the home row.
        val glows = rememberTileGlows()
        val density = LocalDensity.current
        val keyline = remember(density) { GridKeyline(with(density) { (GridTop + RowPitch + tileHeight(TileWidth) / 2).toPx() }) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides keyline) {
        LazyVerticalGrid(
            state = gridState,
            modifier = Modifier.drawBehind {
                val w = TileWidth.toPx()
                val h = tileHeight(TileWidth).toPx()
                for (info in gridState.layoutInfo.visibleItemsInfo) {
                    val glow = glows[info.key] ?: continue
                    // Item offsets count from the content's start: add the paddings back.
                    val center = Offset(
                        info.offset.x + GridPad.toPx() + info.size.width / 2f,
                        info.offset.y - gridState.layoutInfo.viewportStartOffset + h / 2f,
                    )
                    drawFocusGlow(glow.color.value, glow.level.value, center, w * FOCUS_SCALE / 2f, h * FOCUS_SCALE / 2f, FocusGlowSpread.toPx())
                }
            },
            columns = GridCells.Adaptive(TileWidth),
            contentPadding = PaddingValues(GridPad, GridTop, GridPad, 40.dp),
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
                        width = TileWidth,
                        highlighted = focused,
                        dimmed = entry.hidden,
                        modifier = Modifier
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedKey = entry.pkg },
                        onClick = { context.launchApp(entry) },
                        onLongClick = { onOptions(entry) },
                    ) { AppTileArt(repo, entry) }
                    // The name only under the selected tile, like Google TV, in the gap to the next
                    // row: offset, so it takes no room and the rows stay put.
                    if (focused) {
                        T(
                            entry.label,
                            17.sp,
                            Modifier.fillMaxWidth().offset(y = tileHeight(TileWidth) + NameGap),
                            align = TextAlign.Center,
                        )
                    }
                }
            }
        }
        }
    }
}

private val GridTop = 14.dp
private val ColumnGap = 30.dp
// Room for the selected tile's name between the rows.
private val RowGap = 40.dp
private val NameGap = 9.dp
// From one row's top to the next: every row is the same height (the name takes no room).
private val RowPitch = tileHeight(TileWidth) + RowGap

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
