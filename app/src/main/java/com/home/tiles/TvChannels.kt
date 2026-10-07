package com.home.tiles

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
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
)

class TvChannel(val key: String, val name: String, val items: List<TvItem>)

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

/** [setting] with [key]'s row added at the end, or taken out; no rows left means off. */
fun toggleChannelRow(setting: String, key: String): String {
    val keys = channelRowKeys(setting)
    val next = if (key in keys) keys - key else keys + key
    return if (next.isEmpty()) SECOND_ROW_OFF else next.joinToString("\n")
}

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
                )
            }
        }
    }
    val watchNextItems = watchNext.sortedByDescending { it.first }.map { it.second }.clean()
    if (watchNextItems.isNotEmpty()) result += TvChannel(WATCH_NEXT_KEY, tr(R.string.continue_watching), watchNextItems)

    val channels = mutableListOf<Triple<Long, String, String>>() // id, package, name
    // TvProvider rejects selection/sort arguments from non-system callers, so filter here.
    runCatching {
        resolver.query(
            TvContract.Channels.CONTENT_URI,
            arrayOf(
                TvContract.Channels._ID,
                TvContract.Channels.COLUMN_PACKAGE_NAME,
                TvContract.Channels.COLUMN_DISPLAY_NAME,
                TvContract.Channels.COLUMN_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(3) == TvContract.Channels.TYPE_PREVIEW) {
                    channels += Triple(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())
                }
            }
        }
    }.onFailure { Log.w(TAG, "Channels query failed", it) }
    for ((id, pkg, name) in channels) {
        val items = mutableListOf<TvItem>()
        runCatching {
            resolver.query(
                TvContract.buildPreviewProgramsUriForChannel(id),
                arrayOf(
                    TvContract.PreviewPrograms._ID,
                    TvContract.PreviewPrograms.COLUMN_TITLE,
                    TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
                    TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
                    TvContract.PreviewPrograms.COLUMN_INTENT_URI,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    items += TvItem(
                        key = "pp:${c.getLong(0)}",
                        pkg = pkg,
                        title = c.getString(1).orEmpty(),
                        subtitle = c.getString(2).orEmpty(),
                        poster = c.getString(3)?.let(Uri::parse),
                        intentUri = c.getString(4),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Programs of channel $id failed", it) }
        val app = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)) }
            .getOrNull()?.toString()
        val label = if (app == null || app == name) name else "$name · $app"
        result += TvChannel("$pkg|$name", label, items.clean())
    }
    return result
}

private const val TAG = "TvChannels"

private fun List<TvItem>.clean() = filter { it.title.isNotBlank() }.distinctBy { it.intentUri ?: it.key }.take(MAX_ITEMS)

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

private const val POSTER_CACHE_BYTES = 12 * 1024 * 1024

/** A card's size ([TvCard]); posters are decoded to just cover it. */
private const val CARD_W_DP = 208f
private const val CARD_H_DP = 117f

/**
 * Some apps (VoKino) publish posters as plain http:// links, hence usesCleartextTraffic in the
 * manifest: without it Android refuses them and those cards stay grey.
 * Russian services (VoKino, Kinopoisk) refuse foreign addresses and send an error page instead,
 * so behind a VPN the cards stayed grey: a poster that doesn't come through the default network
 * is fetched again straight over Wi-Fi, past the VPN (unless the VPN app forbids that).
 */
private suspend fun loadPoster(context: Context, uri: Uri): ImageBitmap? = withContext(Dispatchers.IO) {
    posterCache.get(uri)?.let { return@withContext it }
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
        val scale = maxOf(CARD_W_DP * density / w, CARD_H_DP * density / h).coerceAtMost(1f)
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
    }.getOrNull()?.also { posterCache.put(uri, it) }
}

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

@Composable
fun ChannelRow(channel: TvChannel, startPad: Dp) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        T(channel.name, 20.sp, Modifier.padding(start = startPad), color = Colors.TextDim)
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(start = startPad, end = 60.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(channel.items, key = { it.key }) { item ->
                TvCard(item) { context.openTvItem(item) }
            }
        }
    }
}

/** A channel row's place while the channels are being read: a title bar and a run of cards. */
@Composable
fun ChannelRowSkeleton(startPad: Dp) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.padding(start = startPad).size(240.dp, 20.dp).skeleton(RoundedCornerShape(6.dp)))
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.padding(start = startPad, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            repeat(6) { Box(Modifier.size(208.dp, 117.dp).skeleton(RoundedCornerShape(12.dp))) }
        }
    }
}

@Composable
private fun TvCard(item: TvItem, onClick: () -> Unit) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    // Like the tiles: the selected card grows over its neighbours with a soft light around it
    // (neutral: posters are many-coloured), no frame and no shadows (they overloaded the GPU).
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, tween(150), label = "card")
    val glowStrength by animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "glow")
    val poster by produceState<ImageBitmap?>(item.poster?.let { posterCache.get(it) }, item.poster) {
        item.poster?.let { value = loadPoster(context, it) }
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .zIndex(if (focused) 1f else 0f)
            .size(CARD_W_DP.dp, CARD_H_DP.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusGlow(Colors.Text, spread = 14.dp) { glowStrength * 0.5f }
            .clip(shape)
            .background(Color(0xFF3A3A3A))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.navigate()
            }
            .clickable(remember { MutableInteractionSource() }, null) {
                Sounds.activate()
                onClick()
            },
    ) {
        poster?.let { Image(it, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        // Title over a dark fade so it reads on any artwork.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Column {
                T(item.title, 15.sp, color = Color.White)
                if (item.subtitle.isNotEmpty()) T(item.subtitle, 12.sp, color = Color(0xFFCCCCCC))
            }
        }
    }
}
