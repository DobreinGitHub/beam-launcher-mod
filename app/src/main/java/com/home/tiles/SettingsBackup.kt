package com.home.tiles

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * All of Beam's settings as one base64-encoded JSON string, for testing installs over adb without
 * losing them: export, reinstall or replace Beam, import, restart Beam. Base64 so it passes through
 * a broadcast's result and an --es argument untouched (the shell can't reach the app's own folders).
 */
object SettingsBackup {
    private val FILES = listOf("settings", "tiles", "battery", "updates", "voice", "keystone")

    fun export(context: Context): String {
        val root = JSONObject()
        for (name in FILES) {
            val values = JSONObject()
            for ((key, value) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                val entry = when (value) {
                    is Boolean -> JSONObject().put("t", "b").put("v", value)
                    is Int -> JSONObject().put("t", "i").put("v", value)
                    is Long -> JSONObject().put("t", "l").put("v", value)
                    is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", value)
                    is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.map { it.toString() }))
                    else -> continue
                }
                values.put(key, entry)
            }
            root.put(name, values)
        }
        return Base64.encodeToString(root.toString().toByteArray(), Base64.NO_WRAP)
    }

    /** Replaces every settings file with the backup's contents; Beam needs a restart to read them. */
    fun import(context: Context, base64: String): Int {
        val root = JSONObject(String(Base64.decode(base64, Base64.DEFAULT)))
        var count = 0
        for (name in FILES) {
            val values = root.optJSONObject(name) ?: continue
            val edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for (key in values.keys()) {
                val entry = values.getJSONObject(key)
                when (entry.getString("t")) {
                    "b" -> edit.putBoolean(key, entry.getBoolean("v"))
                    "i" -> edit.putInt(key, entry.getInt("v"))
                    "l" -> edit.putLong(key, entry.getLong("v"))
                    "f" -> edit.putFloat(key, entry.getDouble("v").toFloat())
                    "s" -> edit.putString(key, entry.getString("v"))
                    "set" -> {
                        val array = entry.getJSONArray("v")
                        edit.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                    }
                }
                count++
            }
            edit.commit()
        }
        return count
    }
}
