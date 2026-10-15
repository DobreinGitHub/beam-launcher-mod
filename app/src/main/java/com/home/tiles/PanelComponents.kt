package com.home.tiles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// XGIMI's own panel and settings (as the russifier leaves them): no sheet over the evenly dimmed
// picture, see-through grey tiles, white focus with near-black text (measured off its screen).
// Light theme: XGIMI's light skin (measured off its quick panel): near-white tiles with dark grey
// text, the selection in blue with white text. The darkening at the left stays dark in both.
private val dark get() = LauncherSettings.dark
internal val CardBg get() = if (dark) Color(0x38FFFFFF) else Color(0xE6FFFFFF)
internal val FocusBg get() = if (dark) Color.White else Color(0xFF497AF5)
internal val FocusText get() = if (dark) Color(0xFF1A1A1A) else Color.White
/** Filled part of a focused slider or switch, drawn on [FocusBg]. */
private val FocusFill get() = if (dark) Color(0xFF1A1A1A) else Color.White
internal val Accent = Color(0xFF8AB4F8)
/** Text on a tile that is switched on (light [Accent] fill). */
internal val OnText = Color(0xFF0B1D36)
/** Text and icons on tiles. */
internal val PanelText get() = if (dark) Color(0xFFE8EAED) else Color(0xFF3A3A3A)
internal val PanelDim get() = if (dark) Color(0xFFA8ACB3) else Color(0xFF7A7A7A)
/** Text on the darkening itself (headers, the clock, hints between tiles): light in both themes, as XGIMI's. */
internal val ScrimText = Color(0xFFE8EAED)
internal val ScrimDim = Color(0xFFA8ACB3)
/**
 * Projector: like XGIMI's panel, darkest along the panel's edge and fading across the picture
 * (theirs is on the left, ours on the right).
 */
/**
 * The darkening behind the panel, matched to XGIMI's own quick panel (measured off its screen and
 * mirrored, as Beam's panel is on the right): nearly black behind the panel for easy reading,
 * fading smoothly to nothing at the far edge.
 */
internal val TvScrim = Brush.horizontalGradient(
    0f to Color(0x00000000),
    0.125f to Color(0x24000000),
    0.25f to Color(0x47000000),
    0.375f to Color(0x70000000),
    0.5f to Color(0x99000000),
    0.75f to Color(0xCC000000),
    0.94f to Color(0xEB000000),
    1f to Color(0xF5000000),
)

/** [TvScrim] mirrored: darkest on the left, behind sheets placed where XGIMI puts its own. */
internal val TvScrimLeft = Brush.horizontalGradient(
    // As dark as under XGIMI's quick panel where Beam's panel sits (the left third), so the home
    // screen hardly shows through its tiles; clearing towards the right.
    0f to Color(0xF5000000),
    0.25f to Color(0xEB000000),
    0.36f to Color(0xD9000000),
    0.5f to Color(0xA6000000),
    0.625f to Color(0x70000000),
    0.75f to Color(0x47000000),
    0.875f to Color(0x24000000),
    1f to Color(0x00000000),
)

/** The dark cards of XGIMI's full settings (measured off its screen). */
internal val SheetBg = Color(0xFF292929)

internal val NoScrim =Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))

@Composable
internal fun Section(title: String) {
    Spacer(Modifier.height(18.dp))
    T(title, 14.sp, color = ScrimDim, weight = FontWeight.Medium)
    Spacer(Modifier.height(10.dp))
}

@Composable
internal fun PairRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** A full-width row that runs an action, with a chevron like Google TV's list entries; [subtitle] under it. */
@Composable
internal fun ListRow(text: String, subtitle: String? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fg = if (focused) FocusText else PanelText
    Row(
        Modifier
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(if (focused) FocusBg else CardBg, RoundedCornerShape(16.dp))
            .panelControl({ focused = it }, onClick)
            .padding(horizontal = 18.dp, vertical = if (subtitle != null) 12.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            T(text, 16.sp, color = fg)
            subtitle?.let { T(it, 14.sp, Modifier.padding(top = 2.dp), color = if (focused) FocusText.copy(alpha = 0.7f) else PanelDim, maxLines = 2) }
        }
        Image(Icons.Rounded.ChevronRight, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(fg))
    }
}

/** Focus handling shared by every panel control: nav/activate sounds. */
@Composable
internal fun Modifier.panelControl(onFocus: (Boolean) -> Unit, onClick: () -> Unit): Modifier =
    onFocusChanged {
        onFocus(it.isFocused)
        if (it.isFocused) Sounds.navigate()
    }.clickable(remember { MutableInteractionSource() }, null) {
        Sounds.activate()
        onClick()
    }

@Composable
internal fun Chip(
    text: String,
    selected: Boolean,
    modifier: Modifier,
    note: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val fg = if (focused) FocusText else if (selected) PanelText else PanelDim
    Row(
        modifier
            .height(52.dp)
            .background(if (focused) FocusBg else CardBg, RoundedCornerShape(16.dp))
            // Disabled chips stay focusable so the grid doesn't jump, but do nothing.
            .panelControl({ focused = it }) { if (enabled) onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            T(text, 16.sp, color = if (enabled) fg else fg.copy(alpha = 0.5f))
            note?.let { T(it, 12.sp, color = if (focused) FocusText else PanelDim) }
        }
        if (selected) {
            Image(Icons.Rounded.Check, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(if (focused) FocusText else Accent))
        }
    }
}

/** A switch with its name, and under it a dimmer line saying what it does ([subtitle]). */
@Composable
internal fun Toggle(text: String, checked: Boolean, modifier: Modifier, subtitle: String? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .heightIn(min = 56.dp)
            .background(if (focused) FocusBg else CardBg, RoundedCornerShape(16.dp))
            .panelControl({ focused = it }, onClick)
            .padding(horizontal = 18.dp, vertical = if (subtitle != null) 12.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            T(text, 16.sp, color = if (focused) FocusText else PanelText)
            subtitle?.let { T(it, 14.sp, Modifier.padding(top = 2.dp), color = if (focused) FocusText.copy(alpha = 0.7f) else PanelDim, maxLines = 2) }
        }
        Spacer(Modifier.width(12.dp))
        SwitchTrack(checked, focused)
    }
}

/** A Material 3 switch's look (filled track when on), on a control that is [focused] or not. */
@Composable
internal fun SwitchTrack(checked: Boolean, focused: Boolean) {
    val knob by animateFloatAsState(if (checked) 1f else 0f, tween(150), label = "knob")
    Box(
        Modifier
            .size(width = 50.dp, height = 30.dp)
            .background(
                when {
                    checked && focused -> FocusFill
                    checked -> Accent
                    else -> Color(0xFF5F6368)
                },
                CircleShape,
            )
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .offset(x = 20.dp * knob)
                .size(22.dp)
                .background(if (checked) (if (focused) FocusBg else OnText) else Color(0xFFC4C7C5), CircleShape),
        )
    }
}

/** A focusable bar: left/right step it, taps and drags set it directly. */
@Composable
internal fun LevelSlider(
    icon: ImageVector,
    value: Int,
    max: Int,
    modifier: Modifier,
    label: String? = null,
    onSet: (Int) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    fun set(target: Int) {
        val clamped = target.coerceIn(0, max)
        if (clamped != value) onSet(clamped)
    }
    Row(
        modifier
            .height(54.dp)
            .background(if (focused) FocusBg else CardBg, RoundedCornerShape(16.dp))
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                if (code != AndroidKeyEvent.KEYCODE_DPAD_LEFT && code != AndroidKeyEvent.KEYCODE_DPAD_RIGHT) {
                    return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) set(value + if (code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                true
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.navigate()
            }
            .focusable()
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(icon, null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(if (focused) FocusText else PanelText))
        Spacer(Modifier.width(14.dp))
        label?.let {
            T(it, 16.sp, Modifier.width(96.dp), color = if (focused) FocusText else PanelText)
        }
        fun setFraction(fraction: Float) = set((fraction.coerceIn(0f, 1f) * max).roundToInt())
        Box(
            Modifier
                .weight(1f)
                // Generous touch target around the thin bar.
                .height(40.dp)
                .pointerInput(max) { detectTapGestures { setFraction(it.x / size.width) } }
                .pointerInput(max) {
                    detectHorizontalDragGestures { change, _ -> setFraction(change.position.x / size.width) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(if (focused) FocusFill.copy(alpha = 0.3f) else Color(0xFF4A4E55), CircleShape),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(value / max.toFloat())
                        .height(8.dp)
                        .background(if (focused) FocusFill else Accent, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        T("$value", 16.sp, color = if (focused) FocusText else PanelText)
    }
}

@Composable
internal fun Selector(label: String, value: String, modifier: Modifier, onClick: (() -> Unit)? = null, onChange: (Int) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .height(54.dp)
            .background(if (focused) FocusBg else CardBg, RoundedCornerShape(16.dp))
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                // OK, where the selector has something to open (on release, as a click).
                if (onClick != null && (code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER)) {
                    if (event.type == KeyEventType.KeyUp) {
                        Sounds.activate()
                        onClick()
                    }
                    return@onPreviewKeyEvent true
                }
                if (code != AndroidKeyEvent.KEYCODE_DPAD_LEFT && code != AndroidKeyEvent.KEYCODE_DPAD_RIGHT) {
                    return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) {
                    Sounds.activate()
                    onChange(if (code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                }
                true
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.navigate()
            }
            .focusable()
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(label, 16.sp, color = if (focused) FocusText else PanelDim)
        Spacer(Modifier.weight(1f))
        T("‹  $value  ›", 16.sp, color = if (focused) FocusText else PanelText)
    }
}

@Composable
internal fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.15f else 1f, tween(120), label = "dot")
    Box(
        Modifier
            .size(46.dp)
            // As the swatch: grown inside the focus target.
            .panelControl({ focused = it }, onClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                when {
                    focused -> Modifier.border(3.dp, FocusBg, CircleShape)
                    selected -> Modifier.border(3.dp, Accent, CircleShape)
                    else -> Modifier
                },
            )
            .padding(5.dp)
            .background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Image(Icons.Rounded.Check, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(Color.White))
        }
    }
}
