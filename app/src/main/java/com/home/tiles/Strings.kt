package com.home.tiles

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import java.util.Locale

/** Holds the application context so strings can be looked up outside composables too (services, hardware helpers). */
class BeamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        AppLanguage.init(this)
    }

    companion object {
        @Volatile
        internal var app: Application? = null
    }
}

/**
 * The interface language: the system's, or Russian or English regardless of it (the firmware of
 * this projector refuses English as a system language). Snapshot state, so every composable that
 * reads a string through [tr] recomposes when it changes.
 */
object AppLanguage {
    const val SYSTEM = "system"
    const val RU = "ru"
    const val EN = "en"

    private val choice = mutableStateOf(SYSTEM)
    private var prefs: SharedPreferences? = null
    @Volatile
    private var forced: Resources? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        apply(prefs?.getString("language", SYSTEM) ?: SYSTEM)
    }

    var current: String
        get() = choice.value
        set(value) {
            prefs?.edit()?.putString("language", value)?.apply()
            apply(value)
        }

    private fun apply(value: String) {
        val locale = when (value) {
            RU -> Locale("ru")
            EN -> Locale.ENGLISH
            else -> null
        }
        val app = BeamApp.app
        forced = if (locale == null || app == null) null else {
            val configuration = Configuration(app.resources.configuration)
            configuration.setLocale(locale)
            app.createConfigurationContext(configuration).resources
        }
        choice.value = if (locale == null) SYSTEM else value
    }

    /** For dates and times; follows [current] and is read as state. */
    val locale: Locale
        get() = when (choice.value) {
            RU -> Locale("ru")
            EN -> Locale.ENGLISH
            else -> Locale.getDefault()
        }

    internal fun resources(): Resources? {
        choice.value // subscribes the reader to language changes
        return forced ?: BeamApp.app?.resources
    }
}

/**
 * A string resource in the app's language. The Application's resources follow the system
 * configuration, so this also works in the accessibility service and for background work.
 */
fun tr(@StringRes id: Int, vararg args: Any): String {
    val resources = AppLanguage.resources() ?: return ""
    return if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args)
}
