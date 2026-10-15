package com.home.tiles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.SignalWifiStatusbarConnectedNoInternet4
import androidx.compose.material.icons.rounded.SignalWifi0Bar
import androidx.compose.material.icons.rounded.NetworkWifi1Bar
import androidx.compose.material.icons.rounded.NetworkWifi2Bar
import androidx.compose.material.icons.rounded.NetworkWifi3Bar
import androidx.compose.material.icons.rounded.SignalWifi4Bar
import androidx.compose.material.icons.rounded.SignalWifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tile corners scale with the tile's height, so big and small tiles look equally rounded. */
private const val TILE_CORNER = 0.1f

private fun tileCorner(size: Dp) = size * TILE_CORNER

/** Top-right status cluster (clock, network, battery) metrics, kept identical across its items. */
val StatusTextSize = 22.sp
val StatusIconSize = 24.dp
val StatusGap = 16.dp

@Composable
fun T(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Colors.Text,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = 1,
    /** Digits all as wide (the clock, percentages), so the text beside them doesn't shift as they change. */
    tabular: Boolean = false,
) = BasicText(
    text = text,
    modifier = modifier,
    style = TextStyle(color = color, fontSize = size, fontWeight = weight, textAlign = align, fontFeatureSettings = if (tabular) "tnum" else null),
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
)

/**
 * Google TV style focus light: a soft, wide oval of [color] around an element of half-size
 * [halfW] x [halfH] at [center], fading gently to nothing [spread] beyond its edge. A radial
 * gradient, not a blur (blur needs Android 12; the projector has 11). Rows draw it behind all
 * their items, so the neighbours stay on top of the light.
 */
fun DrawScope.drawFocusGlow(color: Color, strength: Float, center: Offset, halfW: Float, halfH: Float, spread: Float) {
    if (strength <= 0f) return
    val ry = halfH + spread
    val rx = halfW + spread
    // The light's shape is a small picture made once, stretched into the oval and coloured as it
    // is drawn (like AmbientWash): a gradient worked out per pixel every frame, with new shader
    // objects each time, was the largest part of the home row's GPU time.
    drawImage(
        glowMask(halfH / ry),
        dstOffset = IntOffset((center.x - rx).roundToInt(), (center.y - ry).roundToInt()),
        dstSize = IntSize((rx * 2).roundToInt(), (ry * 2).roundToInt()),
        alpha = strength,
        colorFilter = glowFilter(color),
        filterQuality = FilterQuality.Low,
    )
}

/** The glow's alpha shape, by where the element's edge falls in it (tiles and cards differ). */
private val glowMasks = HashMap<Int, ImageBitmap>()

private fun glowMask(edge: Float): ImageBitmap {
    // In twentieths: a step finer than the eye can tell, and only a couple of shapes in use.
    val step = (edge * 20).roundToInt().coerceIn(1, 19)
    return glowMasks.getOrPut(step) {
        val e = step / 20f
        val side = GLOW_MASK_SIDE
        // White with the light's alpha, coloured by glowFilter as it is drawn (as AmbientWash's).
        val bitmap = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(
                side / 2f, side / 2f, side / 2f,
                // Alpha 0.30, 0.26, 0.10, 0.
                intArrayOf(0x4DFFFFFF, 0x42FFFFFF, 0x1AFFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, e, e + (1f - e) * 0.45f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        android.graphics.Canvas(bitmap).drawCircle(side / 2f, side / 2f, side / 2f, paint)
        bitmap.asImageBitmap()
    }
}

// Smooth enough stretched over a tile's light (a soft gradient, no edges to blur).
private const val GLOW_MASK_SIDE = 128

/** One tint filter per colour, not one per frame. */
private val glowFilters = HashMap<Color, ColorFilter>()

private fun glowFilter(color: Color): ColorFilter {
    if (glowFilters.size > 64) glowFilters.clear()
    return glowFilters.getOrPut(color) { ColorFilter.tint(color, BlendMode.SrcIn) }
}

/**
 * One tile's light, which its row draws behind all the tiles. It fades in and out with the
 * tile's growth, so moving the selection crossfades from one tile to the next, each in its own
 * colour (one light carried over would arrive in the previous tile's colour and blend: a flicker).
 */
class TileGlow(val level: State<Float>, val color: State<Color>, val target: State<Color>)

/** A row's tile lights by item key. Snapshot state, so the row redraws as tiles come and go. */
@Composable
fun rememberTileGlows(): MutableMap<Any, TileGlow> = remember { mutableStateMapOf() }

/** Puts this tile's light in [glows]: lit while [lit], in [color]. */
@Composable
fun RegisterTileGlow(glows: MutableMap<Any, TileGlow>, key: Any, lit: Boolean, color: Color) {
    // As long as the tile takes to grow (see Tile).
    val level = animateFloatAsState(if (lit) 1f else 0f, tween(150), label = "glow")
    val animated = animateColorAsState(color, tween(250), label = "glow")
    val target = rememberUpdatedState(color)
    DisposableEffect(glows, key) {
        glows[key] = TileGlow(level, animated, target)
        onDispose { glows.remove(key) }
    }
}


/**
 * OK click and OK long-press / Menu key for D-pad focus targets.
 * Handled by hand so a long press that opens a dialog never leaves a stuck press state.
 */
class DpadPress {
    var pressed = false
    var longFired = false
}

fun Modifier.dpadClick(
    press: DpadPress,
    onPress: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier = with(press) {
    onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        when (native.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.type == KeyEventType.KeyDown) {
                    if (native.repeatCount == 0) {
                        pressed = true
                        longFired = false
                        onPress(true)
                    } else if (!longFired && pressed) {
                        longFired = true
                        onPress(false)
                        onLongClick()
                    }
                } else if (event.type == KeyEventType.KeyUp) {
                    if (pressed && !longFired) onClick()
                    pressed = false
                    longFired = false
                    onPress(false)
                }
                true
            }
            // Not MOVE_HOME: the XGIMI gear key sends it and the system already opens its quick panel.
            AndroidKeyEvent.KEYCODE_MENU -> {
                if (event.type == KeyEventType.KeyDown && native.repeatCount == 0) onLongClick()
                true
            }
            else -> false
        }
    }
}

@Composable
fun rememberArt(repo: AppRepository, entry: AppEntry): State<TileArt?> =
    produceState(repo.cachedArt(entry), entry.pkg, entry.updated) { value = repo.loadArt(entry) }

/**
 * A placeholder while its content loads, so the launcher fills in calmly instead of popping:
 * a faint shape with a soft band of light sweeping across it, left to right. Drawn behind the
 * content; the sweep is read in the draw phase only.
 */
@Composable
fun Modifier.skeleton(shape: Shape): Modifier {
    val sweep = rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val base = Colors.TextDim
    return drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        drawOutline(outline, base.copy(alpha = 0.10f))
        // The band is as wide as the shape and travels from fully left of it to fully right.
        val band = size.width
        val x = -band + (size.width + band) * sweep.value
        drawOutline(
            outline,
            Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to base.copy(alpha = 0.16f),
                1f to Color.Transparent,
                startX = x,
                endX = x + band,
            ),
        )
    }
}

/** A tile-sized placeholder for the row before the apps are known. */
@Composable
fun SkeletonTile(width: Dp) {
    val height = tileHeight(width)
    Box(Modifier.size(width, height).skeleton(RoundedCornerShape(tileCorner(height))))
}

/** An app's tile picture, a placeholder until it has loaded (the letter only if it can't). */
@Composable
fun AppTileArt(repo: AppRepository, entry: AppEntry) {
    val cached = repo.cachedArt(entry)
    val loaded by produceState(cached to (cached != null), entry.pkg, entry.updated) {
        value = repo.loadArt(entry) to true
    }
    if (loaded.second) AppArt(loaded.first, entry.label) else Box(Modifier.fillMaxSize().skeleton(RectangleShape))
}

/** Tiles are 16:9, the shape of Android TV app banners, so a banner fills its tile exactly. */
fun tileHeight(width: Dp) = width * 9f / 16f

/** The widest a home-row tile gets; tile pictures are made for it (see AppRepository). */
const val MAX_TILE_WIDTH_DP = 280

/** How much a selected tile or card grows, and a tile being moved; the gaps leave it room. */
const val FOCUS_SCALE = 1.08f
const val FOCUS_SCALE_LIFTED = 1.12f

/** How far the light around a selected tile or card reaches past its edge. */
val FocusGlowSpread = 44.dp

private val FocusBorderWidth = 3.dp

/**
 * Google TV's selection outline: a line along the rounded edge of a tile or card, drawn over its
 * picture, fading in and out with the tile's growth ([alpha] is read at draw time). White in the
 * dark theme, XGIMI's selection blue in the light one (as everything selected there).
 */
fun Modifier.focusBorder(corner: Dp, alpha: () -> Float): Modifier = drawWithContent {
    drawContent()
    val a = alpha()
    if (a <= 0f) return@drawWithContent
    val w = FocusBorderWidth.toPx()
    val r = (corner.toPx() - w / 2).coerceAtLeast(0f)
    drawRoundRect(
        (if (LauncherSettings.dark) Colors.Text else FocusBg).copy(alpha = a),
        topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(w),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tile(
    width: Dp,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    /** Installed this past week and not opened yet: «Новое» in the corner. */
    newMark: Boolean = false,
    /** Picked up to be moved: drawn larger still than a selected tile. */
    lifted: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val press = remember { DpadPress() }
    // Google TV style selection: the selected tile grows a little and gets an outline; the row
    // draws the light behind it (see drawFocusGlow), under the neighbours.
    val border by animateFloatAsState(if (highlighted || lifted) 1f else 0f, tween(150), label = "border")
    val scale by animateFloatAsState(
        when {
            lifted -> FOCUS_SCALE_LIFTED
            pressed -> 1.03f
            highlighted -> FOCUS_SCALE
            else -> 1f
        },
        tween(150),
        label = "focus",
    )
    val click = {
        Sounds.activate()
        onClick()
    }
    val height = tileHeight(width)
    val corner = tileCorner(height)
    val screenShown = LocalScreenShown.current
    Box(
        modifier
            .size(width, height)
            // Not on the screen kept ready behind the shown one.
            .focusProperties { canFocus = screenShown }
            .onFocusChanged { if (it.isFocused) Sounds.navigate() else pressed = false }
            .dpadClick(press, onPress = { pressed = it }, onClick = click, onLongClick = onLongClick)
            .combinedClickable(remember { MutableInteractionSource() }, null, onLongClick = onLongClick, onClick = click)
            // Grown inside the focus target, not around it: scrolling to the selected tile then
            // sees its real place. Grown, its edges moved as it animated, and the grid scrolled
            // a few pixels on every move sideways.
            // No shadows: under every tile they cost the projector's GPU more than the frame budget.
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().focusBorder(corner) { border }.clip(RoundedCornerShape(corner))) { content() }
        if (newMark) {
            T(
                tr(R.string.app_new),
                12.sp,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(MarkOrange, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                color = Color.White,
                weight = FontWeight.Medium,
            )
        }
    }
}

/**
 * An app's tile picture. A banner fills the tile. An app without one shows its icon at the left
 * and its name beside it, as Android TV does, so it can be told apart without selecting it; on a
 * tile too small for words (a menu's picture) the icon alone.
 */
@Composable
fun AppArt(art: TileArt?, label: String) {
    val brush = when {
        art == null -> Brush.verticalGradient(listOf(Color(0xFF6A6A6A), Color(0xFF4A4A4A)))
        else -> Brush.verticalGradient(listOf(art.top, art.bottom))
    }
    if (art != null && art.isBanner) {
        // Banners are 16:9 like the tile; Crop only trims an off-ratio banner's edges.
        Box(Modifier.fillMaxSize().background(brush)) {
            Image(art.image, label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(brush), contentAlignment = Alignment.CenterStart) {
        val w = maxWidth
        val h = maxHeight
        val roomy = w >= 160.dp
        // An adaptive icon's picture is the whole tile, its icon already at the left (see AppRepository).
        if (art != null && art.fullBleed) Image(art.image, label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (!roomy) {
            if (art != null && !art.fullBleed) Image(art.image, label, Modifier.align(Alignment.Center).fillMaxHeight(ICON_HEIGHT).aspectRatio(1f))
            if (art == null) T(label.take(1).uppercase(), (h.value * 0.5f).sp, Modifier.align(Alignment.Center), color = Color.White, weight = FontWeight.Light)
            return@BoxWithConstraints
        }
        Row(Modifier.fillMaxSize().padding(start = w * ICON_START, end = w * 0.06f), verticalAlignment = Alignment.CenterVertically) {
            val icon = h * ICON_HEIGHT
            when {
                art == null -> Box(Modifier.size(icon), contentAlignment = Alignment.Center) {
                    T(label.take(1).uppercase(), (h.value * 0.4f).sp, color = Color.White, weight = FontWeight.Light)
                }
                art.fullBleed -> Spacer(Modifier.width(icon))
                else -> Image(art.image, null, Modifier.size(icon))
            }
            Spacer(Modifier.width(w * 0.05f))
            T(label, (h.value * 0.13f).sp, color = art?.label ?: Color.White, weight = FontWeight.Medium, maxLines = 2)
        }
    }
}

/**
 * The tile that closes the home row and opens All apps: a quiet card with the apps icon in a
 * disc, the name and how many apps there are, so it reads as a door to the full list rather than
 * one more app. Too small for words (as in a menu): the disc alone.
 */
@Composable
fun AllAppsArt(title: String, subtitle: String) {
    val dark = LauncherSettings.dark
    BoxWithConstraints(
        Modifier.fillMaxSize().background(if (dark) Color(0xFF383838) else Color(0xFFE6E6E6)),
        contentAlignment = Alignment.Center,
    ) {
        val w = maxWidth
        val h = maxHeight
        val roomy = w >= 160.dp
        // Sized from the tile, so it reads the same at any size; alone it fills more of it.
        val disc = h * if (roomy) 0.36f else 0.62f
        val icon = @Composable {
            Box(Modifier.size(disc).background(Colors.Button, CircleShape), contentAlignment = Alignment.Center) {
                Image(Icons.Rounded.Apps, null, Modifier.size(disc * 0.5f), colorFilter = ColorFilter.tint(Colors.Text))
            }
        }
        if (!roomy) {
            icon()
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = w * 0.08f), verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(w * 0.06f))
                Column(Modifier.weight(1f)) {
                    T(title, (h.value * 0.12f).sp, color = Colors.Text, weight = FontWeight.Medium, maxLines = 2)
                    Spacer(Modifier.height(h * 0.02f))
                    T(subtitle, (h.value * 0.09f).sp, color = Colors.TextDim)
                }
            }
        }
    }
}

@Composable
fun Clock(size: TextUnit = 34.sp, color: Color = Colors.Text, weight: FontWeight = FontWeight.Light) {
    val format = remember(AppLanguage.locale) { SimpleDateFormat("HH:mm", AppLanguage.locale) }
    val time by produceState(format.format(Date())) {
        while (true) {
            value = format.format(Date())
            delay(1000 - System.currentTimeMillis() % 1000)
        }
    }
    T(time, size, color = color, weight = weight, tabular = true)
}

/**
 * A key in the status cluster while a VPN is up (Happ, Amnezia...), with the gap after it;
 * nothing otherwise. Only VPNs that carry Beam's own traffic are visible to it, i.e. the usual
 * whole-device ones.
 */
@Composable
fun VpnIcon(color: Color = Colors.Text, size: Dp = StatusIconSize, gap: Dp = StatusGap) {
    val context = LocalContext.current
    val active by produceState(initialValue = false) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val up = java.util.Collections.synchronizedSet(mutableSetOf<Network>())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                up += network
                value = true
            }

            override fun onLost(network: Network) {
                up -= network
                value = up.isNotEmpty()
            }
        }
        cm.registerNetworkCallback(request, callback)
        awaitDispose { cm.unregisterNetworkCallback(callback) }
    }
    if (active) {
        Image(Icons.Rounded.VpnKey, null, Modifier.size(size), colorFilter = ColorFilter.tint(color))
        Spacer(Modifier.width(gap))
    }
}

/** What the network icon shows: the connection, whether it reaches the internet, Wi-Fi's bars. */
private data class NetState(val connected: Boolean, val wifi: Boolean = true, val internet: Boolean = true, val bars: Int = 4)

/**
 * The connection as XGIMI's status bar shows it: Wi-Fi with its signal in bars (0-4), Wi-Fi with
 * an exclamation mark when it's up but the internet isn't (Android's own check failed), Ethernet,
 * or crossed out with nothing. Physical networks only: a VPN on top doesn't change it (it has its
 * own key, see VpnIcon). Connection changes come from the system as they happen; the signal is
 * read every few seconds while Wi-Fi is up (the system only reports it on large changes).
 */
@Composable
fun NetworkIcon(color: Color = Colors.Text, size: Dp = StatusIconSize) {
    val context = LocalContext.current
    val state by produceState(NetState(connected = true)) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wm = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        val nets = java.util.concurrent.ConcurrentHashMap<Network, NetworkCapabilities>()
        fun bars(): Int {
            @Suppress("DEPRECATION")
            val rssi = runCatching { wm?.connectionInfo?.rssi }.getOrNull() ?: return 4
            return when {
                rssi >= -55 -> 4
                rssi >= -66 -> 3
                rssi >= -77 -> 2
                rssi >= -88 -> 1
                else -> 0
            }
        }
        fun update() {
            val caps = nets.values
            // Ethernet over Wi-Fi when both are up, as the system routes.
            val best = caps.firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
                ?: caps.firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
                ?: caps.firstOrNull()
            value = if (best == null) NetState(connected = false) else {
                val wifi = best.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                NetState(
                    connected = true,
                    wifi = wifi,
                    internet = best.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    bars = if (wifi) bars() else 4,
                )
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                nets[network] = caps
                update()
            }
            override fun onLost(network: Network) {
                nets.remove(network)
                update()
            }
        }
        // Not VPNs (a request excludes them unless asked), only networks that can carry the internet.
        val request = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        update()
        try {
            while (true) {
                delay(SIGNAL_POLL_MS)
                if (value.connected && value.wifi) {
                    val b = bars()
                    if (b != value.bars) value = value.copy(bars = b)
                }
            }
        } finally {
            cm.unregisterNetworkCallback(callback)
        }
    }
    val icon = when {
        // The same filled family as the bars, with a slash (the outline WifiOff didn't match them).
        !state.connected -> Icons.Rounded.SignalWifiOff
        !state.wifi -> Icons.Rounded.SettingsEthernet
        !state.internet -> Icons.Rounded.SignalWifiStatusbarConnectedNoInternet4
        else -> when (state.bars) {
            0 -> Icons.Rounded.SignalWifi0Bar
            1 -> Icons.Rounded.NetworkWifi1Bar
            2 -> Icons.Rounded.NetworkWifi2Bar
            3 -> Icons.Rounded.NetworkWifi3Bar
            else -> Icons.Rounded.SignalWifi4Bar
        }
    }
    Image(icon, null, Modifier.size(size), colorFilter = ColorFilter.tint(color))
}

private const val SIGNAL_POLL_MS = 5_000L

/** What the OK-hold menu shows: a tile's name, its picture and the actions. */
class MenuRequest(
    val title: String,
    val art: @Composable () -> Unit,
    val options: List<Pair<String, () -> Unit>>,
)

/** The OK-hold menu of an app. [onMove] is null where the order can't change (All apps). */
fun appMenu(
    context: android.content.Context,
    repo: AppRepository,
    entry: AppEntry,
    onChanged: () -> Unit,
    onMove: (() -> Unit)?,
) = MenuRequest(
    title = entry.label,
    art = {
        val art by rememberArt(repo, entry)
        AppArt(art, entry.label)
    },
    options = buildList<Pair<String, () -> Unit>> {
        add(tr(R.string.menu_open) to { context.launchApp(entry) })
        if (onMove != null && !entry.hidden) add(tr(R.string.menu_move) to onMove)
        add((if (entry.hidden) tr(R.string.menu_show_home) else tr(R.string.menu_hide_home)) to { repo.toggleHidden(entry.pkg); onChanged() })
        if (!entry.isSystem) add(tr(R.string.menu_uninstall) to { context.uninstall(entry.pkg) })
    },
)

private fun isOkKey(code: Int) =
    code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER || code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

@Composable
fun OptionsDialog(request: MenuRequest, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    // The menu opens while OK is still held: its key repeats and the release belong to the long
    // press, and would otherwise click the first item. OK counts only after a fresh press.
    var okArmed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(440.dp)
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (!isOkKey(native.keyCode)) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown && native.repeatCount == 0) okArmed = true
                    !okArmed
                }
                .arrowSoundTracker()
                .shadow(24.dp, RoundedCornerShape(18.dp))
                .background(Colors.Surface, RoundedCornerShape(18.dp))
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(96.dp, tileHeight(96.dp)).clip(RoundedCornerShape(tileCorner(tileHeight(96.dp))))) { request.art() }
                Spacer(Modifier.width(18.dp))
                T(request.title, 22.sp)
            }
            Spacer(Modifier.height(18.dp))
            request.options.forEachIndexed { i, (text, action) ->
                var focused by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        // Google TV style: the selected item is a light bar with dark text.
                        .background(if (focused) FocusBg else Color.Transparent, RoundedCornerShape(10.dp))
                        .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                        .onFocusChanged {
                            focused = it.isFocused
                            if (it.isFocused) Sounds.navigate()
                        }
                        .clickable(remember { MutableInteractionSource() }, null) {
                            Sounds.activate()
                            onDismiss()
                            action()
                        }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    T(text, 20.sp, color = if (focused) FocusText else Colors.Text)
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
