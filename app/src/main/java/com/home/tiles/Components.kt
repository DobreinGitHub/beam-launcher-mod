package com.home.tiles

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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

/** Tile corners scale with the tile, so big and small tiles look equally rounded. */
private const val TILE_CORNER = 0.08f

private fun tileCorner(size: Dp) = size * TILE_CORNER

/** Top-right status cluster (clock, network, battery) metrics, kept identical across its items. */
val StatusTextSize = 30.sp
val StatusIconSize = 28.dp
val StatusGap = 22.dp

@Composable
fun T(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Colors.Text,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Start,
) = BasicText(
    text = text,
    modifier = modifier,
    style = TextStyle(color = color, fontSize = size, fontWeight = weight, textAlign = align),
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)

/**
 * Cyan outline that breathes like the Switch selection frame: a few pulses after the selection
 * moves, then steady. An endless pulse would keep the whole screen redrawing at the display rate.
 */
@Composable
private fun rememberPulse(): State<Color> {
    val glow = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        repeat(3) {
            glow.animateTo(1f, tween(750))
            glow.animateTo(0f, tween(750))
        }
    }
    return remember { derivedStateOf { lerp(Colors.Accent, Colors.AccentGlow, glow.value) } }
}

/**
 * The breathing selection frame. Call it only on the selected/focused element: the colour is read
 * in the draw phase, so the animation redraws just this outline instead of recomposing the screen.
 */
@Composable
fun Modifier.pulseBorder(width: Dp, shape: Shape): Modifier {
    val color = rememberPulse()
    return drawWithContent {
        drawContent()
        val w = width.toPx()
        inset(w / 2) { drawOutline(shape.createOutline(size, layoutDirection, this), color.value, style = Stroke(w)) }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tile(
    size: Dp,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val press = remember { DpadPress() }
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, tween(90), label = "press")
    val click = {
        Sounds.activate()
        onClick()
    }
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (dimmed) 0.45f else 1f
            }
            .onFocusChanged { if (it.isFocused) Sounds.navigate() else pressed = false }
            .dpadClick(press, onPress = { pressed = it }, onClick = click, onLongClick = onLongClick)
            .combinedClickable(remember { MutableInteractionSource() }, null, onLongClick = onLongClick, onClick = click),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .shadow(if (highlighted) 14.dp else 3.dp, RoundedCornerShape(tileCorner(size)))
                .clip(RoundedCornerShape(tileCorner(size))),
        ) { content() }
        if (highlighted) {
            // Frame sits outside the tile with a small gap, like the Switch selection.
            Box(
                Modifier
                    .requiredSize(size + 16.dp)
                    // The frame sits 8dp outside the tile, so its radius grows by the same amount.
                    .pulseBorder(5.dp, RoundedCornerShape(tileCorner(size) + 8.dp)),
            )
        }
    }
}

@Composable
fun AppArt(art: TileArt?, label: String) {
    val brush = when {
        art == null -> Brush.verticalGradient(listOf(Color(0xFF6A6A6A), Color(0xFF4A4A4A)))
        else -> Brush.verticalGradient(listOf(art.top, art.bottom))
    }
    Box(Modifier.fillMaxSize().background(brush), contentAlignment = Alignment.Center) {
        when {
            art == null -> T(label.take(1).uppercase(), 64.sp, color = Color.White, weight = FontWeight.Light)
            art.fullBleed -> Image(art.image, label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            art.isBanner -> Image(art.image, label, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            else -> Image(art.image, label, Modifier.fillMaxSize(0.58f))
        }
    }
}

@Composable
fun AllAppsArt() {
    Column(
        Modifier.fillMaxSize().background(Colors.AllTile),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            Icons.Rounded.Apps,
            null,
            Modifier.fillMaxSize(0.4f),
            colorFilter = ColorFilter.tint(Colors.TextDim),
        )
    }
}

@Composable
fun RoundButton(icon: ImageVector, tint: Color, label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(120), label = "focus")
    Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(if (focused) 8.dp else 3.dp, CircleShape)
                .background(Colors.Button, CircleShape)
                .then(if (focused) Modifier.pulseBorder(3.dp, CircleShape) else Modifier)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) Sounds.navigate()
                }
                .clickable(remember { MutableInteractionSource() }, null) {
                    Sounds.activate()
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(icon, label, Modifier.size(34.dp), colorFilter = ColorFilter.tint(tint))
        }
        Spacer(Modifier.height(8.dp))
        T(
            if (focused) label else "",
            16.sp,
            Modifier.requiredWidth(200.dp),
            color = Colors.Accent,
            align = TextAlign.Center,
        )
    }
}

@Composable
fun Clock(size: TextUnit = 36.sp) {
    val format = remember(AppLanguage.locale) { SimpleDateFormat("HH:mm", AppLanguage.locale) }
    val time by produceState(format.format(Date())) {
        while (true) {
            value = format.format(Date())
            delay(1000 - System.currentTimeMillis() % 1000)
        }
    }
    T(time, size, weight = FontWeight.Light)
}

@Composable
fun NetworkIcon() {
    val context = LocalContext.current
    val online by produceState(initialValue = true) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun check() = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        value = check()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { value = true }
            override fun onLost(network: Network) { value = check() }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitDispose { cm.unregisterNetworkCallback(callback) }
    }
    Image(
        if (online) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
        null,
        Modifier.size(StatusIconSize),
        colorFilter = ColorFilter.tint(Colors.Text),
    )
}

@Composable
fun OptionsDialog(entry: AppEntry, repo: AppRepository, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val art by rememberArt(repo, entry)
    val first = remember { FocusRequester() }
    val options = buildList<Pair<String, () -> Unit>> {
        add(tr(R.string.menu_open) to { context.launchApp(entry) })
        add((if (entry.pinned) tr(R.string.menu_unpin) else tr(R.string.menu_pin_first)) to { repo.togglePinned(entry.pkg); onChanged() })
        add((if (entry.hidden) tr(R.string.menu_show_home) else tr(R.string.menu_hide_home)) to { repo.toggleHidden(entry.pkg); onChanged() })
        add(tr(R.string.menu_app_info) to { context.openAppInfo(entry.pkg) })
        if (!entry.isSystem) add(tr(R.string.menu_uninstall) to { context.uninstall(entry.pkg) })
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(440.dp)
                .arrowSoundTracker()
                .shadow(24.dp, RoundedCornerShape(18.dp))
                .background(Colors.Surface, RoundedCornerShape(18.dp))
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(tileCorner(64.dp)))) { AppArt(art, entry.label) }
                Spacer(Modifier.width(18.dp))
                T(entry.label, 26.sp)
            }
            Spacer(Modifier.height(18.dp))
            options.forEachIndexed { i, (text, action) ->
                var focused by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .background(if (focused) Colors.Accent else Color.Transparent, RoundedCornerShape(10.dp))
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
                    T(text, 21.sp, color = if (focused) Color.White else Colors.Text)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        Sounds.popup()
        runCatching { first.requestFocus() }
    }
}
