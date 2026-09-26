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
        // Voice model: --ez voice_install true unzips the pushed zip (see VoiceModelProvider),
        // --es voice_url URL downloads it instead. Both run in the background; watch tag Voice.
        if (intent.hasExtra("voice_install") || intent.hasExtra("voice_url")) {
            val url = intent.getStringExtra("voice_url")
            val app = context.applicationContext
            Thread {
                if (url != null) VoiceModel.download(app, url) else VoiceModel.installFrom(VoiceModelProvider.pushedZip(app), app)
            }.start()
            resultData = "installing"
        }
        if (intent.hasExtra("voice_status")) resultData = "installed=${VoiceModel.installed(context)}"
        // --es voice_test "фраза": run a command as if it had been spoken.
        intent.getStringExtra("voice_test")?.let { resultData = VoiceCommands.run(context, it) ?: "no match" }
        // Bluetooth: --ez bt_list true lists paired devices; --es bt_connect / bt_disconnect NAME.
        if (intent.hasExtra("bt_list")) {
            resultData = XgimiBluetooth.devices(context).joinToString("; ") {
                "${it.name} type=${it.type} status=${it.status} audio=${it.audio} connected=${it.connected}"
            }
        }
        intent.getStringExtra("bt_connect")?.let { name ->
            val device = XgimiBluetooth.devices(context).firstOrNull { it.name == name }
            resultData = "connect=${device?.let { XgimiBluetooth.connect(context, it) }}"
        }
        intent.getStringExtra("bt_disconnect")?.let { name ->
            val device = XgimiBluetooth.devices(context).firstOrNull { it.name == name }
            resultData = "disconnect=${device?.let { XgimiBluetooth.disconnect(context, it) }}"
        }
        // Game mode: --ez game_get true reads mode (0 off, 1 on, 2 auto) and level.
        if (intent.hasExtra("game_mode")) GameMode.setMode(intent.getIntExtra("game_mode", GameMode.AUTO))
        if (intent.hasExtra("game_get") || intent.hasExtra("game_mode")) {
            resultData = GameMode.read()?.let { "mode=${it.mode} option=${it.option}" } ?: "unavailable"
        }
        // HDMI: --ez hdmi_get true reads auto switch and connection; --ez hdmi_auto BOOL sets auto switch.
        if (intent.hasExtra("hdmi_auto")) Hdmi.setAutoSwitch(intent.getBooleanExtra("hdmi_auto", true))
        if (intent.hasExtra("hdmi_get") || intent.hasExtra("hdmi_auto")) {
            resultData = "autoSwitch=${Hdmi.autoSwitch()} connected=${Hdmi.connected()} boot=${LauncherSettings.bootSource} " +
                "inputs=" + Xgimi.hdmiInputs(context).joinToString { "${it.label}|${it.device}|${it.id}" }
        }
        // Sensors: --ez eye_protection BOOL (test), then read all three.
        if (intent.hasExtra("eye_protection")) Sensors.setEyeProtection(intent.getBooleanExtra("eye_protection", false))
        if (intent.hasExtra("sensors_get") || intent.hasExtra("eye_protection")) {
            resultData = "realtimeKeystone=${Sensors.realtimeKeystone()} motionFocus=${Sensors.motionFocus()} eyes=${Sensors.eyeProtection()}"
        }
        if (intent.hasExtra("sound_mode")) {
            SoundMode.set(intent.getIntExtra("sound_mode", 3))
            resultData = "soundMode=${SoundMode.current()}"
        }
        // Generic read of a gmpf manager getter, for exploring: --es gmpf "DisplayManager.getHumanDetectOnOff"
        intent.getStringExtra("gmpf")?.let { spec ->
            resultData = runCatching {
                val (cls, method) = spec.split('.', limit = 2)
                val c = Class.forName("com.xgimi.gmpf.api.$cls")
                val m = c.getMethod("getInstance").invoke(null)
                c.getMethod(method).invoke(m).toString()
            }.getOrElse { "error ${it.cause ?: it}" }
        }
        intent.getStringExtra("bt_name")?.let { name ->
            @Suppress("DEPRECATION", "MissingPermission")
            val ok = BluetoothAdapter.getDefaultAdapter()?.setName(name) == true
            Log.i("AdbCommand", "Bluetooth name -> $name: $ok")
            resultData = if (ok) "ok" else "failed"
        }
    }
}
