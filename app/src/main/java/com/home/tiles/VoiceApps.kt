package com.home.tiles

import android.content.Context

/** An app the voice key opens: said as one of [phrases], started from the first of [packages] that is installed. */
internal class VoiceApp(val name: String, val packages: List<String>, val phrases: List<String>)

/**
 * The apps behind the voice commands. A few are built in, each with the package names it is known
 * by (stable and beta builds); more are added over adb and kept in the preferences, one per line:
 *
 *     Name|package1,package2|phrase one;phrase two
 *
 * An app that is not installed is left out of the commands, so it is never recognised either.
 */
internal object VoiceApps {
    val builtIn = listOf(
        VoiceApp("SmartTube", listOf("org.smarttube.stable", "org.smarttube.beta"), listOf("ютуб", "открой ютуб", "смарт тюб", "открой смарт тюб")),
        VoiceApp("Spotify", listOf("com.spotify.tv.android", "com.spotify.music"), listOf("музыка", "включи музыку", "открой музыку")),
        VoiceApp("Jellyfin", listOf("org.jellyfin.androidtv"), listOf("фильмы", "открой фильмы", "медиатека")),
    )

    private fun prefs(context: Context) = context.getSharedPreferences("voice", Context.MODE_PRIVATE)

    /** The apps added over adb. */
    fun custom(context: Context): List<VoiceApp> = parseVoiceApps(prefs(context).getString("apps", "").orEmpty())

    /** Added apps first, so one can take over a built-in phrase. */
    fun all(context: Context): List<VoiceApp> = custom(context) + builtIn

    fun installedPackage(context: Context, app: VoiceApp): String? = app.packages.firstOrNull { context.isInstalled(it) }

    /** Where "найди …" searches go: SmartTube, whichever build is installed. */
    fun searchPackage(context: Context): String? = builtIn.first { it.name == "SmartTube" }.let { installedPackage(context, it) }

    /** Adds [app], replacing an added one of the same name. */
    fun add(context: Context, app: VoiceApp) {
        val apps = custom(context).filterNot { it.name.equals(app.name, ignoreCase = true) } + app
        prefs(context).edit().putString("apps", formatVoiceApps(apps)).apply()
    }

    /** True if an added app of that name existed. */
    fun remove(context: Context, name: String): Boolean {
        val apps = custom(context)
        val kept = apps.filterNot { it.name.equals(name, ignoreCase = true) }
        if (kept.size == apps.size) return false
        prefs(context).edit().putString("apps", formatVoiceApps(kept)).apply()
        return true
    }
}

/** Lines of `Name|package1,package2|phrase one;phrase two`; lines that don't fit are skipped. */
internal fun parseVoiceApps(text: String): List<VoiceApp> = text.lines().mapNotNull { line ->
    val parts = line.split('|')
    if (parts.size != 3) return@mapNotNull null
    val name = parts[0].trim()
    val packages = parts[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val phrases = parts[2].split(';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    if (name.isEmpty() || packages.isEmpty() || phrases.isEmpty()) null else VoiceApp(name, packages, phrases)
}

internal fun formatVoiceApps(apps: List<VoiceApp>): String =
    apps.joinToString("\n") { "${it.name}|${it.packages.joinToString(",")}|${it.phrases.joinToString(";")}" }
