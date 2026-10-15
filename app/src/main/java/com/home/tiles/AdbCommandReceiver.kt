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
        // Appearance, for testing without the remote: --es background XMB, --ez bg_animation false,
        // --ez ambient_tint false
        intent.getStringExtra("background")?.let { name ->
            val index = Backgrounds.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            if (index >= 0) LauncherSettings.background = index
            resultData = if (index >= 0) "ok" else "unknown background"
        }
        if (intent.hasExtra("bg_animation")) {
            LauncherSettings.bgAnimation = intent.getBooleanExtra("bg_animation", true)
            resultData = "ok"
        }
        if (intent.hasExtra("ambient_tint")) {
            LauncherSettings.ambientTint = intent.getBooleanExtra("ambient_tint", true)
            resultData = "ok"
        }
        // --ez appearance_get true: the current background, theme and language (to put them back after a test).
        if (intent.hasExtra("appearance_get")) {
            AppLanguage.init(context)
            resultData = "background=${Backgrounds[LauncherSettings.background].name} dark=${LauncherSettings.dark} " +
                "language=${AppLanguage.current} bg_animation=${LauncherSettings.bgAnimation} ambient_tint=${LauncherSettings.ambientTint}"
        }
        // --ez dark BOOL: the theme; --es language system|ru|en: the interface language.
        if (intent.hasExtra("dark")) {
            LauncherSettings.dark = intent.getBooleanExtra("dark", true)
            resultData = "ok"
        }
        intent.getStringExtra("language")?.let { language ->
            AppLanguage.init(context)
            val known = language in listOf(AppLanguage.SYSTEM, AppLanguage.RU, AppLanguage.EN)
            if (known) AppLanguage.current = language
            resultData = if (known) "ok" else "unknown language"
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
            val service = Gmpf.call("com.xgimi.api.XgimiAudioManager", "getAudioOutputDevices")
                .fold({ "ok paths=$it" }, { "error $it" })
            resultData = "auto=${SoundOutput.auto()} output=${SoundOutput.output()} connected=$devices service=$service"
        }
        // --ez pic_get true: current picture mode number (as XGIMI's settings use it).
        if (intent.hasExtra("pic_get")) {
            val source = Gmpf.int("GmTvManager", "getCurrentInputSource")
            resultData = if (source == null) "error: no input source" else "source=$source mode=${Gmpf.int("GmTvManager", "getPictureMode", source)}"
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
        // Voice commands that open apps: --es voice_app "Name|package1,package2|phrase one;phrase two"
        // adds one, --es voice_app_remove Name removes it, --ez voice_apps true lists the added ones.
        intent.getStringExtra("voice_app")?.let { spec ->
            val app = parseVoiceApps(spec).firstOrNull()
            resultData = if (app == null) "error: expected Name|package1,package2|phrase one;phrase two" else {
                VoiceApps.add(context, app)
                "ok ${app.name}: ${app.phrases}"
            }
        }
        intent.getStringExtra("voice_app_remove")?.let { name ->
            resultData = if (VoiceApps.remove(context, name)) "ok" else "no such app"
        }
        if (intent.hasExtra("voice_apps")) resultData = formatVoiceApps(VoiceApps.custom(context)).ifEmpty { "none" }
        // --es voice_test "фраза": run a command as if it had been spoken.
        intent.getStringExtra("voice_test")?.let { resultData = VoiceCommands.run(context, it) ?: "no match" }
        // HDMI: --ez hdmi_get true reads auto switch and connection; --ez hdmi_auto BOOL sets auto switch.
        if (intent.hasExtra("hdmi_auto")) Hdmi.setAutoSwitch(intent.getBooleanExtra("hdmi_auto", true))
        if (intent.hasExtra("boot_hdmi")) Hdmi.setBootToHdmi(context, intent.getBooleanExtra("boot_hdmi", false))
        if (intent.hasExtra("hdmi_get") || intent.hasExtra("hdmi_auto") || intent.hasExtra("boot_hdmi")) {
            val inputs = Xgimi.hdmiInputs(context)
            resultData = "autoSwitch=${Hdmi.autoSwitch()} connectedPorts=${Hdmi.connectedPorts(inputs.size.coerceAtLeast(1))} " +
                "bootHdmi=${Hdmi.bootToHdmi()} cec=${Cec.control(context)} cecWake=${Cec.wakeUp()} " +
                "inputs=" + inputs.joinToString { "${it.label}|${it.device}|${it.id}" }
        }
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
        // Generic read of a gmpf manager getter, for exploring: --es gmpf "DisplayManager.getHumanDetectOnOff"
        intent.getStringExtra("gmpf")?.let { spec ->
            // "Class.method" or "Class.method:1,2": integer arguments are converted to what the method takes.
            resultData = runCatching {
                val (cls, call) = spec.split('.', limit = 2)
                val method = call.substringBefore(':')
                val args = call.substringAfter(':', "").split(',').filter { it.isNotBlank() }.map { it.trim().toInt() }
                Gmpf.call(cls, method, *args.toTypedArray()).fold({ it.toString() }, { "error $it" })
            }.getOrElse { "error: expected Class.method[:int,int] ($it)" }
        }
        // --ez channels_get true: other apps' channels with their item count and first poster URI.
        if (intent.hasExtra("channels_get")) {
            resultData = queryTvChannels(context).joinToString("\n") { ch ->
                "${ch.key} [${ch.items.size}] ${ch.items.firstOrNull()?.poster ?: "-"}"
            }.ifEmpty { "none" }
        }
        // --ez channels_fields true: which optional fields each channel's programs fill in.
        if (intent.hasExtra("channels_fields")) resultData = describeChannelFields(context)
        // --ez order_get true: the home row's saved order (row keys, comma-separated).
        if (intent.hasExtra("order_get")) resultData = AppRepository(context).savedOrder().joinToString(",").ifEmpty { "none" }
        intent.getStringExtra("bt_name")?.let { name ->
            @Suppress("DEPRECATION", "MissingPermission")
            val ok = BluetoothAdapter.getDefaultAdapter()?.setName(name) == true
            Log.i("AdbCommand", "Bluetooth name -> $name: $ok")
            resultData = if (ok) "ok" else "failed"
        }
    }
}
