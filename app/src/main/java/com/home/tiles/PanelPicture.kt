package com.home.tiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Details
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picture modes with the current one ticked. The panel stays open so the change can be judged
 * against the picture behind it. Performance asks first, like XGIMI does (heat warning).
 */
@Composable
internal fun PicturePage(onXgimiPage: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(PictureMode.current()) }
    LaunchedEffect(Unit) { XgimiService.bind(context) }
    var confirmPerformance by remember { mutableStateOf(false) }
    fun apply(mode: Int) {
        Xgimi.setPictureMode(context, mode)
        current = mode
        // The firmware switches asynchronously; read back what it actually applied.
        scope.launch {
            delay(1500)
            PictureMode.current()?.let { current = it }
        }
    }
    Section("Режим изображения")
    Xgimi.pictureModes.forEach { (label, mode) ->
        Chip(label, current == mode, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            if (mode == Xgimi.PICTURE_PERFORMANCE && current != mode) confirmPerformance = true else apply(mode)
        }
        if (mode == Xgimi.PICTURE_PERFORMANCE && confirmPerformance) {
            PerformanceWarning(
                onConfirm = {
                    confirmPerformance = false
                    apply(mode)
                },
                onCancel = { confirmPerformance = false },
            )
        }
    }
    GameModeSection()
    Section("Пользовательский режим")
    if (current == CUSTOM_PICTURE) {
        CustomPictureControls()
    } else {
        // XGIMI keeps these values per mode and only saves them in the custom one.
        ListRow("Перейти в пользовательский режим") { apply(CUSTOM_PICTURE) }
    }
    Section("Ещё")
    ListRow("Настройки AI и режимов XGIMI", onXgimiPage)
}

private val GameModes = listOf(GameMode.AUTO to "Авто", GameMode.ON to "Вкл", GameMode.OFF to "Выкл")
private val GameLevels = listOf("Базовый", "Максимальный")

/** XGIMI's game mode: lower input lag for consoles; it only takes effect with an HDMI signal. */
@Composable
private fun GameModeSection() {
    val context = LocalContext.current
    var state by remember { mutableStateOf(GameMode.read()) }
    var level by remember { mutableStateOf(GameMode.level(context)) }
    val current = state ?: return
    Section("Игровой режим · для HDMI")
    val index = GameModes.indexOfFirst { it.first == current.mode }.coerceAtLeast(0)
    Selector("Режим", GameModes[index].second, Modifier.fillMaxWidth().padding(bottom = 8.dp)) { delta ->
        val mode = GameModes[(index + delta).mod(GameModes.size)].first
        GameMode.setMode(mode)
        state = GameMode.read() ?: GameMode.State(mode)
    }
    // The level (basic / top speed) applies when game mode is forced on.
    if (current.mode == GameMode.ON) {
        Selector("Уровень", GameLevels[level], Modifier.fillMaxWidth().padding(bottom = 8.dp)) { delta ->
            level = (level + delta).mod(GameLevels.size)
            GameMode.setLevel(context, level)
        }
    }
}

private const val CUSTOM_PICTURE = 3

/** Everything XGIMI's custom picture mode page offers, read once XGIMI's service is bound. */
private data class CustomPicture(
    val items: Map<Int, Int>,
    val colorTemp: Int,
    val noise: Int,
    val motion: Int,
    val gamma: Int,
    val dynamicContrast: Boolean,
    val localContrast: Int,
    val hdr: Boolean,
)

private fun readCustomPicture(): CustomPicture? {
    val items = listOf(PictureAdjust.BRIGHTNESS, PictureAdjust.CONTRAST, PictureAdjust.SATURATION, PictureAdjust.SHARPNESS)
        .associateWith { PictureAdjust.get(it) ?: return null }
    return CustomPicture(
        items,
        PictureAdjust.colorTemp() ?: return null,
        PictureAdjust.noiseReduction() ?: return null,
        PictureAdjust.motion() ?: return null,
        PictureAdjust.gamma() ?: return null,
        PictureAdjust.dynamicContrast() ?: return null,
        PictureAdjust.localContrast() ?: return null,
        PictureAdjust.hdr() ?: return null,
    )
}

private val NoiseLevels = listOf("Выкл", "Низкое", "Среднее", "Высокое", "Авто")
private val MotionLevels = listOf("Выкл", "Слабая", "Средняя", "Сильная")
private val LocalContrastLevels = listOf("Выкл", "Низкий", "Средний", "Высокий")
private val GammaLevels = listOf("1.8", "1.9", "2.0", "2.1", "2.2", "2.3", "2.4", "2.5", "2.6")

/** XGIMI's defaults for the custom mode, as the projector came. */
private val CustomDefaults = CustomPicture(
    items = mapOf(PictureAdjust.BRIGHTNESS to 50, PictureAdjust.CONTRAST to 50, PictureAdjust.SATURATION to 50, PictureAdjust.SHARPNESS to 50),
    colorTemp = 1,
    noise = 2,
    motion = 3,
    gamma = 4,
    dynamicContrast = true,
    localContrast = 2,
    hdr = true,
)

/** The custom picture mode's settings, laid out like XGIMI's page (basic, then advanced). */
@Composable
private fun CustomPictureControls() {
    val context = LocalContext.current
    // XGIMI's service binds asynchronously on first use, so poll briefly for the values.
    val loaded by produceState<CustomPicture?>(null) {
        XgimiService.bind(context)
        repeat(20) {
            withContext(Dispatchers.IO) { readCustomPicture() }?.let {
                value = it
                return@produceState
            }
            delay(250)
        }
    }
    val initial = loaded
    if (initial == null) {
        T("Загрузка…", 14.sp, color = PanelDim)
        return
    }
    var values by remember(initial) { mutableStateOf(initial) }
    // Writes go to a background queue; a slider drag keeps only its latest value per control.
    fun update(key: String, apply: () -> Unit, next: CustomPicture) {
        PanelIo.submit("pic-$key", apply)
        values = next
    }
    val sliders = listOf(
        Triple(PictureAdjust.BRIGHTNESS, "Яркость", Icons.Rounded.WbSunny),
        Triple(PictureAdjust.CONTRAST, "Контраст", Icons.Rounded.Contrast),
        Triple(PictureAdjust.SATURATION, "Насыщенн.", Icons.Rounded.WaterDrop),
        Triple(PictureAdjust.SHARPNESS, "Резкость", Icons.Rounded.Details),
    )
    sliders.forEach { (item, label, icon) ->
        LevelSlider(icon, values.items.getValue(item), 100, Modifier.fillMaxWidth().padding(bottom = 8.dp), label) {
            update("item$item", { PictureAdjust.set(item, it) }, values.copy(items = values.items + (item to it)))
        }
    }
    Selector("Шумоподавление", NoiseLevels.getOrElse(values.noise) { "?" }, Modifier.fillMaxWidth().padding(bottom = 8.dp)) { delta ->
        val next = (values.noise + delta).mod(NoiseLevels.size)
        update("noise", { PictureAdjust.setNoiseReduction(next) }, values.copy(noise = next))
    }
    T("Цветовая температура", 14.sp, color = PanelDim)
    Spacer(Modifier.height(8.dp))
    PairRow {
        listOf("Холодная" to 0, "Станд." to 1, "Тёплая" to 2).forEach { (label, temp) ->
            Chip(label, values.colorTemp == temp, Modifier.weight(1f)) {
                update("temp", { PictureAdjust.setColorTemp(temp) }, values.copy(colorTemp = temp))
            }
        }
    }

    Section("Расширенные")
    Selector("Плавность (MEMC)", MotionLevels.getOrElse(values.motion) { "?" }, Modifier.fillMaxWidth().padding(bottom = 8.dp)) { delta ->
        val next = (values.motion + delta).mod(MotionLevels.size)
        update("motion", { PictureAdjust.setMotion(next) }, values.copy(motion = next))
    }
    Selector("Гамма", GammaLevels.getOrElse(values.gamma) { "?" }, Modifier.fillMaxWidth().padding(bottom = 8.dp)) { delta ->
        val next = (values.gamma + delta).coerceIn(0, GammaLevels.lastIndex)
        update("gamma", { PictureAdjust.setGamma(next) }, values.copy(gamma = next))
    }
    Toggle("Динамический контраст", values.dynamicContrast, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        val next = !values.dynamicContrast
        update("dyn", { PictureAdjust.setDynamicContrast(next) }, values.copy(dynamicContrast = next))
    }
    Toggle("HDR (авто)", values.hdr, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        val next = !values.hdr
        update("hdr", { PictureAdjust.setHdr(next) }, values.copy(hdr = next))
    }
    Selector("Локальный контраст", LocalContrastLevels.getOrElse(values.localContrast) { "?" }, Modifier.fillMaxWidth().padding(bottom = 8.dp)) { delta ->
        val next = (values.localContrast + delta).mod(LocalContrastLevels.size)
        update("local", { PictureAdjust.setLocalContrast(next) }, values.copy(localContrast = next))
    }
    ListRow("Сбросить по умолчанию") {
        val d = CustomDefaults
        update("reset", {
            d.items.forEach { (item, v) -> PictureAdjust.set(item, v) }
            PictureAdjust.setColorTemp(d.colorTemp)
            PictureAdjust.setNoiseReduction(d.noise)
            PictureAdjust.setMotion(d.motion)
            PictureAdjust.setGamma(d.gamma)
            PictureAdjust.setDynamicContrast(d.dynamicContrast)
            PictureAdjust.setLocalContrast(d.localContrast)
            PictureAdjust.setHdr(d.hdr)
        }, d)
    }
}

@Composable
private fun PerformanceWarning(onConfirm: () -> Unit, onCancel: () -> Unit) {
    val confirm = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { confirm.requestFocus() }
    }
    Column(
        Modifier
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .border(1.dp, Color(0x66FFB74D), RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        T("Режим производительности", 16.sp, color = PanelText, weight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        BasicText(
            "Максимальная яркость. Вентиляция не должна быть закрыта, в комнате — не выше 25 °C. " +
                "Долгое использование может перегреть проектор и сократить срок службы.",
            style = TextStyle(color = PanelDim, fontSize = 13.sp),
        )
        Spacer(Modifier.height(10.dp))
        PairRow {
            Chip("Включить", false, Modifier.weight(1f).focusRequester(confirm), onClick = onConfirm)
            Chip("Отмена", false, Modifier.weight(1f), onClick = onCancel)
        }
    }
}

/** The projector's light-source brightness (0..10), the same setting as XGIMI's Brightness page. */
@Composable
internal fun BrightnessSlider(modifier: Modifier) {
    var level by remember { mutableStateOf(Lumens.level()) }
    val current = level ?: return
    LevelSlider(Icons.Rounded.BrightnessMedium, current, Lumens.MAX, modifier) {
        // Level 0 would leave a nearly black picture; keep the image usable.
        val value = it.coerceAtLeast(1)
        level = value
        PanelIo.submit("lumens") { Lumens.setLevel(value) }
    }
}
