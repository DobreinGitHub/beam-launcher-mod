@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.home.tiles

import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.ExperimentalComposeUiApi
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.composed
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
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ClassicPad = 108.dp
// Wide enough that a selected tile, grown by FOCUS_SCALE, keeps some room from its neighbours.
private val ClassicGap = 28.dp
private val RowTop = 8.dp
private val NameHeight = 40.dp
private const val MoveSlideMs = 160
// Rows are taller than tiles: a little longer, so the slide reads the same.
private const val RowSlideMs = 220

/**
 * The Switch home screen: equal-size tiles in a normally scrolling row (tiles to the left stay in
 * view), the selected one framed with its name above; the chosen channel rows underneath.
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
    // Each tile's light, in its app's colour (see TileGlow).
    val glows = rememberTileGlows()
    // The background leans towards the selected tile's colour; it fades back when the selection
    // leaves the row or the home screen closes.
    val tint = AmbientTint.of(focusedKey?.let { glows[it] }?.target?.value)
    SideEffect { AmbientTint.target.value = tint }
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
            LazyRow(
                state = listState,
                // The tiles' lights, behind the whole row so the neighbours stay on top of them;
                // they follow the tiles as the row scrolls (read at draw time).
                // Coming back to the row (down from the top bar, up from a channel row) returns to
                // the tile last selected in it, as on Google TV, not to whichever is nearest: that
                // was often the tile half out of view at the edge, one before.
                modifier = Modifier.fadeAtTop(pageScroll).focusRestorer().drawBehind {
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
                    val focused = focusedKey == item.key
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
            channelRows.forEachIndexed { i, channel ->
                // A clear gap between the apps and the first channel, a smaller one between channels.
                Spacer(Modifier.height(if (i == 0) 32.dp else 18.dp))
                // Keyed, so a moved row takes its state (and the focused card) along; it slides to
                // its new place like a moved tile, above the row it passes.
                key(channel.key) {
                    ChannelRow(
                        channel, ClassicPad, i, rowMove,
                        modifier = Modifier
                            .fadeAtTop(pageScroll)
                            .zIndex(if (rowMove.key == channel.key) 1f else 0f)
                            .slideToPlace(enabled = rowMove.key != null),
                        onMenu = { rowMenu(channel, it) },
                    )
                }
            }
            repeat(placeholderRows) { i ->
                Spacer(Modifier.height(if (i == 0 && channelRows.isEmpty()) 32.dp else 18.dp))
                ChannelRowSkeleton(ClassicPad)
            }
            if (scrolls) Spacer(Modifier.height(40.dp))
        }
        }
        }
    }
}

// Where a focused channel card settles, from the top of the page (just under the top bar): its
// row's name (about 46dp above the card) is in full view, and the row above is just out of it.
private val Keyline = 88.dp
// A row fades out over this much of its way past the page's top (see fadeAtTop).
private val TopFade = 48.dp

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
 * every frame, and the projector's GPU missed 60% of frames on the channel rows with it.
 */
private fun Modifier.fadeAtTop(scroll: ScrollState): Modifier = composed {
    // The row's top in the page's content: the scroll is subtracted at draw time.
    var top by remember { mutableFloatStateOf(0f) }
    this
        .onPlaced { top = it.positionInParent().y }
        .graphicsLayer {
            val y = top - scroll.value
            alpha = if (y >= 0f) 1f else (1f + y / TopFade.toPx()).coerceIn(0f, 1f)
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
}

/**
 * Scrolls the row along with a moving tile. Each step aims at where the row must be for the
 * tile's new place, counting from where an unfinished scroll was heading, so quick steps add up
 * instead of waiting for each other. Tiles are equal, so a place is index Ã— (tile + gap).
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
 * The keys of a tile being moved: left/right move it, OK puts it down, Back puts it back where it
 * was. Up/down are swallowed so the focus can't leave the tile mid-move.
 */
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

/** Keys while moving [key]: ◀ ▶ step it (▲ ▼ for a [vertical] list, like the channel rows). */
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
