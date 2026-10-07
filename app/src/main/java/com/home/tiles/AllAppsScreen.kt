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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset

private val TileWidth = 200.dp
private val GridPad = 12.dp

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
        // The selected tile's light, behind the whole grid like on the home row.
        val focusedEntry = sorted.firstOrNull { it.pkg == focusedKey }
        val glowTarget by produceState(focusedEntry?.let { repo.cachedArt(it)?.glow } ?: Colors.Text, focusedKey) {
            value = focusedEntry?.let { repo.loadArt(it)?.glow } ?: Colors.Text
        }
        val glowColor by animateColorAsState(glowTarget, tween(250), label = "glow")
        val glowStrength by animateFloatAsState(if (focusedEntry != null) 1f else 0f, tween(200), label = "glow")
        LazyVerticalGrid(
            state = gridState,
            modifier = Modifier.drawBehind {
                val key = focusedKey ?: return@drawBehind
                val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return@drawBehind
                val w = TileWidth.toPx()
                val h = tileHeight(TileWidth).toPx()
                // Item offsets count from the content's start: add the paddings back.
                val center = Offset(
                    info.offset.x + GridPad.toPx() + info.size.width / 2f,
                    info.offset.y - gridState.layoutInfo.viewportStartOffset + h / 2f,
                )
                drawFocusGlow(glowColor, glowStrength, center, w * FOCUS_SCALE / 2f, h * FOCUS_SCALE / 2f, FocusGlowSpread.toPx())
            },
            columns = GridCells.Adaptive(TileWidth),
            contentPadding = PaddingValues(GridPad, 14.dp, GridPad, 40.dp),
            horizontalArrangement = Arrangement.spacedBy(30.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            itemsIndexed(sorted, key = { _, e -> e.pkg }) { i, entry ->
                val focused = focusedKey == entry.pkg
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                    Spacer(Modifier.height(12.dp))
                    T(
                        entry.label,
                        17.sp,
                        Modifier.fillMaxWidth(),
                        color = if (focused) Colors.Text else Colors.TextDim,
                        align = TextAlign.Center,
                    )
                }
            }
        }
    }
}
