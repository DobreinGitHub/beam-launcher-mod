@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.home.tiles

import androidx.compose.animation.core.snap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity

// The grid lines up with the home screen: the rows' left edge and their gap between tiles.
private val GridStart = 108.dp
private val GridEnd = 108.dp
private const val Columns = 5
private const val SYSTEM_KEY = "__system__"

/**
 * Every app by name in two parts under small headers, the apps on the home screen and the hidden
 * ones (the headers say what a crossed-out eye on each tile used to). Hidden system apps, which
 * are rarely opened from here, wait behind one tile that opens them out in its place.
 */
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
    val byName = remember(apps) { apps.sortedBy { it.label.lowercase() } }
    val onHome = byName.filter { !it.hidden }
    val hiddenOwn = byName.filter { it.hidden && !it.isSystem }
    val hiddenSystem = byName.filter { it.hidden && it.isSystem }
    // Opened out until the screen is left; folded again on the next visit.
    var systemOpen by remember { mutableStateOf(false) }
    val shown = LocalScreenShown.current
    LaunchedEffect(shown) { if (!shown) systemOpen = false }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val firstSystem = remember { FocusRequester() }
    val firstKey = (onHome.firstOrNull() ?: hiddenOwn.firstOrNull() ?: hiddenSystem.firstOrNull())?.pkg
    val gridState = rememberLazyGridState()

    // Shown only under the highlighted tab, the selection stays on the tab.
    LaunchedEffect(takeFocus) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos {}
        runCatching { first.requestFocus() }
        onFocusTaken()
    }
    // The system apps take the place of their tile, the selection on the first of them.
    LaunchedEffect(systemOpen) {
        if (!systemOpen) return@LaunchedEffect
        withFrameNanos {}
        runCatching { firstSystem.requestFocus() }
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
        val keyline = remember(density, tile) { GridKeyline(with(density) { (HeaderHeight + tileH + RowGap + tileH / 2).toPx() }) }
        fun LazyGridScope.header(key: String, icon: ImageVector, title: String, count: Int) =
            item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = "header") { GridHeader(icon, title, count) }
        fun LazyGridScope.tiles(list: List<AppEntry>, firstOf: FocusRequester? = null) =
            items(list, key = { it.pkg }, contentType = { "app" }) { entry ->
                val focused = focusedKey == entry.pkg
                val glowColor by produceState(repo.cachedArt(entry)?.glow ?: Colors.Text, entry.pkg) {
                    repo.loadArt(entry)?.glow?.let { value = it }
                }
                RegisterTileGlow(glows, entry.pkg, focused, glowColor)
                // An app without a banner has its name on the tile already.
                val named = repo.cachedArt(entry)?.isBanner == false
                AppCell(
                    tile, tileH, focused,
                    name = if (named) null else entry.label,
                    modifier = Modifier
                        .then(if (entry.pkg == firstKey) Modifier.focusRequester(first) else Modifier)
                        .then(if (firstOf != null && entry == list.first()) Modifier.focusRequester(firstOf) else Modifier),
                    onFocus = { on -> if (on) focusedKey = entry.pkg else if (focusedKey == entry.pkg) focusedKey = null },
                ) {
                    Tile(
                        width = tile,
                        highlighted = focused,
                        newMark = entry.isNew,
                        onClick = { context.launchApp(entry) },
                        onLongClick = { onOptions(entry) },
                    ) { AppTileArt(repo, entry) }
                }
            }
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
            contentPadding = PaddingValues(GridStart, 0.dp, GridEnd, 40.dp),
            horizontalArrangement = Arrangement.spacedBy(ColumnGap),
        ) {
            if (onHome.isNotEmpty()) {
                header("h:home", Icons.Rounded.Home, tr(R.string.apps_on_home), onHome.size)
                tiles(onHome)
            }
            if (hiddenOwn.isNotEmpty() || hiddenSystem.isNotEmpty()) {
                header("h:hidden", Icons.Rounded.VisibilityOff, tr(R.string.apps_hidden), hiddenOwn.size + hiddenSystem.size)
                tiles(hiddenOwn)
                when {
                    hiddenSystem.isEmpty() -> {}
                    systemOpen -> tiles(hiddenSystem, firstOf = firstSystem)
                    else -> item(key = SYSTEM_KEY, contentType = "app") {
                        val focused = focusedKey == SYSTEM_KEY
                        RegisterTileGlow(glows, SYSTEM_KEY, focused, Colors.Text)
                        AppCell(
                            tile, tileH, focused, name = null,
                            modifier = if (firstKey == null) Modifier.focusRequester(first) else Modifier,
                            onFocus = { on -> if (on) focusedKey = SYSTEM_KEY else if (focusedKey == SYSTEM_KEY) focusedKey = null },
                        ) {
                            Tile(width = tile, highlighted = focused, onClick = { systemOpen = true }) {
                                AllAppsArt(tr(R.string.apps_system), tr(R.string.apps_count, hiddenSystem.size))
                            }
                        }
                    }
                }
            }
        }
        }
        }
    }
}

/** A grid cell: the tile, and under the selected one its [name] (null: the tile shows it). */
@Composable
private fun AppCell(
    tile: Dp,
    tileH: Dp,
    focused: Boolean,
    name: String?,
    modifier: Modifier,
    onFocus: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    // The gap to the next row is part of the cell, so the headers between parts sit close above
    // their tiles, not a whole gap away.
    Box(Modifier.fillMaxWidth().height(tileH + RowGap), contentAlignment = Alignment.TopCenter) {
        // Cleared when the selection leaves for the top bar, so no tile stays lit.
        Box(modifier.onFocusChanged { onFocus(it.hasFocus) }) { content() }
        // The name only under the selected tile, like Google TV, in the gap to the next row.
        if (focused && name != null) {
            T(name, 16.sp, Modifier.fillMaxWidth().offset(y = tileH + NameGap), align = TextAlign.Center)
        }
    }
}

/**
 * A part's name and how many apps it has, over its first row, after a symbol in the same rounded
 * square as the app icons by the home screen's row names (a glyph on a plain fill, so it doesn't
 * read as an app).
 */
@Composable
private fun GridHeader(icon: ImageVector, title: String, count: Int) {
    Box(Modifier.fillMaxWidth().height(HeaderHeight), contentAlignment = Alignment.BottomStart) {
        Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).background(Colors.Button, RoundedCornerShape(26.dp * 0.22f)), contentAlignment = Alignment.Center) {
                Image(icon, null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(Colors.Text))
            }
            Spacer(Modifier.width(10.dp))
            T(title, 20.sp, color = Colors.Text, weight = FontWeight.Medium)
            T("  $count", 20.sp, color = Colors.TextDim)
        }
    }
}

private val HeaderHeight = 52.dp
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
    // Fast mode: straight there.
    override val scrollAnimationSpec: AnimationSpec<Float>
        get() = if (Effects.reduced) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 600f)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset + size / 2 - centerLine
}
