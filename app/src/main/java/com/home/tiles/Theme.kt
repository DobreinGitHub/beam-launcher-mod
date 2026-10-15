package com.home.tiles

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** User-facing launcher settings. Snapshot state, so anything that reads them recomposes on change. */
object LauncherSettings {
    private lateinit var prefs: SharedPreferences

    private val darkState = mutableStateOf(false)
    private val backgroundState = mutableStateOf(0)
    private val nowPlayingState = mutableStateOf(true)
    private val secondRowState = mutableStateOf(SECOND_ROW_AUTO)
    private val xmbColorState = mutableStateOf(-1)
    private val bgAnimationState = mutableStateOf(true)
    private val ambientTintState = mutableStateOf(true)
    private val seenChannelsState = mutableStateOf<Set<String>?>(null)
    private val followXgimiState = mutableStateOf(true)
    private val xgimiDarkState = mutableStateOf<Boolean?>(null)

    /** XGIMI's own «Стиль темы» (Общие → Стиль темы): dark, null where there's no such setting. */
    val xgimiDark: Boolean? get() = xgimiDarkState.value

    /**
     * Reads XGIMI's theme. The firmware applies it as a skin, named in a system property: the
     * light one has "light" in its name (xgimi-skin-black_light_blue); the dark and blue ones
     * don't. «Авто» swaps the skin by the time of day, so following the skin covers it too.
     * Read on resume: the setting is changed in XGIMI's own settings app.
     */
    fun refreshXgimiTheme() {
        xgimiDarkState.value = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "persist.sys.xgimi.theme.url") as String
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { !it.contains("light", ignoreCase = true) }
    }

    /** Light and dark as XGIMI has them («Как в XGIMI»), unless chosen here. */
    var followXgimi: Boolean
        get() = followXgimiState.value && xgimiDarkState.value != null
        set(value) { followXgimiState.value = value; prefs.edit().putBoolean("followXgimiTheme", value).apply() }

    fun init(context: Context) {
        // Shared by the launcher activity and the overlay service in the same process.
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        darkState.value = prefs.getBoolean("dark", false)
        backgroundState.value = prefs.getInt("background", 0).coerceIn(Backgrounds.indices)
        nowPlayingState.value = prefs.getBoolean("nowPlaying", true)
        secondRowState.value = prefs.getString("secondRow", SECOND_ROW_AUTO) ?: SECOND_ROW_AUTO
        xmbColorState.value = prefs.getInt("xmbColor", -1)
        bgAnimationState.value = prefs.getBoolean("bgAnimation", true)
        ambientTintState.value = prefs.getBoolean("ambientTint", true)
        seenChannelsState.value = prefs.getStringSet("seenChannels", null)?.toSet()
        followXgimiState.value = prefs.getBoolean("followXgimiTheme", true)
        refreshXgimiTheme()
    }

    /**
     * Channels the rows list has shown; the others are new («Новый»). Null until the channels are
     * first read, when all of them count as seen (a first start doesn't call everything new).
     */
    val seenChannels: Set<String>? get() = seenChannelsState.value

    fun markChannelsSeen(keys: Collection<String>) {
        val next = seenChannelsState.value.orEmpty() + keys
        if (next == seenChannelsState.value) return
        seenChannelsState.value = next
        prefs.edit().putStringSet("seenChannels", next).apply()
    }

    /** The theme in use: XGIMI's while following it, else the one chosen here. Setting it chooses it here. */
    var dark: Boolean
        get() = if (followXgimi) xgimiDarkState.value == true else darkState.value
        set(value) {
            darkState.value = value
            prefs.edit().putBoolean("dark", value).apply()
            followXgimi = false
        }

    var background: Int
        get() = backgroundState.value
        set(value) { backgroundState.value = value; prefs.edit().putInt("background", value).apply() }

    var nowPlaying: Boolean
        get() = nowPlayingState.value
        set(value) { nowPlayingState.value = value; prefs.edit().putBoolean("nowPlaying", value).apply() }

    /** Channel key shown under the tiles, or [SECOND_ROW_AUTO] / [SECOND_ROW_OFF]. */
    var secondRow: String
        get() = secondRowState.value
        set(value) { secondRowState.value = value; prefs.edit().putString("secondRow", value).apply() }

    /** XMB colour index into [XmbColors], or -1 for the current month's colour like the PS3. */
    var xmbColor: Int
        get() = xmbColorState.value
        set(value) { xmbColorState.value = value; prefs.edit().putInt("xmbColor", value).apply() }

    var bgAnimation: Boolean
        get() = bgAnimationState.value
        set(value) { bgAnimationState.value = value; prefs.edit().putBoolean("bgAnimation", value).apply() }

    /** The background takes on a faint tint of the selected app's colour (see [AmbientWash]). */
    var ambientTint: Boolean
        get() = ambientTintState.value
        set(value) { ambientTintState.value = value; prefs.edit().putBoolean("ambientTint", value).apply() }
}

/** Switch-like light and dark palettes, picked by [LauncherSettings.dark]. */
object Colors {
    private val dark get() = LauncherSettings.dark

    val Background get() = if (dark) Color(0xFF2D2D2D) else Color(0xFFFAFAFA)
    val Surface get() = if (dark) Color(0xFF3A3A3C) else Color.White
    val Button get() = if (dark) Color(0xFF474747) else Color(0xFFEBEBEB)
    val Text get() = if (dark) Color(0xFFF2F2F2) else Color(0xFF2D2D2D)
    val TextDim get() = if (dark) Color(0xFFA8A8A8) else Color(0xFF6E6E6E)
    val Accent = Color(0xFF00C3E3)

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
    @StringRes val nameRes: Int,
    val light: List<Color>,
    val dark: List<Color>,
    /** PS3-style animated backdrop; colours come from [xmbStops]. */
    val xmb: Boolean = false,
) {
    val name: String get() = tr(nameRes)
}

val Backgrounds = listOf(
    BackgroundPreset(R.string.bg_normal, listOf(Color(0xFFFAFAFA)), listOf(Color(0xFF2D2D2D))),
    BackgroundPreset(R.string.bg_ocean, listOf(Color(0xFFDDF1FF), Color(0xFFFAFAFA)), listOf(Color(0xFF0F2027), Color(0xFF2C5364))),
    BackgroundPreset(R.string.bg_sunset, listOf(Color(0xFFFFE3D3), Color(0xFFFFF6EE)), listOf(Color(0xFF2B1B2E), Color(0xFF5A2A3C))),
    BackgroundPreset(R.string.bg_forest, listOf(Color(0xFFDDF3E4), Color(0xFFFAFAFA)), listOf(Color(0xFF10251C), Color(0xFF24493A))),
    BackgroundPreset(R.string.bg_lavender, listOf(Color(0xFFEAE3FF), Color(0xFFFAFAFA)), listOf(Color(0xFF1D1830), Color(0xFF3A3060))),
    BackgroundPreset(R.string.bg_xmb, emptyList(), emptyList(), xmb = true),
    // Added later: appended, since the choice is saved as an index (the panel shows XMB last).
    BackgroundPreset(R.string.bg_graphite, listOf(Color(0xFFF2F2F2), Color(0xFFDCDCDC)), listOf(Color(0xFF1C1C1E), Color(0xFF3A3A3C))),
    BackgroundPreset(R.string.bg_midnight, listOf(Color(0xFFE3E8FF), Color(0xFFFAFAFA)), listOf(Color(0xFF0B1026), Color(0xFF1E2A5A))),
    BackgroundPreset(R.string.bg_burgundy, listOf(Color(0xFFFBE3E8), Color(0xFFFFF6F7)), listOf(Color(0xFF2A0E16), Color(0xFF5C1A2B))),
    BackgroundPreset(R.string.bg_emerald, listOf(Color(0xFFD9F7EE), Color(0xFFFAFAFA)), listOf(Color(0xFF062B26), Color(0xFF0E5A4E))),
    BackgroundPreset(R.string.bg_aurora, listOf(Color(0xFFDDF7F0), Color(0xFFE8E6FF), Color(0xFFFFE9F3)), listOf(Color(0xFF0B1B2B), Color(0xFF1C4B5A), Color(0xFF3B2A5E))),
    BackgroundPreset(R.string.bg_sand, listOf(Color(0xFFFFF3DC), Color(0xFFFAFAFA)), listOf(Color(0xFF2A2216), Color(0xFF4D3E28))),
)

