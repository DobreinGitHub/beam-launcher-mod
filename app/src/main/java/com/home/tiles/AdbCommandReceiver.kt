package com.home.tiles

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Small maintenance hooks for adb, guarded by the DUMP permission (held by the shell, not by apps):
 *   adb shell am broadcast -n com.home.tiles/.AdbCommandReceiver --es bt_name "XGIMI Play 6"
 *   adb shell am broadcast -n com.home.tiles/.AdbCommandReceiver --es background XMB --ez bg_animation true
 *   adb shell am broadcast -n com.home.tiles/.AdbCommandReceiver --ez lumens true
 */
class AdbCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LauncherSettings.init(context)
        // Appearance, for testing without the remote: --es background XMB, --ez bg_animation false
        intent.getStringExtra("background")?.let { name ->
            val index = Backgrounds.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            if (index >= 0) LauncherSettings.background = index
            resultData = if (index >= 0) "ok" else "unknown background"
        }
        if (intent.hasExtra("bg_animation")) {
            LauncherSettings.bgAnimation = intent.getBooleanExtra("bg_animation", true)
            resultData = "ok"
        }
        // Light-source brightness: --ez lumens true reads it, --ei lumens_level N sets it (0..10).
        if (intent.hasExtra("lumens_level")) {
            Lumens.setLevel(intent.getIntExtra("lumens_level", Lumens.MAX))
        }
        if (intent.hasExtra("lumens") || intent.hasExtra("lumens_level")) {
            resultData = "level=${Lumens.level()} mode=${Lumens.mode()}"
        }
        // Eco mode: --ez eco_get true reads it, --ez eco true/false sets it.
        if (intent.hasExtra("eco")) Eco.set(intent.getBooleanExtra("eco", false))
        if (intent.hasExtra("eco") || intent.hasExtra("eco_get")) resultData = "eco=${Eco.enabled()}"
        // Sound output: --ez sound_get true reads the mode, current output and connected devices.
        // --ez sound_bind true: connect to XGIMI's audio service (needed before switching outputs).
        if (intent.hasExtra("sound_bind")) SoundOutput.prepare(context)
        if (intent.hasExtra("sound_get")) {
            val devices = (0..8).filter { SoundOutput.connected(it) }
            val service = runCatching {
                val cls = Class.forName("com.xgimi.api.XgimiAudioManager")
                cls.getMethod("getAudioOutputDevices").invoke(cls.getMethod("getInstance").invoke(null))
            }.fold({ "ok paths=$it" }, { "error ${it.cause ?: it}" })
            resultData = "auto=${SoundOutput.auto()} output=${SoundOutput.output()} connected=$devices service=$service"
        }
        // --ei pic_item N --ei pic_value V: set one picture parameter (0 brightness .. 4 hue).
        if (intent.hasExtra("pic_item")) {
            XgimiService.bind(context)
            PictureAdjust.set(intent.getIntExtra("pic_item", 0), intent.getIntExtra("pic_value", 50))
        }
        // --ez pic_adv true: the custom mode's advanced settings.
        if (intent.hasExtra("pic_adv")) {
            XgimiService.bind(context)
            resultData = "nr=${PictureAdjust.noiseReduction()} memc=${PictureAdjust.motion()} " +
                "gamma=${PictureAdjust.gamma()} dynContrast=${PictureAdjust.dynamicContrast()} " +
                "localContrast=${PictureAdjust.localContrast()} hdr=${PictureAdjust.hdr()}"
        }
        // --ez pic_items true: custom picture parameters (binds XGIMI's service first; ask twice).
        if (intent.hasExtra("pic_items")) {
            XgimiService.bind(context)
            val items = (0..4).map { PictureAdjust.get(it) }
            resultData = "bright/contrast/sat/sharp/hue=$items colorTemp=${PictureAdjust.colorTemp()}"
        }
        // --ez pic_get true: current picture mode number (as XGIMI's settings use it).
        if (intent.hasExtra("pic_get")) {
            resultData = runCatching {
                val cls = Class.forName("com.xgimi.gmpf.api.GmTvManager")
                val tv = cls.getMethod("getInstance").invoke(null)
                val source = cls.getMethod("getCurrentInputSource").invoke(tv) as Int
                "source=$source mode=${cls.getMethod("getPictureMode", Int::class.javaPrimitiveType).invoke(tv, source)}"
            }.getOrElse { "error $it" }
        }
        intent.getStringExtra("bt_name")?.let { name ->
            @Suppress("DEPRECATION", "MissingPermission")
            val ok = BluetoothAdapter.getDefaultAdapter()?.setName(name) == true
            Log.i("AdbCommand", "Bluetooth name -> $name: $ok")
            resultData = if (ok) "ok" else "failed"
        }
    }
}
