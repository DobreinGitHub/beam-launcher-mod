package com.home.tiles

import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class AppEntry(
    val pkg: String,
    val component: ComponentName,
    val label: String,
    val lastUsed: Long,
    val hidden: Boolean,
    val pinned: Boolean,
    val isSystem: Boolean,
    val updated: Long,
)

class TileArt(
    val image: ImageBitmap,
    val isBanner: Boolean,
    val top: Color,
    val bottom: Color,
    /** The image is the whole tile (an adaptive icon's layers), not a logo on a colored card. */
    val fullBleed: Boolean = false,
    /** The light around the tile when selected: the picture's liveliest colour, see [glowOf]. */
    val glow: Color = Color.White,
)

/**
 * The colour a selected tile glows in, Google TV style: the picture's most vivid colour (YouTube
 * red, Kinopoisk orange) rather than its edge, which banners often leave black. Lifted towards
 * white when dark, so the glow shows on a dark background; white when the picture has no colour.
 */
private fun glowOf(bitmap: Bitmap): Color {
    val palette = Palette.from(bitmap).generate()
    val rgb = palette.vibrantSwatch?.rgb
        ?: palette.lightVibrantSwatch?.rgb
        ?: palette.darkVibrantSwatch?.rgb
        ?: palette.mutedSwatch?.rgb
        ?: return Color.White
    val color = Color(rgb)
    return if (color.luminance() < 0.12f) lerp(color, Color.White, 0.5f) else color
}

class AppRepository(private val context: Context) {
    private val pm = context.packageManager
    private val prefs = context.getSharedPreferences("tiles", Context.MODE_PRIVATE)
    private val artCache = ConcurrentHashMap<String, TileArt>()

    private var hidden: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN, null) ?: DEFAULT_HIDDEN
        set(value) = prefs.edit().putStringSet(KEY_HIDDEN, value).apply()

    private var pinned: List<String>
        get() = prefs.getString(KEY_PINNED, "").orEmpty().split(',').filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString(KEY_PINNED, value.joinToString(",")).apply()

    /**
     * The home row's order, set by hand with "Move": row keys (package names, "hdmi:…", "usb:…").
     * A Compose state, so the row redraws as soon as a tile moves.
     */
    private val orderState = mutableStateOf(
        prefs.getString(KEY_ORDER, null)?.split(',')?.filter { it.isNotEmpty() },
    )

    private var order: List<String>
        get() = orderState.value.orEmpty()
        set(value) {
            orderState.value = value
            prefs.edit().putString(KEY_ORDER, value.joinToString(",")).apply()
        }

    @Synchronized
    fun toggleHidden(pkg: String) {
        val wasHidden = pkg in hidden
        hidden = hidden.toMutableSet().apply { if (!remove(pkg)) add(pkg) }
        // Shown again: it comes back at the end of the row, like a newly installed app.
        if (wasHidden) order = order - pkg
    }

    /**
     * [keys] in the home row's order. Keys the order doesn't know yet keep their old default
     * place: HDMI/USB tiles before everything, apps after everything.
     */
    fun arrange(keys: List<String>): List<String> {
        val index = order.withIndex().associate { (i, key) -> key to i }
        val (known, unknown) = keys.partition { it in index }
        val (newSpecial, newApps) = unknown.partition { it.contains(':') }
        return newSpecial + known.sortedBy { index.getValue(it) } + newApps
    }

    /** The whole current order, as [arrange] would show it, so tiles not saved yet can be moved. */
    @Synchronized
    fun beginMove(visible: List<String>): List<String> {
        val missing = visible.filter { it !in order }
        if (missing.isNotEmpty()) {
            val (special, apps) = missing.partition { it.contains(':') }
            order = special + order + apps
        }
        return order
    }

    /** Swaps [key] with its visible neighbour [step] places away (-1 left, 1 right). */
    @Synchronized
    fun move(visible: List<String>, key: String, step: Int): Boolean {
        val i = visible.indexOf(key)
        val neighbour = visible.getOrNull(i + step) ?: return false
        if (i < 0) return false
        val full = beginMove(visible).toMutableList()
        val a = full.indexOf(key)
        val b = full.indexOf(neighbour)
        full[a] = neighbour
        full[b] = key
        order = full
        return true
    }

    /** For the adb hook. */
    fun savedOrder(): List<String> = order

    /** Puts back an order saved by [beginMove] (a cancelled move). */
    @Synchronized
    fun restoreOrder(saved: List<String>) {
        order = saved
    }

    /**
     * Launchable apps in the home row's order; new apps go to its end. Synchronized: reloads can
     * overlap (resume, package broadcasts), and two of them seeding the order at once left the
     * screen and the saved order different.
     */
    @Synchronized
    fun loadApps(): List<AppEntry> {
        val found = LinkedHashMap<String, Pair<ComponentName, String>>()
        // Leanback entries win over phone-style ones for the same package.
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(intent, 0)) {
                val activity = info.activityInfo
                if (activity.packageName == context.packageName || activity.packageName in RemoteButtons.stubPackages) continue
                found.putIfAbsent(
                    activity.packageName,
                    ComponentName(activity.packageName, activity.name) to info.loadLabel(pm).toString(),
                )
            }
        }

        val usage = lastUsedTimes()
        val hiddenSet = hidden
        val pins = pinned
        val entries = found.mapNotNull { (pkg, value) ->
            val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            val updated = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
            AppEntry(
                pkg = pkg,
                component = value.first,
                label = value.second,
                lastUsed = usage[pkg] ?: 0L,
                hidden = pkg in hiddenSet,
                pinned = pkg in pins,
                isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                    appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0,
                updated = updated,
            )
        }
        // The first start fixes the order once the old way: pinned, then recently used, then by name.
        val byUse = entries.sortedWith(
            compareBy<AppEntry> { if (it.pinned) pins.indexOf(it.pkg) else Int.MAX_VALUE }
                .thenByDescending { it.lastUsed }
                .thenBy { it.label.lowercase() },
        )
        if (orderState.value == null) order = byUse.map { it.pkg }
        // Apps the order hasn't seen are saved at its end, so later ones line up behind them.
        val saved = order.toSet()
        val added = byUse.filter { it.pkg !in saved && !it.hidden }.map { it.pkg }
        if (added.isNotEmpty()) order = order + added
        val index = order.withIndex().associate { (i, key) -> key to i }
        return byUse.sortedBy { index[it.pkg] ?: Int.MAX_VALUE }
    }

    /** Empty unless the usage-stats app-op was granted over adb. */
    private fun lastUsedTimes(): Map<String, Long> = runCatching {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        usm.queryAndAggregateUsageStats(now - USAGE_WINDOW_MS, now).mapValues { it.value.lastTimeUsed }
    }.getOrDefault(emptyMap())

    fun cachedArt(entry: AppEntry): TileArt? = artCache[cacheKey(entry)]

    suspend fun loadArt(entry: AppEntry): TileArt? = withContext(Dispatchers.IO) {
        artCache[cacheKey(entry)]?.let { return@withContext it }
        val art = runCatching { banner(entry) ?: icon(entry) }.getOrNull()
        art?.also { artCache[cacheKey(entry)] = it }
    }

    private fun cacheKey(entry: AppEntry) = "${entry.pkg}:${entry.updated}"

    private fun banner(entry: AppEntry): TileArt? {
        val drawable = runCatching { pm.getActivityBanner(entry.component) }.getOrNull()
            ?: runCatching { pm.getApplicationBanner(entry.pkg) }.getOrNull()
            ?: return null
        val w = 640
        val h = if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            w * drawable.intrinsicHeight / drawable.intrinsicWidth
        } else {
            360
        }
        val bitmap = drawable.toBitmap(w, h)
        // Behind the banner (seen through transparent parts): its own edge colour, so it blends in.
        val corner = bitmap.getPixel(4, 4)
        val edge = if (android.graphics.Color.alpha(corner) > 200) {
            Color(corner)
        } else {
            Color(Palette.from(bitmap).generate().getDominantColor(FALLBACK_TILE))
        }
        return TileArt(bitmap.asImageBitmap(), isBanner = true, top = edge, bottom = edge, glow = glowOf(bitmap))
    }

    private fun icon(entry: AppEntry): TileArt {
        val drawable = highResIcon(entry) ?: pm.getActivityIcon(entry.component)
        if (drawable is AdaptiveIconDrawable) adaptiveTile(drawable)?.let { return it }
        val bitmap = drawable.toBitmap(384, 384)
        val palette = Palette.from(bitmap).generate()
        var base = Color(
            palette.vibrantSwatch?.rgb
                ?: palette.mutedSwatch?.rgb
                ?: palette.getDominantColor(FALLBACK_TILE),
        )
        // Keep white/pale icons readable.
        if (base.luminance() > 0.6f) base = lerp(base, Color.Black, 0.45f)
        return TileArt(bitmap.asImageBitmap(), isBanner = false, top = base, bottom = lerp(base, Color.Black, 0.3f), glow = glowOf(bitmap))
    }

    /**
     * Draws an adaptive icon across the whole 16:9 tile, so the tile is the icon itself instead
     * of a badge on a colored card: the background layer covers the tile (as a square as wide as
     * it, cropped top and bottom), the foreground sits in the middle. Layers are 108dp with the
     * visible icon in the middle 72dp, which is sized to 3/4 of the tile's height.
     */
    private fun adaptiveTile(icon: AdaptiveIconDrawable): TileArt? {
        val background = icon.background ?: return null
        val w = 768
        val h = 432
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val top = (h - w) / 2
        background.setBounds(0, top, w, top + w)
        background.draw(canvas)
        icon.foreground?.let { foreground ->
            val side = (h * 0.75f * 108 / 72).toInt()
            val left = (w - side) / 2
            val fgTop = (h - side) / 2
            foreground.setBounds(left, fgTop, left + side, fgTop + side)
            foreground.draw(canvas)
        }
        val base = Color(Palette.from(bitmap).generate().getDominantColor(FALLBACK_TILE))
        return TileArt(bitmap.asImageBitmap(), isBanner = false, top = base, bottom = base, fullBleed = true, glow = glowOf(bitmap))
    }

    private fun highResIcon(entry: AppEntry): Drawable? = runCatching {
        val info = pm.getActivityInfo(entry.component, 0)
        val res = pm.getResourcesForApplication(info.applicationInfo)
        val id = info.iconResource.takeIf { it != 0 } ?: info.applicationInfo.icon
        if (id == 0) null else res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null)
    }.getOrNull()

    private fun Drawable.toBitmap(w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, w, h)
        draw(Canvas(bitmap))
        return bitmap
    }

    companion object {
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_PINNED = "pinned"
        private const val KEY_ORDER = "order"
        private const val USAGE_WINDOW_MS = 60L * 24 * 60 * 60 * 1000
        private const val FALLBACK_TILE = 0xFF5A5A5A.toInt()

        /** XGIMI service apps and tools that are reachable from the top bar anyway. */
        private val DEFAULT_HIDDEN = setOf(
            "com.android.newsettings",
            "com.xgimi.upgrade",
            "com.xgimi.user",
            "com.xgimi.instruction30",
            "com.xgimi.manager",
            "com.xgimi.starmirror",
            "com.xgimi.atmosphere",
            "com.xgimi.vcontrol",
            "com.spocky.projengmenu",
            "com.zacharee1.systemuituner",
            "io.github.sds100.keymapper",
            "org.liskovsoft.androidtv.rukeyboard",
        )
    }
}
