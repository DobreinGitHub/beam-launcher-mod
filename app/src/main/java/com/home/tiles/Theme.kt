package com.home.tiles

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** User-facing launcher settings. Snapshot state, so anything that reads them recomposes on change. */
object LauncherSettings {
    private lateinit var prefs: SharedPreferences

    private val darkState = mutableStateOf(false)
    private val backgroundState = mutableStateOf(0)
    private val largeTilesState = mutableStateOf(false)
    private val soundsState = mutableStateOf(true)
    private val nowPlayingState = mutableStateOf(true)
    private val usbTileState = mutableStateOf(true)
    private val hdmiTileState = mutableStateOf(true)
    private val secondRowState = mutableStateOf(SECOND_ROW_AUTO)
    private val xmbColorState = mutableStateOf(-1)
    private val bgAnimationState = mutableStateOf(true)
    private val layoutState = mutableStateOf(LAYOUT_FOCUS)

    fun init(context: Context) {
        // Shared by the launcher activity and the overlay service in the same process.
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        darkState.value = prefs.getBoolean("dark", false)
        backgroundState.value = prefs.getInt("background", 0).coerceIn(Backgrounds.indices)
        largeTilesState.value = prefs.getBoolean("largeTiles", false)
        soundsState.value = prefs.getBoolean("sounds", true)
        nowPlayingState.value = prefs.getBoolean("nowPlaying", true)
        usbTileState.value = prefs.getBoolean("usbTile", true)
        hdmiTileState.value = prefs.getBoolean("hdmiTile", true)
        secondRowState.value = prefs.getString("secondRow", SECOND_ROW_AUTO) ?: SECOND_ROW_AUTO
        xmbColorState.value = prefs.getInt("xmbColor", -1)
        bgAnimationState.value = prefs.getBoolean("bgAnimation", true)
        layoutState.value = prefs.getString("layout", LAYOUT_FOCUS) ?: LAYOUT_FOCUS
    }

    var dark: Boolean
        get() = darkState.value
        set(value) { darkState.value = value; prefs.edit().putBoolean("dark", value).apply() }

    var background: Int
        get() = backgroundState.value
        set(value) { backgroundState.value = value; prefs.edit().putInt("background", value).apply() }

    var largeTiles: Boolean
        get() = largeTilesState.value
        set(value) { largeTilesState.value = value; prefs.edit().putBoolean("largeTiles", value).apply() }

    var sounds: Boolean
        get() = soundsState.value
        set(value) { soundsState.value = value; prefs.edit().putBoolean("sounds", value).apply() }

    var nowPlaying: Boolean
        get() = nowPlayingState.value
        set(value) { nowPlayingState.value = value; prefs.edit().putBoolean("nowPlaying", value).apply() }

    var usbTile: Boolean
        get() = usbTileState.value
        set(value) { usbTileState.value = value; prefs.edit().putBoolean("usbTile", value).apply() }

    var hdmiTile: Boolean
        get() = hdmiTileState.value
        set(value) { hdmiTileState.value = value; prefs.edit().putBoolean("hdmiTile", value).apply() }

    /** Channel key shown under the tiles, or [SECOND_ROW_AUTO] / [SECOND_ROW_OFF]. */
    var secondRow: String
        get() = secondRowState.value
        set(value) { secondRowState.value = value; prefs.edit().putString("secondRow", value).apply() }

    /** XMB colour index into [XmbColors], or -1 for the current month's colour like the PS3. */
    var xmbColor: Int
        get() = xmbColorState.value
        set(value) { xmbColorState.value = value; prefs.edit().putInt("xmbColor", value).apply() }

    /** Home layout: [LAYOUT_FOCUS] (big selected tile) or [LAYOUT_CLASSIC] (Switch row). */
    var layout: String
        get() = layoutState.value
        set(value) { layoutState.value = value; prefs.edit().putString("layout", value).apply() }

    var bgAnimation: Boolean
        get() = bgAnimationState.value
        set(value) { bgAnimationState.value = value; prefs.edit().putBoolean("bgAnimation", value).apply() }
}

/** Switch-like light and dark palettes, picked by [LauncherSettings.dark]. */
object Colors {
    private val dark get() = LauncherSettings.dark

    val Background get() = if (dark) Color(0xFF2D2D2D) else Color(0xFFFAFAFA)
    val Surface get() = if (dark) Color(0xFF3A3A3C) else Color.White
    val Button get() = if (dark) Color(0xFF474747) else Color(0xFFEBEBEB)
    val Text get() = if (dark) Color(0xFFF2F2F2) else Color(0xFF2D2D2D)
    val TextDim get() = if (dark) Color(0xFFA8A8A8) else Color(0xFF6E6E6E)
    val Divider get() = if (dark) Color(0xFF9A9A9A) else Color(0xFF3C3C3C)
    val AllTile get() = if (dark) Color(0xFF4A4A4A) else Color(0xFFE3E3E3)
    val Accent = Color(0xFF00C3E3)
    val AccentGlow = Color(0xFF7FF0FF)

    val isXmb get() = Backgrounds[LauncherSettings.background].xmb

    fun backgroundBrush(): Brush = presetBrush(Backgrounds[LauncherSettings.background])

    fun presetBrush(preset: BackgroundPreset): Brush {
        val stops = when {
            preset.xmb -> xmbStops(dark)
            dark -> preset.dark
            else -> preset.light
        }
        return if (stops.size == 1) Brush.linearGradient(stops + stops) else Brush.linearGradient(stops)
    }
}

class BackgroundPreset(
    val name: String,
    val light: List<Color>,
    val dark: List<Color>,
    /** PS3-style animated backdrop; colours come from [xmbStops]. */
    val xmb: Boolean = false,
)

val Backgrounds = listOf(
    BackgroundPreset("Обычный", listOf(Color(0xFFFAFAFA)), listOf(Color(0xFF2D2D2D))),
    BackgroundPreset("Океан", listOf(Color(0xFFDDF1FF), Color(0xFFFAFAFA)), listOf(Color(0xFF0F2027), Color(0xFF2C5364))),
    BackgroundPreset("Закат", listOf(Color(0xFFFFE3D3), Color(0xFFFFF6EE)), listOf(Color(0xFF2B1B2E), Color(0xFF5A2A3C))),
    BackgroundPreset("Лес", listOf(Color(0xFFDDF3E4), Color(0xFFFAFAFA)), listOf(Color(0xFF10251C), Color(0xFF24493A))),
    BackgroundPreset("Лаванда", listOf(Color(0xFFEAE3FF), Color(0xFFFAFAFA)), listOf(Color(0xFF1D1830), Color(0xFF3A3060))),
    BackgroundPreset("XMB", emptyList(), emptyList(), xmb = true),
)

