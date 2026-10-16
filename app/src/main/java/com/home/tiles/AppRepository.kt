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
import android.content.ComponentCallbacks2
import android.util.LruCache
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlin.math.roundToInt

private const val ART_CACHE_BYTES = 24 * 1024 * 1024

data class AppEntry(
    val pkg: String,
    val component: ComponentName,
    val label: String,
    val lastUsed: Long,
    val hidden: Boolean,
    val pinned: Boolean,
    val isSystem: Boolean,
    val updated: Long,
    /** When it was first installed (0 when unknown). */
    val installed: Long = 0L,
) {
    /** Installed this past week and not opened since: marked «Новое». */
    val isNew: Boolean
        get() = installed > 0 && System.currentTimeMillis() - installed < NEW_APP_MS && lastUsed < installed
}

private const val NEW_APP_MS = 7L * 24 * 60 * 60 * 1000

/** An app icon on a tile without a banner: its height in the tile's, and its left edge in the tile's width. */
internal const val ICON_HEIGHT = 0.5f
internal const val ICON_START = 0.07f

class TileArt(
    val image: ImageBitmap,
    val isBanner: Boolean,
    val top: Color,
    val bottom: Color,
    /** The image is the whole tile (an adaptive icon's layers), not a logo on a colored card. */
    val fullBleed: Boolean = false,
    /** The light around the tile when selected: the picture's liveliest colour, see [glowOf]. */
    val glow: Color = Color.White,
    /** The app's name beside its icon (no banner): dark on a light tile, white otherwise. */
    val label: Color = Color.White,
)

private fun labelOn(background: Color) = if (background.luminance() > 0.6f) Color(0xFF1A1A1A) else Color.White

/**
 * The colour a selected tile glows in, Google TV style: the picture's most vivid colour (YouTube
 * red, Kinopoisk orange) rather than its edge, which banners often leave black. Lifted towards
 * white when dark, so the glow shows on a dark background; white when the picture has no colour.
 */
internal fun glowOf(bitmap: Bitmap): Color {
    val palette = Palette.from(bitmap).generate()
    val rgb = palette.vibrantSwatch?.rgb
        ?: palette.lightVibrantSwatch?.rgb
        ?: palette.darkVibrantSwatch?.rgb
        ?: palette.mutedSwatch?.rgb
        ?: return Color.White
    val color = Color(rgb)
    return if (color.luminance() < 0.12f) lerp(color, Color.White, 0.5f) else color
}

/**
 * The remote-button stubs of earlier releases (they took the package names the firmware starts for
 * the remote's four app keys). The installer removes them now that tonisaf's module assigns the keys;
 * where one is left, it is not an app to show.
 */
private val oldStubPackages = setOf("com.cibn.tv", "com.ktcp.tvvideo", "com.gitvjimi.video", "com.hunantv.license")

class AppRepository(private val context: Context) {
    private val pm = context.packageManager
    private val prefs = context.getSharedPreferences("tiles", Context.MODE_PRIVATE)

    // Tile pictures at the most pixels a tile ever shows (the widest tile, lifted to be moved):
    // larger ones cost memory on a 1.4 GB projector and made the GPU scale them down every frame.
    private val artWidth = (MAX_TILE_WIDTH_DP * FOCUS_SCALE_LIFTED * context.resources.displayMetrics.density).roundToInt()
    private val artHeight = artWidth * 9 / 16
    // A logo on a coloured card fills 62% of the tile's height (see AppArt).
    private val iconSide = (artHeight * ICON_HEIGHT).roundToInt()

    // Bounded, so a long list of apps (All apps loads them all) can't grow it without end;
    // at about half a megabyte a picture it holds some fifty.
    private val artCache = object : LruCache<String, TileArt>(ART_CACHE_BYTES) {
        override fun sizeOf(key: String, value: TileArt) = value.image.asAndroidBitmap().allocationByteCount
    }

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
                if (activity.packageName == context.packageName || activity.packageName in oldStubPackages) continue
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
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
            AppEntry(
                pkg = pkg,
                component = value.first,
                label = value.second,
                lastUsed = usage[pkg] ?: 0L,
                hidden = pkg in hiddenSet,
                pinned = pkg in pins,
                isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                    appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0,
                updated = info?.lastUpdateTime ?: 0L,
                installed = info?.firstInstallTime ?: 0L,
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

    /**
     * Gives memory back when the system runs short (see MainActivity.onTrimMemory): the least
     * recently shown pictures go, the home row's stay as long as they can. Not on every trip to
     * another app, so coming home doesn't redraw the tiles from scratch.
     */
    fun trimArt(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ->
                artCache.trimToSize(ART_CACHE_BYTES / 4)
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                artCache.trimToSize(ART_CACHE_BYTES / 2)
        }
    }

    fun cachedArt(entry: AppEntry): TileArt? = artCache.get(cacheKey(entry))

    suspend fun loadArt(entry: AppEntry): TileArt? = withContext(Dispatchers.IO) {
        artCache.get(cacheKey(entry))?.let { return@withContext it }
        val art = runCatching { banner(entry) ?: icon(entry) }.getOrNull()
        art?.also {
            artCache.put(cacheKey(entry), it)
            // Sent to the GPU now, in the background: uploaded only when first drawn, a screen's
            // worth of pictures stalled its first frames (All apps' slide showed as a jump).
            it.image.prepareToDraw()
        }
    }

    private fun cacheKey(entry: AppEntry) = "${entry.pkg}:${entry.updated}"

    private fun banner(entry: AppEntry): TileArt? {
        val drawable = runCatching { pm.getActivityBanner(entry.component) }.getOrNull()
            ?: runCatching { pm.getApplicationBanner(entry.pkg) }.getOrNull()
            ?: return null
        val w = artWidth
        val h = if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            w * drawable.intrinsicHeight / drawable.intrinsicWidth
        } else {
            artHeight
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
        val bitmap = drawable.toBitmap(iconSide, iconSide)
        val palette = Palette.from(bitmap).generate()
        var base = Color(
            palette.vibrantSwatch?.rgb
                ?: palette.mutedSwatch?.rgb
                ?: palette.getDominantColor(FALLBACK_TILE),
        )
        // Keep white/pale icons readable.
        if (base.luminance() > 0.6f) base = lerp(base, Color.Black, 0.45f)
        return TileArt(bitmap.asImageBitmap(), isBanner = false, top = base, bottom = lerp(base, Color.Black, 0.3f), glow = glowOf(bitmap), label = labelOn(base))
    }

    /**
     * Draws an adaptive icon across the whole 16:9 tile, so the tile is the icon itself instead
     * of a badge on a colored card: the background layer covers the tile (as a square as wide as
     * it, cropped top and bottom), the foreground sits at the left where a plain icon does, with
     * the app's name beside it (see AppArt). Layers are 108dp with the visible icon in the middle
     * 72dp, which is sized like a plain icon.
     */
    private fun adaptiveTile(icon: AdaptiveIconDrawable): TileArt? {
        val background = icon.background ?: return null
        val w = artWidth
        val h = artHeight
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val top = (h - w) / 2
        background.setBounds(0, top, w, top + w)
        background.draw(canvas)
        icon.foreground?.let { foreground ->
            val visible = h * ICON_HEIGHT
            val side = (visible * 108 / 72).toInt()
            val left = (w * ICON_START - (side - visible) / 2).toInt()
            val fgTop = (h - side) / 2
            foreground.setBounds(left, fgTop, left + side, fgTop + side)
            foreground.draw(canvas)
        }
        val base = Color(Palette.from(bitmap).generate().getDominantColor(FALLBACK_TILE))
        // Where the name goes, right of the icon: the background layer there, not the picture's
        // main colour (Palette leaves white out, and a red icon on white got white words).
        val behindName = listOf(0.6f to 0.3f, 0.75f to 0.5f, 0.9f to 0.7f).map { (x, y) -> Color(bitmap.getPixel((w * x).toInt(), (h * y).toInt())) }
        val label = labelOn(Color(behindName.map { it.red }.average().toFloat(), behindName.map { it.green }.average().toFloat(), behindName.map { it.blue }.average().toFloat()))
        return TileArt(bitmap.asImageBitmap(), isBanner = false, top = base, bottom = base, fullBleed = true, glow = glowOf(bitmap), label = label)
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
