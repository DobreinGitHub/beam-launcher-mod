package com.home.tiles

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Which keystone control is taking the arrow keys, if any (see [ArrowPad]). */
object KeystoneEdit {
    val active = mutableStateOf<String?>(null)
}

/**
 * Manual keystone: each corner moved with the arrows, the whole picture shifted, its size
 * shrunk in XGIMI's digital zoom steps; plus automatic keystone and a reset to no correction.
 */
@Composable
internal fun KeystonePage(onScreen: () -> Unit) {
    val context = LocalContext.current
    val cornersState = rememberFirmwareState { Keystone.corners() }
    var zoom by remember { mutableStateOf(Keystone.savedZoom(context)) }
    val realtime = rememberFirmwareState { Sensors.realtimeKeystone() }
    val bootKeystone = rememberFirmwareState { Sensors.bootKeystone() }
    DisposableEffect(Unit) { onDispose { KeystoneEdit.active.value = null } }
    val current = cornersState.value
    if (current == null) {
        T(if (cornersState.loaded) "Трапеция недоступна" else "Загрузка…", 14.sp, color = PanelDim)
        return
    }
    // The corners on screen are the ones last asked for; the write goes to a background queue
    // (keeping the newest), so a held arrow key neither lags nor computes from stale corners.
    // A move that would cross or collapse the picture is ignored, like the firmware would refuse it.
    fun apply(values: List<Int>) {
        val clamped = Keystone.clamp(values)
        if (!Keystone.isValid(clamped)) return
        cornersState.change(clamped) { Keystone.setCorners(clamped) }
    }
    ListRow("Настроить на экране", onScreen)
    if (bootKeystone.value != null || realtime.value == true) {
        Section("Автокоррекция")
        bootKeystone.value?.let { on ->
            Toggle("Коррекция при включении", on, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                bootKeystone.change(!on) { Sensors.setBootKeystone(!on) }
            }
        }
        if (realtime.value == true) {
            Toggle("Коррекция при сдвиге", true, Modifier.fillMaxWidth()) {
                realtime.change(false) { Sensors.setRealtimeKeystone(false) }
            }
            T("Выключите, иначе сдвиг проектора собьёт ручную настройку", 14.sp, color = PanelDim)
        }
    }
    Section("Углы · OK, затем стрелки")
    listOf("↖  Левый верхний", "↗  Правый верхний", "↙  Левый нижний", "↘  Правый нижний").forEachIndexed { i, label ->
        ArrowPad("corner$i", label, Modifier.fillMaxWidth().padding(bottom = 8.dp)) { dx, dy ->
            val values = (cornersState.value ?: current).toMutableList()
            values[i * 2] += dx
            values[i * 2 + 1] += dy
            apply(values)
        }
    }
    Section("Размер и положение")
    Selector("Размер", "${Keystone.sizePercent(zoom)}%", Modifier.fillMaxWidth().padding(bottom = 8.dp)) { step ->
        // Right makes it bigger (fewer shrink steps).
        val next = (zoom - step).coerceIn(0, Keystone.MAX_ZOOM)
        if (next != zoom) {
            val previous = zoom
            zoom = next
            Keystone.saveZoom(context, next)
            PanelIo.submit("zoom") {
                if (Keystone.setZoom(next)) {
                    cornersState.refresh() // the size moves the corners
                } else {
                    // Refused: back to what the projector really has.
                    zoom = previous
                    Keystone.saveZoom(context, previous)
                }
            }
        }
    }
    ArrowPad("shift", "✥  Сдвиг картинки", Modifier.fillMaxWidth()) { dx, dy ->
        val moved = (cornersState.value ?: current).mapIndexed { i, v -> v + if (i % 2 == 0) dx else dy }
        val inside = moved.chunked(2).all { (x, y) -> x in 0 until Keystone.WIDTH && y in 0 until Keystone.HEIGHT }
        if (inside) apply(moved)
    }
    T("Сдвиг работает, когда картинка уменьшена", 14.sp, color = PanelDim)
    Section("Сброс")
    ListRow("Автотрапеция") {
        // Xgimi.autoKeystone resets the size and the saved note of it.
        zoom = 0
        PanelIo.submit("auto-keystone") { Xgimi.autoKeystone(context) }
    }
    ListRow("Без коррекции") {
        PanelIo.submit("zoom") { Keystone.setZoom(0) }
        Keystone.saveZoom(context, 0)
        zoom = 0
        apply(listOf(0, 0, Keystone.WIDTH - 1, 0, 0, Keystone.HEIGHT - 1, Keystone.WIDTH - 1, Keystone.HEIGHT - 1))
    }
}

/**
 * A row that, after OK, takes the four arrows (a held key moves faster) until OK or Back.
 * [id] ties it to [KeystoneEdit] so only one is active and the panel can end it on Back.
 */
@Composable
private fun ArrowPad(id: String, label: String, modifier: Modifier, onMove: (dx: Int, dy: Int) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val active = KeystoneEdit.active.value == id
    Row(
        modifier
            .height(56.dp)
            .background(
                when {
                    active -> Accent
                    focused -> FocusBg
                    else -> CardBg
                },
                RoundedCornerShape(16.dp),
            )
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (!active || native.keyCode !in ARROWS) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    val step = if (native.repeatCount > 0) 12 else 4
                    when (native.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> onMove(-step, 0)
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> onMove(step, 0)
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> onMove(0, -step)
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> onMove(0, step)
                    }
                }
                true
            }
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused && active) KeystoneEdit.active.value = null
            }
            .panelControl({ }, { KeystoneEdit.active.value = if (active) null else id })
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (active) OnText else if (focused) FocusText else PanelText
        T(label, 16.sp, Modifier.weight(1f), color = color)
        T(if (active) "стрелки · OK" else "OK", 14.sp, color = if (active || focused) color else PanelDim)
    }
}

private val ARROWS = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN,
)
