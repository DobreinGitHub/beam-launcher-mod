@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.home.tiles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.ExperimentalComposeUiApi
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.asAndroidBitmap
import android.media.tv.TvContract
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Other apps' home-screen content from the Android TV provider: their preview channels
 * (SmartTube subscriptions, Spotify recents...) and the Watch Next list.
 * Needs READ_TV_LISTINGS (granted over adb) to see channels the stock launcher never approved.
 */
class TvItem(
    val key: String,
    val pkg: String,
    val title: String,
    val subtitle: String,
    val poster: Uri?,
    val intentUri: String?,
    /** The poster's shape as the app declares it (TvContract's ASPECT_RATIO_*), or null. */
    val aspect: Int? = null,
    /** How much of it has been watched, 0..1, when the app says. */
    val progress: Float? = null,
)

class TvChannel(
    val key: String,
    val name: String,
    val items: List<TvItem>,
    /** The app that publishes it; empty for Watch Next, which gathers several. */
    val pkg: String = "",
    /** That app's name. */
    val app: String? = null,
    /** Opens the app at this channel (the provider's app link), when it has one. */
    val appLink: String? = null,
) {
    /** The name with its app, where the app's icon isn't beside it: "Популярное · VoKino". */
    val label: String get() = if (app == null || app == name) name else "$name · $app"

    /** Its cards' shape: the one most of its programs declare; null (16:9) when none do. */
    val aspect: Int? = items.mapNotNull { it.aspect }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}

/** Width over height of a card for a provider shape: posters stand, everything else lies. */
internal fun aspectRatioOf(aspect: Int?): Float = when (aspect) {
    TvContract.PreviewPrograms.ASPECT_RATIO_3_2 -> 3f / 2f
    TvContract.PreviewPrograms.ASPECT_RATIO_4_3 -> 4f / 3f
    TvContract.PreviewPrograms.ASPECT_RATIO_1_1 -> 1f
    TvContract.PreviewPrograms.ASPECT_RATIO_2_3 -> 2f / 3f
    ASPECT_RATIO_MOVIE_POSTER -> 1f / 1.441f
    else -> 16f / 9f
}

// TvContract's ASPECT_RATIO_MOVIE_POSTER (1:1.441, VoKino's), named here as it came later than the others.
private const val ASPECT_RATIO_MOVIE_POSTER = 5

/**
 * A card's size for a channel's shape: wide ones as tall as a 16:9 card, standing posters taller,
 * so they don't come out as narrow strips (VoKino's are movie posters).
 */
internal fun cardSize(aspect: Int?): DpSize {
    val ratio = aspectRatioOf(aspect)
    return if (ratio >= 1f) DpSize((CARD_H_DP * ratio).dp, CARD_H_DP.dp) else DpSize((POSTER_H_DP * ratio).dp, POSTER_H_DP.dp)
}

const val WATCH_NEXT_KEY = "watchnext"
const val SECOND_ROW_OFF = "off"
const val SECOND_ROW_AUTO = "auto"

private const val MAX_ITEMS = 20

/** The channels as last read, so coming back home doesn't flash the placeholders again. */
@Volatile
private var lastChannels: List<TvChannel>? = null

/**
 * Reloads when the TV provider changes and whenever [refreshKey] changes (e.g. coming back home).
 * Null until the first load, so the home screen can hold the rows' places meanwhile.
 */
@Composable
fun rememberTvChannels(refreshKey: Any): State<List<TvChannel>?> {
    val context = LocalContext.current
    return produceState<List<TvChannel>?>(lastChannels, refreshKey) {
        suspend fun load() { value = withContext(Dispatchers.IO) { queryTvChannels(context) }.also { lastChannels = it } }
        load()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                launch { load() }
            }
        }
        val resolver = context.contentResolver
        runCatching {
            resolver.registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, observer)
            resolver.registerContentObserver(TvContract.PreviewPrograms.CONTENT_URI, true, observer)
            resolver.registerContentObserver(TvContract.Channels.CONTENT_URI, true, observer)
        }
        awaitDispose { resolver.unregisterContentObserver(observer) }
    }
}

/**
 * The channel rows under the tiles. The setting is [SECOND_ROW_AUTO] (one row, picked),
 * [SECOND_ROW_OFF], or channel keys one per line, shown in that order.
 */
fun pickChannelRows(channels: List<TvChannel>, setting: String): List<TvChannel> = when (setting) {
    SECOND_ROW_OFF -> emptyList()
    SECOND_ROW_AUTO -> listOfNotNull(pickSecondRow(channels, setting))
    else -> channelRowKeys(setting).mapNotNull { key -> channels.firstOrNull { it.key == key } }.filter { it.items.isNotEmpty() }
}

/** The channels chosen by hand; empty for auto and off. */
fun channelRowKeys(setting: String): List<String> =
    if (setting == SECOND_ROW_OFF || setting == SECOND_ROW_AUTO) emptyList() else setting.split('\n').filter { it.isNotEmpty() }

/** The rows on the home screen by key: those chosen by hand, or the one auto picks from [channels]. */
fun shownChannelRows(setting: String, channels: List<TvChannel>): List<String> =
    if (setting == SECOND_ROW_AUTO) listOfNotNull(pickSecondRow(channels, setting)?.key) else channelRowKeys(setting)

/**
 * [setting] with [key]'s row added at the end, or taken out; no rows left means off. Auto turns
 * into the row it showed, so switching another on keeps that one.
 */
fun toggleChannelRow(setting: String, key: String, channels: List<TvChannel> = emptyList()): String {
    val keys = shownChannelRows(setting, channels)
    val next = if (key in keys) keys - key else keys + key
    return if (next.isEmpty()) SECOND_ROW_OFF else next.joinToString("\n")
}

/**
 * [setting] with [key]'s row swapped with its neighbour on screen, [step] rows away (−1 up, 1 down).
 * [shown] are the rows on screen, in order: chosen rows that are empty right now keep their places.
 * Auto has a single row, so nothing moves.
 */
fun moveChannelRow(setting: String, key: String, step: Int, shown: List<String>): String {
    val keys = channelRowKeys(setting).toMutableList()
    val target = shown.getOrNull(shown.indexOf(key) + step) ?: return setting
    val i = keys.indexOf(key)
    val j = keys.indexOf(target)
    if (i < 0 || j < 0) return setting
    keys[i] = target
    keys[j] = key
    return keys.joinToString("\n")
}

/** [setting] without [key]'s row; the one automatic row taken away means no rows. */
fun hideChannelRow(setting: String, key: String): String =
    if (setting == SECOND_ROW_AUTO) SECOND_ROW_OFF else toggleChannelRow(setting, key)

/** The channel chosen for the second row; "auto" prefers SmartTube subscriptions. */
fun pickSecondRow(channels: List<TvChannel>, setting: String): TvChannel? = when (setting) {
    SECOND_ROW_OFF -> null
    SECOND_ROW_AUTO -> channels.firstOrNull { it.key == "org.smarttube.stable|Подписки" && it.items.isNotEmpty() }
        ?: channels.firstOrNull { it.key == WATCH_NEXT_KEY }
        ?: channels.firstOrNull { it.items.isNotEmpty() }
    else -> channels.firstOrNull { it.key == setting }
}?.takeIf { it.items.isNotEmpty() }

fun queryTvChannels(context: Context): List<TvChannel> {
    val resolver = context.contentResolver
    val result = mutableListOf<TvChannel>()

    val watchNext = mutableListOf<Pair<Long, TvItem>>()
    runCatching {
        resolver.query(
            TvContract.WatchNextPrograms.CONTENT_URI,
            arrayOf(
                TvContract.WatchNextPrograms._ID,
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                watchNext += c.getLong(6) to TvItem(
                    key = "wn:${c.getLong(0)}",
                    pkg = c.getString(1).orEmpty(),
                    title = c.getString(2).orEmpty(),
                    subtitle = c.getString(3).orEmpty(),
                    poster = c.getString(4)?.let(Uri::parse),
                    intentUri = c.getString(5),
                    aspect = c.intOrNull(7),
                    progress = progressOf(c.longOrNull(8), c.longOrNull(9)),
                )
            }
        }
    }
    val watchNextItems = watchNext.sortedByDescending { it.first }.map { it.second }.clean()
    if (watchNextItems.isNotEmpty()) result += TvChannel(WATCH_NEXT_KEY, tr(R.string.continue_watching), watchNextItems)

    class Found(val id: Long, val pkg: String, val name: String, val appLink: String?)
    val channels = mutableListOf<Found>()
    // TvProvider rejects selection/sort arguments from non-system callers, so filter here.
    runCatching {
        resolver.query(
            TvContract.Channels.CONTENT_URI,
            arrayOf(
                TvContract.Channels._ID,
                TvContract.Channels.COLUMN_PACKAGE_NAME,
                TvContract.Channels.COLUMN_DISPLAY_NAME,
                TvContract.Channels.COLUMN_TYPE,
                TvContract.Channels.COLUMN_APP_LINK_INTENT_URI,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(3) == TvContract.Channels.TYPE_PREVIEW) {
                    channels += Found(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(4)?.takeIf { it.isNotBlank() })
                }
            }
        }
    }.onFailure { Log.w(TAG, "Channels query failed", it) }
    val pm = context.packageManager
    for (channel in channels) {
        val items = mutableListOf<TvItem>()
        runCatching {
            resolver.query(
                TvContract.buildPreviewProgramsUriForChannel(channel.id),
                arrayOf(
                    TvContract.PreviewPrograms._ID,
                    TvContract.PreviewPrograms.COLUMN_TITLE,
                    TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
                    TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
                    TvContract.PreviewPrograms.COLUMN_INTENT_URI,
                    TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
                    TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                    TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    items += TvItem(
                        key = "pp:${c.getLong(0)}",
                        pkg = channel.pkg,
                        title = c.getString(1).orEmpty(),
                        subtitle = c.getString(2).orEmpty(),
                        poster = c.getString(3)?.let(Uri::parse),
                        intentUri = c.getString(4),
                        aspect = c.intOrNull(5),
                        progress = progressOf(c.longOrNull(6), c.longOrNull(7)),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Programs of channel ${channel.id} failed", it) }
        val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(channel.pkg, 0)) }.getOrNull()?.toString()
        result += TvChannel("${channel.pkg}|${channel.name}", channel.name, items.clean(), channel.pkg, app, channel.appLink)
    }
    return result
}

private fun android.database.Cursor.intOrNull(i: Int) = if (isNull(i)) null else getInt(i)
private fun android.database.Cursor.longOrNull(i: Int) = if (isNull(i)) null else getLong(i)

/** Watched so far as a share of the whole; null unless the app gives both, and not for barely started. */
internal fun progressOf(position: Long?, duration: Long?): Float? {
    if (position == null || duration == null || position <= 0 || duration <= 0) return null
    return (position.toFloat() / duration).coerceIn(0f, 1f).takeIf { it >= 0.02f }
}

private const val TAG = "TvChannels"

/**
 * For the adb hook: per channel, which of the provider's optional fields its programs fill in
 * (poster shape, watch progress, logos, preview videos) and whether the channel links to its app.
 */
internal fun describeChannelFields(context: Context): String {
    val resolver = context.contentResolver
    val out = StringBuilder()
    fun summary(uri: Uri, ratio: String, position: String, duration: String, extra: Array<String>): String = runCatching {
        resolver.query(uri, arrayOf(ratio, position, duration, *extra), null, null, null)?.use { c ->
            val ratios = sortedSetOf<String>()
            var pos = 0
            var dur = 0
            val filled = IntArray(extra.size)
            while (c.moveToNext()) {
                ratios += if (c.isNull(0)) "-" else c.getInt(0).toString()
                if (!c.isNull(1) && c.getLong(1) > 0) pos++
                if (!c.isNull(2) && c.getLong(2) > 0) dur++
                extra.indices.forEach { if (!c.isNull(3 + it) && c.getString(3 + it).isNotEmpty()) filled[it]++ }
            }
            "n=${c.count} ratio=$ratios pos=$pos dur=$dur " + extra.indices.joinToString(" ") { "${extra[it].removePrefix("internal_").removeSuffix("_uri")}=${filled[it]}" }
        } ?: "no access"
    }.getOrElse { "error ${it.message}" }
    out.append("watchnext: ").append(
        summary(
            TvContract.WatchNextPrograms.CONTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            arrayOf(TvContract.WatchNextPrograms.COLUMN_LOGO_URI, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE),
        ),
    ).append('\n')
    runCatching {
        resolver.query(
            TvContract.Channels.CONTENT_URI,
            arrayOf(
                TvContract.Channels._ID,
                TvContract.Channels.COLUMN_PACKAGE_NAME,
                TvContract.Channels.COLUMN_DISPLAY_NAME,
                TvContract.Channels.COLUMN_TYPE,
                TvContract.Channels.COLUMN_APP_LINK_INTENT_URI,
                TvContract.Channels.COLUMN_BROWSABLE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(3) != TvContract.Channels.TYPE_PREVIEW) continue
                val id = c.getLong(0)
                val logo = runCatching { resolver.openInputStream(TvContract.buildChannelLogoUri(id))?.use { it.available() >= 0 } }.getOrNull() == true
                out.append("${c.getString(1)}|${c.getString(2)} link=${!c.getString(4).isNullOrEmpty()} browsable=${c.getInt(5)} logo=$logo: ")
                out.append(
                    summary(
                        TvContract.buildPreviewProgramsUriForChannel(id),
                        TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
                        TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                        TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
                        arrayOf(TvContract.PreviewPrograms.COLUMN_LOGO_URI, TvContract.PreviewPrograms.COLUMN_PREVIEW_VIDEO_URI, TvContract.PreviewPrograms.COLUMN_TYPE),
                    ),
                ).append('\n')
            }
        }
    }.onFailure { out.append("channels: error ${it.message}") }
    return out.toString()
}

private fun List<TvItem>.clean() = filter { it.title.isNotBlank() }.distinctBy { it.intentUri ?: it.key }.take(MAX_ITEMS)

/** The channel's own place in its app (its app link), or the app itself. */
fun Context.openChannelApp(channel: TvChannel) {
    val intent = channel.appLink?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }
    if (intent != null) {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { launchPackage(channel.pkg) }
    } else {
        launchPackage(channel.pkg)
    }
}

fun Context.openTvItem(item: TvItem) {
    val intent = item.intentUri?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }
    if (intent != null) {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { launchPackage(item.pkg) }
    } else {
        launchPackage(item.pkg)
    }
}

/**
 * XGIMI's XTurbo force-stops background apps, which also cancels SmartTube's periodic channel job.
 * Poking its (exported) update receiver directly reschedules the sync; throttled to every 30 minutes.
 */
fun Context.requestChannelRefresh() {
    val prefs = getSharedPreferences("tiles", Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()
    if (now - prefs.getLong(KEY_LAST_REFRESH, 0) < REFRESH_INTERVAL_MS) return
    prefs.edit().putLong(KEY_LAST_REFRESH, now).apply()
    runCatching {
        sendBroadcast(
            Intent("com.home.tiles.REFRESH_CHANNELS").setComponent(
                ComponentName("org.smarttube.stable", "com.liskovsoft.leanbackassistant.channels.UpdateChannelsReceiver"),
            ),
        )
    }
}

private const val KEY_LAST_REFRESH = "channelsRefreshedAt"
private const val REFRESH_INTERVAL_MS = 30L * 60 * 1000

// ---- Posters ----

/**
 * Posters kept decoded, by size and dropping the least recently seen: with several channel rows
 * an unbounded cache held a hundred of them, and on this 1.3 GB device the garbage collector's
 * pauses showed as stutter while moving along the home row.
 */
private val posterCache = object : LruCache<Uri, ImageBitmap>(POSTER_CACHE_BYTES) {
    override fun sizeOf(key: Uri, value: ImageBitmap) = value.asAndroidBitmap().allocationByteCount
}

/** Gives memory back when the system runs short, like AppRepository.trimArt. */
fun trimPosters(level: Int) {
    when {
        level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
            posterCache.evictAll()
            thumbCache.evictAll()
            appIcons.evictAll()
        }
        level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> posterCache.trimToSize(POSTER_CACHE_BYTES / 2)
    }
}

private const val POSTER_CACHE_BYTES = 12 * 1024 * 1024

/** A wide card's height, and a standing poster's (see [cardSize]); posters are decoded to just cover their card. */
private const val CARD_H_DP = 117f
private const val POSTER_H_DP = 176f

/**
 * Each poster's liveliest colour, worked out once as it is decoded: the light around its card and
 * the home screen's tint while it is selected, as an app tile's colour does for apps.
 */
private val posterColors = ConcurrentHashMap<Uri, Color>()

/** Small copies for the rows list in Settings, apart from the cards' (a card can't use a tiny one). */
private val thumbCache = object : LruCache<Uri, ImageBitmap>(THUMB_CACHE_BYTES) {
    override fun sizeOf(key: Uri, value: ImageBitmap) = value.asAndroidBitmap().allocationByteCount
}
private const val THUMB_CACHE_BYTES = 2 * 1024 * 1024
/** Opening the rows list fetches a few posters for each channel: a handful at a time. */
private val thumbFetches = Semaphore(4)

private suspend fun loadPoster(context: Context, uri: Uri, size: DpSize): ImageBitmap? = withContext(Dispatchers.IO) {
    posterCache.get(uri)?.let { return@withContext it }
    decodePoster(context, uri, size)?.also {
        posterCache.put(uri, it)
        if (posterColors.size > 500) posterColors.clear()
        posterColors[uri] = glowOf(it.asAndroidBitmap())
        // Sent to the GPU now, in the background, not when first drawn (that stalled a frame).
        it.prepareToDraw()
    }
}

/** A poster for the rows list: the card's copy when it has one, else a small one of its own. */
private suspend fun loadThumb(context: Context, uri: Uri, size: DpSize): ImageBitmap? = withContext(Dispatchers.IO) {
    posterCache.get(uri)?.let { return@withContext it }
    thumbCache.get(uri)?.let { return@withContext it }
    thumbFetches.withPermit { decodePoster(context, uri, size) }?.also { thumbCache.put(uri, it) }
}

/**
 * Some apps (VoKino) publish posters as plain http:// links, hence usesCleartextTraffic in the
 * manifest: without it Android refuses them and those cards stay grey.
 * Russian services (VoKino, Kinopoisk) refuse foreign addresses and send an error page instead,
 * so behind a VPN the cards stayed grey: a poster that doesn't come through the default network
 * is fetched again straight over Wi-Fi, past the VPN (unless the VPN app forbids that).
 */
private fun decodePoster(context: Context, uri: Uri, size: DpSize): ImageBitmap? =
    runCatching {
        val bytes = when (uri.scheme) {
            "http", "https" -> {
                val url = URL(uri.toString())
                runCatching { download(url, null) }.getOrNull()
                    ?: directNetwork(context)?.let { network -> runCatching { download(url, network) }.getOrNull() }
            }
            else -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: return@runCatching null
        // Posters can be full-size artwork: decode them straight to the size that covers a card
        // (the card crops them), at 2 bytes a pixel, to spare this device's small RAM.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return@runCatching null
        val density = context.resources.displayMetrics.density
        val scale = maxOf(size.width.value * density / w, size.height.value * density / h).coerceAtMost(1f)
        val targetW = kotlin.math.ceil(w * scale).toInt().coerceAtLeast(1)
        var sample = 1
        while (w / (sample * 2) >= targetW) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
            val sampledW = w / sample
            if (sampledW > targetW) {
                inScaled = true
                inDensity = sampledW
                inTargetDensity = targetW
            }
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }.getOrNull()

/** The image at [url] over [network] (the default one when null); null for an error or a web page. */
private fun download(url: URL, network: Network?): ByteArray? {
    val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
    return try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        if (connection.responseCode != HttpURLConnection.HTTP_OK || connection.contentType?.startsWith("text/") == true) null
        else connection.inputStream.use { it.readBytes() }
    } finally {
        connection.disconnect()
    }
}

/** A network to the internet that isn't a VPN (the Wi-Fi), or null. */
private fun directNetwork(context: Context): Network? {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    @Suppress("DEPRECATION")
    return cm.allNetworks.firstOrNull { network ->
        cm.getNetworkCapabilities(network)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } == true
    }
}

// ---- UI ----

/** App icons beside the rows' names and in the rows list, by package. */
private val appIcons = object : LruCache<String, ImageBitmap>(APP_ICON_CACHE_BYTES) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().allocationByteCount
}
private const val APP_ICON_CACHE_BYTES = 1024 * 1024
// Made once at the largest size shown (the "Open" card's), drawn smaller elsewhere.
private val AppIconSide = 44.dp

/** [pkg]'s icon, [size] square with rounded corners; an empty place while it loads or without one. */
@Composable
internal fun AppIcon(pkg: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { AppIconSide.roundToPx() }
    val icon by produceState(appIcons.get(pkg), pkg) {
        if (value != null || pkg.isEmpty()) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(pkg)
                val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, px, px)
                drawable.draw(android.graphics.Canvas(bitmap))
                bitmap.asImageBitmap().also { appIcons.put(pkg, it) }
            }.getOrNull()
        }
    }
    val image = icon
    if (image == null) Spacer(modifier.size(size)) else Image(image, null, modifier.size(size).clip(RoundedCornerShape(size * 0.22f)))
}

/**
 * A channel's row: its app's icon and its name, then its cards, shaped as the app declares
 * (VoKino's stand as posters), and a last card that opens the app there. The selected card's
 * title shows under it, the art alone on the others. Holding OK on a card opens [onMenu]. While
 * [move] holds this row, ▲ ▼ move the whole row, OK finishes and Back cancels; [position] is its
 * place among the rows, so the page follows it as it moves. [onTint] hears the selected card's
 * colour, for the background's tint.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChannelRow(
    channel: TvChannel,
    startPad: Dp,
    position: Int = 0,
    move: MoveControl? = null,
    modifier: Modifier = Modifier,
    /** Takes the selection into the row (to the card last selected in it). */
    focus: FocusRequester? = null,
    onTint: (Color?) -> Unit = {},
    onMenu: (TvItem) -> Unit = {},
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var focusedKey by remember { mutableStateOf<String?>(null) }
    // Each card's light in its poster's colour, behind the whole row like the apps row's.
    val glows = rememberTileGlows()
    val moving = move != null && move.key == channel.key
    val card = remember(channel.aspect) { cardSize(channel.aspect) }
    // The page settles a moved row's cards where it settles a focused one (see rowKeyline).
    val bring = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    LaunchedEffect(moving, position) {
        if (!moving) return@LaunchedEffect
        withFrameNanos {}
        val cardTop = with(density) { (ROW_NAME_HEIGHT + CARD_TOP).toPx() }
        runCatching { bring.bringIntoView(Rect(0f, cardTop, 1f, cardTop + 1f)) }
    }
    Column(
        // The page follows the row's new place, not where a slide (in [modifier]) is drawing it.
        Modifier
            .bringIntoViewRequester(bring)
            .then(modifier)
            .fillMaxWidth()
            .then(if (moving) Modifier.moveKeys(channel.key, move!!, vertical = true) else Modifier),
    ) {
        Row(Modifier.padding(start = startPad).height(ROW_NAME_HEIGHT - ROW_NAME_GAP), verticalAlignment = Alignment.CenterVertically) {
            // Watch Next gathers several apps: no icon.
            if (channel.pkg.isNotEmpty()) {
                AppIcon(channel.pkg, 26.dp)
                Spacer(Modifier.width(10.dp))
            }
            T(
                if (moving) tr(R.string.move_row_hint, channel.name) else channel.name,
                20.sp,
                color = if (moving) Colors.Text else Colors.TextDim,
                weight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(ROW_NAME_GAP))
        LazyRow(
            state = listState,
            // Coming back to the row returns to the card last selected in it, as on Google TV.
            modifier = Modifier
                .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                .focusRestorer()
                .drawBehind {
                val h = card.height.toPx()
                for (info in listState.layoutInfo.visibleItemsInfo) {
                    val glow = glows[info.key] ?: continue
                    // Item offsets count from the content's start: add the start padding back.
                    val x = info.offset - listState.layoutInfo.viewportStartOffset
                    val w = info.size.toFloat()
                    val center = Offset(x + w / 2f, CARD_TOP.toPx() + h / 2f)
                    drawFocusGlow(glow.color.value, glow.level.value * 0.7f, center, w * FOCUS_SCALE / 2f, h * FOCUS_SCALE / 2f, FocusGlowSpread.toPx())
                }
            },
            contentPadding = PaddingValues(start = startPad, end = 60.dp, top = CARD_TOP),
            // Room for a selected card, grown by FOCUS_SCALE, to stay clear of its neighbours.
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
        ) {
            items(channel.items, key = { it.key }) { item ->
                // Derived, so a move recomposes the card it leaves and the one it reaches, not all.
                val focused by remember(item.key) { derivedStateOf { focusedKey == item.key } }
                val poster by rememberPoster(item, card)
                val color = poster.image?.let { item.poster?.let(posterColors::get) }
                RegisterTileGlow(glows, item.key, focused, color ?: Colors.Text)
                LaunchedEffect(focused, color) { if (focused) onTint(color) }
                Column {
                    TvCard(
                        item, card, poster,
                        onFocus = { on -> if (on) focusedKey = item.key else if (focusedKey == item.key) focusedKey = null },
                        onLongClick = { onMenu(item) },
                    ) { context.openTvItem(item) }
                    // The title under the selected card, in the gap to the next row.
                    Box(Modifier.width(card.width).height(TITLE_ROOM)) {
                        if (focused) CardTitle(item, card)
                    }
                }
            }
            // Watch Next gathers several apps: no app to open.
            if (channel.pkg.isNotEmpty()) {
                item(key = APP_CARD_KEY) {
                    val focused by remember { derivedStateOf { focusedKey == APP_CARD_KEY } }
                    RegisterTileGlow(glows, APP_CARD_KEY, focused, Colors.Text)
                    LaunchedEffect(focused) { if (focused) onTint(null) }
                    Column {
                        AppLinkCard(channel, card) { on -> if (on) focusedKey = APP_CARD_KEY else if (focusedKey == APP_CARD_KEY) focusedKey = null }
                        Spacer(Modifier.height(TITLE_ROOM))
                    }
                }
            }
        }
    }
}

private const val APP_CARD_KEY = "__app__"
private val CARD_TOP = 8.dp
private val CARD_GAP = 28.dp
// A row's name line and the gap under it, above the cards.
private val ROW_NAME_HEIGHT = 38.dp
private val ROW_NAME_GAP = 10.dp
// Under the cards: the selected card's title.
private val TITLE_ROOM = 36.dp
// The title waits for the selection to settle, so holding ▶ along a row doesn't flash one under every card.
private const val TITLE_DELAY_MS = 120L

/** A poster as it loads: the picture, and whether loading is over (no picture then means it has none). */
private class PosterState(val image: ImageBitmap?, val done: Boolean)

@Composable
private fun rememberPoster(item: TvItem, size: DpSize): State<PosterState> {
    val context = LocalContext.current
    val cached = item.poster?.let { posterCache.get(it) }
    return produceState(PosterState(cached, cached != null || item.poster == null), item.poster) {
        if (value.done) return@produceState
        value = PosterState(item.poster?.let { loadPoster(context, it, size) }, true)
    }
}

/** The selected card's title (and episode), fading in under it once the selection rests there. */
@Composable
private fun CardTitle(item: TvItem, card: DpSize) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(TITLE_DELAY_MS)
        alpha.animateTo(1f, motion(150))
    }
    val dim = Colors.TextDim
    val text = remember(item, dim) {
        buildAnnotatedString {
            append(item.title)
            if (item.subtitle.isNotEmpty()) withStyle(SpanStyle(color = dim)) { append(" · ${item.subtitle}") }
        }
    }
    BasicText(
        text,
        Modifier
            // Clear of the card grown by FOCUS_SCALE.
            .padding(top = card.height * ((FOCUS_SCALE - 1f) / 2f) + 6.dp)
            // May run past the card, under its neighbours (they show none).
            .wrapContentWidth(Alignment.Start, unbounded = true)
            .widthIn(max = maxOf(card.width * 2.2f, 300.dp))
            .graphicsLayer { this.alpha = alpha.value },
        style = TextStyle(color = Colors.Text, fontSize = 16.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A card's picture alone, for the OK-hold menu. */
@Composable
internal fun TvItemArt(item: TvItem) {
    val poster by rememberPoster(item, cardSize(item.aspect))
    Box(Modifier.fillMaxSize().background(Color(0xFF3A3A3A))) {
        poster.image?.let { Image(it, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** A channel's first few posters, small and in its cards' shape, for the rows list in Settings. */
@Composable
internal fun ChannelThumbs(channel: TvChannel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ratio = aspectRatioOf(channel.aspect)
    val size = DpSize(THUMB_H * ratio, THUMB_H)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        channel.items.take(3).forEach { item ->
            val thumb by produceState(item.poster?.let { posterCache.get(it) ?: thumbCache.get(it) }, item.poster) {
                if (value == null) item.poster?.let { value = loadThumb(context, it, size) }
            }
            Box(Modifier.size(size).clip(RoundedCornerShape(4.dp)).background(Color(0x33FFFFFF))) {
                thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
        }
    }
}

private val THUMB_H = 26.dp

/** A channel row's place while the channels are being read: a title bar and a run of cards. */
@Composable
fun ChannelRowSkeleton(startPad: Dp) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.padding(start = startPad).size(240.dp, 20.dp).skeleton(RoundedCornerShape(6.dp)))
        Spacer(Modifier.height(ROW_NAME_GAP))
        Row(
            Modifier.padding(start = startPad, top = CARD_TOP).height(CARD_H_DP.dp + TITLE_ROOM),
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
        ) {
            repeat(6) { Box(Modifier.size(208.dp, CARD_H_DP.dp).skeleton(RoundedCornerShape(12.dp))) }
        }
    }
}

/**
 * A card's frame: selectable, grown a little with an outline when selected like the tiles (no
 * shadows: they overloaded the GPU; the row draws the light behind it). OK opens, holding OK
 * opens the row's menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardFrame(
    size: DpSize,
    background: Color,
    onFocus: (Boolean) -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val press = remember { DpadPress() }
    val click = {
        Sounds.activate()
        onClick()
    }
    val scale by animateFloatAsState(if (focused) FOCUS_SCALE else 1f, motion(150), label = "card")
    val border by animateFloatAsState(if (focused) 1f else 0f, motion(150), label = "border")
    val screenShown = LocalScreenShown.current
    Box(
        Modifier
            .size(size)
            // Not on the screen kept ready behind the shown one.
            .focusProperties { canFocus = screenShown }
            .onFocusChanged {
                focused = it.isFocused
                onFocus(it.isFocused)
                if (it.isFocused) Sounds.navigate()
            }
            .dpadClick(press, onPress = {}, onClick = click, onLongClick = onLongClick)
            .combinedClickable(remember { MutableInteractionSource() }, null, onLongClick = onLongClick, onClick = click)
            // Grown inside the focus target, so paging to the card sees its real place (see Tile).
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusBorder(CARD_CORNER) { border }
            .clip(RoundedCornerShape(CARD_CORNER))
            .background(background),
        content = content,
    )
}

private val CARD_CORNER = 12.dp

/** A program's card: its art alone (the title shows under it when selected), and how far it's been watched. */
@Composable
private fun TvCard(item: TvItem, size: DpSize, poster: PosterState, onFocus: (Boolean) -> Unit, onLongClick: () -> Unit, onClick: () -> Unit) {
    CardFrame(size, Color(0xFF3A3A3A), onFocus, onLongClick, onClick) {
        val image = poster.image
        when {
            image != null -> {
                val cardRatio = size.width / size.height
                val imageRatio = image.width.toFloat() / image.height
                if (imageRatio < cardRatio * 0.75f || imageRatio > cardRatio * 1.33f) {
                    // Another shape than its row's (Watch Next mixes apps): whole, over a dim copy
                    // of itself filling the card, rather than cut down to a strip.
                    Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.35f)
                    Image(image, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Image(image, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            // No picture at all: its title where the picture would be.
            poster.done -> T(
                item.title,
                16.sp,
                Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
                color = Color.White,
                align = TextAlign.Center,
                maxLines = 3,
            )
        }
        item.progress?.let { watched ->
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color(0x80000000), CircleShape),
            ) {
                Box(Modifier.fillMaxWidth(watched).fillMaxHeight().background(Color.White, CircleShape))
            }
        }
    }
}

/** The row's last card: the app's icon and name, opening the app where this channel is. */
@Composable
private fun AppLinkCard(channel: TvChannel, card: DpSize, onFocus: (Boolean) -> Unit) {
    val context = LocalContext.current
    // As tall as the row's cards; as wide as a poster, or a little wider than tall beside wide cards.
    val size = DpSize(if (card.width < card.height) card.width else card.height * 1.3f, card.height)
    CardFrame(size, Colors.Button, onFocus, onLongClick = {}, onClick = { context.openChannelApp(channel) }) {
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(channel.pkg, 40.dp)
            Spacer(Modifier.height(8.dp))
            T(tr(R.string.open_app), 14.sp, color = Colors.TextDim)
            T(channel.app ?: channel.name, 16.sp, color = Colors.Text, align = TextAlign.Center, maxLines = 2)
        }
    }
}
