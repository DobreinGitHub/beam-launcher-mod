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
        // --es voice_url HTTPS_URL [--es voice_sha256 HEX] downloads it instead.
        // Both run in the background (goAsync keeps the receiver alive); watch tag Voice.
        if (intent.hasExtra("voice_install") || intent.hasExtra("voice_url")) {
            val url = intent.getStringExtra("voice_url")
            val sha256 = intent.getStringExtra("voice_sha256")
            val app = context.applicationContext
            val pending = goAsync()
            Thread {
                try {
                    if (url != null) VoiceModel.download(app, url, sha256) else VoiceModel.installFrom(VoiceModelProvider.pushedZip(app), app)
                } finally {
                    pending.finish()
                }
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
            resultData = GameMode.read()?.let { "mode=${it.mode} level=${GameMode.level(context)}" } ?: "unavailable"
        }
        // HDMI: --ez hdmi_get true reads auto switch and connection; --ez hdmi_auto BOOL sets auto switch.
        if (intent.hasExtra("hdmi_auto")) Hdmi.setAutoSwitch(intent.getBooleanExtra("hdmi_auto", true))
        if (intent.hasExtra("boot_hdmi")) Hdmi.setBootToHdmi(context, intent.getBooleanExtra("boot_hdmi", false))
        if (intent.hasExtra("hdmi_get") || intent.hasExtra("hdmi_auto") || intent.hasExtra("boot_hdmi")) {
            resultData = "autoSwitch=${Hdmi.autoSwitch()} connected=${Hdmi.connected()} bootHdmi=${Hdmi.bootToHdmi()} cec=${Cec.control(context)} cecWake=${Cec.wakeUp()} " +
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
        // Keystone and zoom state, read only: --ez kst_get true
        if (intent.hasExtra("kst_get")) resultData = KeystoneProbe.dump()
        // --es kst_set "x0,y0,x1,y1,x2,y2,x3,y3" (TL, TR, BL, BR); --ei zoom_set N
        intent.getStringExtra("kst_set")?.let { spec ->
            val corners = spec.split(',').map { it.trim().toIntOrNull() }
            resultData = if (corners.size == 8 && corners.all { it != null }) {
                Keystone.setCorners(corners.filterNotNull()).toString()
            } else {
                "error: expected 8 comma-separated integers"
            }
        }
        if (intent.hasExtra("zoom_set")) resultData = Keystone.setZoom(intent.getIntExtra("zoom_set", 0)).toString()
        intent.getStringExtra("gmpf")?.let { spec ->
            resultData = runCatching {
                // "Class.method" or "Class.method:1,2" with int arguments.
                val (cls, call) = spec.split('.', limit = 2)
                val method = call.substringBefore(':')
                val args = call.substringAfter(':', "").split(',').filter { it.isNotBlank() }.map { it.trim().toInt() }
                val c = Class.forName("com.xgimi.gmpf.api.$cls")
                val m = c.getMethod("getInstance").invoke(null)
                val target = c.methods.first { it.name == method && it.parameterTypes.size == args.size }
                val converted = target.parameterTypes.zip(args).map { (type, v) ->
                    when (type) {
                        Byte::class.javaPrimitiveType -> v.toByte()
                        Boolean::class.javaPrimitiveType -> v != 0
                        else -> v
                    }
                }
                target.invoke(m, *converted.toTypedArray()).toString()
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

/** Dumps XGIMI's keystone corners and zoom limits for the adb hook. */
private object KeystoneProbe {
    private fun describe(obj: Any?): String = when (obj) {
        null -> "null"
        is Array<*> -> obj.joinToString(prefix = "[", postfix = "]") { describe(it) }
        is Number, is Boolean, is String -> obj.toString()
        else -> obj.javaClass.fields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .joinToString(prefix = "{", postfix = "}") { "${it.name}=${describe(it.get(obj))}" }
    }

    fun dump(): String {
        val out = StringBuilder()
        val dm = runCatching {
            val c = Class.forName("com.xgimi.gmpf.api.DisplayManager")
            c to c.getMethod("getInstance").invoke(null)
        }.getOrNull() ?: return "no DisplayManager"
        val (cls, m) = dm
        fun call(name: String, vararg args: Any): Any? = runCatching {
            cls.methods.first { it.name == name && it.parameterTypes.size == args.size &&
                (args.isEmpty() || it.parameterTypes[0].isInstance(args[0]) || it.parameterTypes[0].isPrimitive) }
                .invoke(m, *args)
        }.getOrElse { "err ${it.cause ?: it}" }
        fun filled(className: String, method: String): String = runCatching {
            val o = Class.forName("com.xgimi.gmpf.rp.$className").getConstructor().newInstance()
            val r = cls.getMethod(method, o.javaClass).invoke(m, o)
            "${describe(o)}${if (r != null) " -> $r" else ""}"
        }.getOrElse { "err ${it.cause ?: it}" }
        out.append("full=").append(filled("KeyStoneFullCoordinates", "getCorrectKeystone")).append(';')
        out.append("offset=").append(filled("KeyStoneFullCoordinatesOffset", "getCorrectKeystonePointOffset")).append(';')
        out.append("range=").append(filled("ZoomStepRange", "getZoomStepRange")).append(';')
        out.append("kstB=").append(call("getCorrectKeystone")).append(" mode=").append(call("getCurrentKeystoneMode"))
            .append(" adjust=").append(call("getKstAdjustType")).append(" zoomFactor=").append(call("getScreenZoomfactor"))
            .append(" allZoom=").append(describe(call("getAllZoomStep")))
            .append(" cur0=").append(call("getCurrentZoomStep", 0)).append(" cur1=").append(call("getCurrentZoomStep", 1))
        return out.toString()
    }
}

