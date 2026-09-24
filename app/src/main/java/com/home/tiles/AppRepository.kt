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
)

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

    fun toggleHidden(pkg: String) {
        hidden = hidden.toMutableSet().apply { if (!remove(pkg)) add(pkg) }
    }

    fun togglePinned(pkg: String) {
        val current = pinned
        pinned = if (pkg in current) current - pkg else listOf(pkg) + current
    }

    /** Launchable apps: pinned first, then most recently used, then by name. */
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
        return entries.sortedWith(
            compareBy<AppEntry> { if (it.pinned) pins.indexOf(it.pkg) else Int.MAX_VALUE }
                .thenByDescending { it.lastUsed }
                .thenBy { it.label.lowercase() },
        )
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
        // Fill the square tile with the banner's own edge colour so it blends in.
        val corner = bitmap.getPixel(4, 4)
        val edge = if (android.graphics.Color.alpha(corner) > 200) {
            Color(corner)
        } else {
            Color(Palette.from(bitmap).generate().getDominantColor(FALLBACK_TILE))
        }
        return TileArt(bitmap.asImageBitmap(), isBanner = true, top = edge, bottom = edge)
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
        return TileArt(bitmap.asImageBitmap(), isBanner = false, top = base, bottom = lerp(base, Color.Black, 0.3f))
    }

    /**
     * Draws an adaptive icon's background and foreground layers across the whole tile, so the
     * tile is the icon itself (like a Switch game cover) instead of a badge on a colored card.
     * Layers are 108dp with the visible icon in the middle 72dp; a slight zoom keeps the logo large.
     */
    private fun adaptiveTile(icon: AdaptiveIconDrawable): TileArt? {
        val background = icon.background ?: return null
        val size = 432
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bleed = (size * 0.08f).toInt()
        for (layer in listOfNotNull(background, icon.foreground)) {
            layer.setBounds(-bleed, -bleed, size + bleed, size + bleed)
            layer.draw(canvas)
        }
        val base = Color(Palette.from(bitmap).generate().getDominantColor(FALLBACK_TILE))
        return TileArt(bitmap.asImageBitmap(), isBanner = false, top = base, bottom = base, fullBleed = true)
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
            "ru.vk.store",
            "ru.vk.store.tv",
        )
    }
}
