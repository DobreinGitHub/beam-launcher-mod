package com.home.tiles

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/*
 * Updates from this mod's GitHub releases. Ported from tonisaf's Beam 1.0.1 (MIT,
 * github.com/tonisaf/beam-launcher, Updates.kt and UpdatePolicy.kt), for this repository and its
 * "1.0-ru-v2" version names. Public releases only; nothing installs silently: the APK must match
 * GitHub's SHA-256, this package, this signing key and a higher version code, and Android asks
 * before installing it.
 */

internal data class BeamRelease(val tag: String, val url: String, val size: Long, val sha256: String, val notes: String)

internal object Updates {
    private const val REPO = "DobreinGitHub/beam-launcher-mod"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val DAY = 24 * 60 * 60 * 1000L
    private const val MAX_APK = 80 * 1024 * 1024L

    var busy by mutableStateOf(false); private set
    /** The newer release found, or null (none, or not checked yet). */
    var release by mutableStateOf<BeamRelease?>(null); private set
    var error by mutableStateOf(false); private set
    /** Download progress 0..100, -1 when not downloading. */
    var progress by mutableStateOf(-1); private set
    /** The downloaded, verified APK, ready to hand to Android's installer. */
    var ready by mutableStateOf<File?>(null); private set
    /** When the last check succeeded (ms since epoch), 0 for never. */
    var checkedAt by mutableLongStateOf(0L); private set
    /**
     * Beam may not install apps, and this firmware has no screen to allow it: it takes one adb
     * command (in the README; the install script runs it).
     */
    var needsGrant by mutableStateOf(false); private set

    /** Whether Android lets Beam hand an APK to its installer, or at least shows where to allow it. */
    private fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls() || grantScreen(context).resolveActivity(context.packageManager) != null

    private fun grantScreen(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    fun autoEnabled(context: Context) = prefs(context).getBoolean("auto", true)
    fun setAuto(context: Context, value: Boolean) = prefs(context).edit().putBoolean("auto", value).apply()
    fun version(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    fun init(context: Context) {
        if (checkedAt == 0L) checkedAt = prefs(context).getLong("checkedOk", 0L)
    }

    // The process's own scope: a check or download carries on when the settings page closes.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** OK on the update button: check, or download and then install what was found. */
    fun act(context: Context, closePage: () -> Unit) {
        val app = context.applicationContext
        scope.launch {
            when {
                ready != null -> {
                    // The page is drawn over every window, the installer's too: it goes first.
                    closePage()
                    install(app)
                }
                release != null -> {
                    // Not worth downloading what can't be installed.
                    needsGrant = !canInstall(app)
                    if (needsGrant) return@launch
                    download(app)
                    if (ready != null) {
                        closePage()
                        install(app)
                    }
                }
                else -> check(app)
            }
        }
    }

    /** The daily check, when the home screen comes up. */
    fun checkDaily(context: Context) {
        val app = context.applicationContext
        scope.launch { check(app, automatic = true) }
    }

    /**
     * Asks GitHub for the latest release. [automatic]: at most once a day, and not when switched
     * off; after a failure it tries again in an hour.
     */
    suspend fun check(context: Context, automatic: Boolean = false): Boolean {
        if (busy) return false
        init(context)
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (automatic && (!autoEnabled(context) || now - prefs.getLong("triedAt", 0) in 0 until DAY)) return false
        busy = true; error = false
        try {
            val result = runInterruptible(Dispatchers.IO) {
                val json = JSONObject(read(API, 1024 * 1024L).toString(Charsets.UTF_8))
                if (json.optBoolean("draft") || json.optBoolean("prerelease") ||
                    !newerRelease(json.getString("tag_name"), version(context))
                ) {
                    null
                } else {
                    val assets = json.getJSONArray("assets")
                    val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                        .single { it.getString("name") == "app-release.apk" && it.getString("state") == "uploaded" }
                    val url = apk.getString("browser_download_url")
                    require(validUpdateUrl(url))
                    val size = apk.getLong("size")
                    require(size in 1..MAX_APK)
                    val digest = updateDigest(apk.optString("digest")) ?: error("Missing SHA-256")
                    BeamRelease(json.getString("tag_name"), url, size, digest, json.optString("body").take(4000))
                }
            }
            if (result != release) ready = null
            release = result
            checkedAt = now
            prefs.edit().putLong("triedAt", now).putLong("checkedOk", now).apply()
            return true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            android.util.Log.w("BeamUpdates", "Check failed", failure)
            error = true
            prefs.edit().putLong("triedAt", now - DAY + 60 * 60 * 1000L).apply()
            return false
        } finally {
            busy = false
        }
    }

    /** Downloads the release found and verifies it; [ready] holds it then. */
    suspend fun download(context: Context) {
        val selected = release ?: return
        if (busy) return
        busy = true; error = false; progress = 0; ready = null
        try {
            ready = runInterruptible(Dispatchers.IO) {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val partial = File(dir, "update.part")
                val target = File(dir, "app-release.apk")
                target.delete()
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    transfer(selected.url, selected.size) { connection ->
                        connection.inputStream.use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(32 * 1024)
                                var total = 0L
                                val deadline = System.nanoTime() + 180_000_000_000L
                                while (true) {
                                    require(!Thread.currentThread().isInterrupted && System.nanoTime() < deadline)
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= selected.size)
                                    output.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                    progress = (total * 100 / selected.size).toInt()
                                }
                                require(total == selected.size)
                            }
                        }
                    }
                    require(digest.digest().joinToString("") { "%02x".format(it) } == selected.sha256)
                    verifyApk(context, partial)
                    require(partial.renameTo(target))
                    target
                } finally {
                    partial.delete()
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            android.util.Log.w("BeamUpdates", "Download failed", failure)
            error = true
        } finally {
            busy = false
            progress = -1
        }
    }

    /**
     * Hands the verified APK to Android's installer, which asks before installing. Without the
     * right to install apps, opens the screen that grants it (where the firmware has one).
     */
    fun install(context: Context): Boolean {
        return try {
            val file = ready ?: error("Nothing downloaded")
            verifyApk(context, file)
            error = false
            needsGrant = !canInstall(context)
            if (needsGrant) return false
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(grantScreen(context))
            } else {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            true
        } catch (failure: Exception) {
            android.util.Log.w("BeamUpdates", "Cannot open installer", failure)
            error = true
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, file: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(file.path, flags) ?: error("Invalid APK")
        val current = pm.getPackageInfo(context.packageName, flags)
        require(candidate.packageName == context.packageName)
        val nextCode = if (android.os.Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        val oldCode = if (android.os.Build.VERSION.SDK_INT >= 28) current.longVersionCode else current.versionCode.toLong()
        require(nextCode > oldCode)
        val signatures = current.signatures?.map { it.toCharsString() }?.toSet() ?: error("No installed signature")
        require(signatures.isNotEmpty() && candidate.signatures?.map { it.toCharsString() }?.toSet() == signatures)
    }

    private fun read(url: String, limit: Long): ByteArray = transfer(url, limit) { connection ->
        connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val deadline = System.nanoTime() + 30_000_000_000L
            while (true) {
                require(!Thread.currentThread().isInterrupted && System.nanoTime() < deadline)
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= limit)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun <T> transfer(url: String, limit: Long, block: (HttpURLConnection) -> T): T {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "Beam-Launcher")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            require(connection.responseCode == 200)
            require(connection.url.protocol == "https")
            require(connection.contentLengthLong <= limit)
            return block(connection)
        } finally {
            connection.disconnect()
        }
    }

    internal fun validUpdateUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            uri.rawPath.startsWith("/$REPO/releases/download/") &&
            uri.rawPath.endsWith("/app-release.apk") && !uri.rawPath.contains("..")
    }.getOrDefault(false)
}

/**
 * Whether release [tag] ("v1.0-ru-v3") is newer than the [installed] version ("1.0-ru-v2"): the
 * base version first, then the mod's revision. Anything unreadable counts as not newer; the version
 * code checked in the APK has the last word anyway.
 */
internal fun newerRelease(tag: String, installed: String): Boolean {
    fun parts(value: String): List<Int>? {
        val m = Regex("""v?(\d+(?:\.\d+){0,3})(?:-ru-v(\d+))?""").matchEntire(value.trim()) ?: return null
        val base = m.groupValues[1].split('.').map { it.toInt() }
        val padded = base + List(4 - base.size) { 0 }
        return padded + (m.groupValues[2].toIntOrNull() ?: 0)
    }
    val remote = parts(tag) ?: return false
    val local = parts(installed) ?: return false
    for (i in remote.indices) {
        val comparison = remote[i].compareTo(local[i])
        if (comparison != 0) return comparison > 0
    }
    return false
}

internal fun updateDigest(value: String): String? =
    value.removePrefix("sha256:").takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }?.lowercase()

/**
 * A small orange dot in the top-right corner while an update is waiting, on the settings button
 * and «О Beam», so it can be found without a pop-up.
 */
internal fun Modifier.updateDot(): Modifier = drawWithContent {
    drawContent()
    if (Updates.release != null) {
        val r = 5.dp.toPx()
        drawCircle(UpdateOrange, r, Offset(size.width - r * 2.4f, r * 2.4f))
    }
}

private val UpdateOrange = Color(0xFFFF6B2C)