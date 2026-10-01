package com.home.tiles

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Proxy

/**
 * The projector's light-source brightness, as XGIMI's own settings drive it: level 0..10 through
 * com.xgimi.gmpf.api.DisplayManager in the com.xgimi.api platform library (see [Gmpf]; it only
 * exists on XGIMI firmware). Null/false when unavailable.
 */
object Lumens {
    const val MAX = 10
    private const val DISPLAY = "DisplayManager"

    fun level(): Int? = Gmpf.int(DISPLAY, "getDlpLumensLevel")

    fun mode(): Int? = Gmpf.int(DISPLAY, "getDlpLumensMode")

    /** False if the firmware didn't take it (or isn't XGIMI's). */
    fun setLevel(level: Int): Boolean = Gmpf.ok(DISPLAY, "setDlpLumensLevel", level.coerceIn(0, MAX))
}

/**
 * The current picture mode, as a number [Xgimi.setPictureMode] takes. GmTvManager reports the
 * mode of the current input; it numbers AI picture 10 where the settings app sends 16.
 */
object PictureMode {
    fun current(): Int? {
        val source = Gmpf.int("GmTvManager", "getCurrentInputSource") ?: return null
        val mode = Gmpf.int("GmTvManager", "getPictureMode", source) ?: return null
        return if (mode == 10) 16 else mode
    }

    /**
     * Switches the mode the way XGIMI's settings app does internally (MstPictureManager, same
     * numbers). Needs [XgimiService.bind]; false if the service isn't bound yet.
     */
    fun set(mode: Int): Boolean = Gmpf.ok(MST_PICTURE, "setPictureMode", mode)
}

/** XGIMI eco mode (dimmer, quieter), via com.xgimi.gmpf.api.SystemManager like the stock panel. */
object Eco {
    fun enabled(): Boolean? = Gmpf.bool("SystemManager", "getEcoState")

    fun set(on: Boolean): Boolean = Gmpf.ok("SystemManager", "setEcoState", on)
}


/**
 * Sound output, as XGIMI's Sound output page drives it through com.xgimi.gmpf.api.GmAudioManager:
 * switch mode 0 = automatic, 1 = manual; outputs are the EN_AUDIO_* device numbers below.
 */
object SoundOutput {
    const val SPEAKER = 0
    const val SPDIF = 1
    const val ARC = 2
    const val BLUETOOTH = 3

    private const val AUDIO = "GmAudioManager"

    val available get() = Gmpf.available(AUDIO)

    fun auto(): Boolean? = Gmpf.int(AUDIO, "getAudioDeviceSwitchMode")?.let { it == 0 }

    fun output(): Int? = Gmpf.int(AUDIO, "getAudioOutput")

    fun connected(device: Int): Boolean = Gmpf.bool(AUDIO, "isAudioDeviceConnected", device) == true

    fun setAuto(on: Boolean) {
        Gmpf.call(AUDIO, "setAudioDeviceSwitchMode", if (on) 0 else 1)
    }

    /** Binds XGIMI's service, which [setOutput] needs; see [XgimiService.bind]. */
    fun prepare(context: Context) = XgimiService.bind(context)

    /**
     * Picks the output the way XGIMI's page does (VoiceHelper.setAudioDevice): through the audio
     * service in com.xgimi.api.XgimiAudioManager, which also moves Android's routing. The plain
     * GmAudioManager.setAudioOutput only switches the amplifier, so a Bluetooth speaker kept playing.
     */
    fun setOutput(device: Int) {
        if (!Gmpf.ok("com.xgimi.api.XgimiAudioManager", "setAudioDeviceOn", device, 0)) {
            Gmpf.call(AUDIO, "setAudioOutput", device)
        }
    }
}

/** The picture parameters live in this manager, which needs [XgimiService.bind]. */
private const val MST_PICTURE = "com.xgimi.video.MstPictureManager"

/**
 * Parts of com.xgimi.api (XgimiAudioManager, MstPictureManager) talk to com.xgimi.xgimiservice,
 * which the library binds only after XgimiAidlServiceManager.init(context, listener), as XGIMI's
 * settings do on start. Call [bind] ahead of using them; the binding is asynchronous.
 *
 * If the service goes away (it is restarted, or its process is killed) and says so through the
 * listener, binding is retried a few times with growing pauses; the library may also reconnect by
 * itself, in which case the listener reports it and the pending retry stands down. Whether
 * init() may be called a second time is not documented: the log (tag XgimiService) shows what the
 * library reports and what a retry did.
 */
object XgimiService {
    private const val TAG = "XgimiService"
    private const val MAX_RETRIES = 8

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var binding = false
    private var retries = 0
    private var described = false

    @Volatile
    private var bound = false

    fun bind(context: Context) {
        synchronized(this) {
            if (bound || binding) return
            binding = true
            retries = 0
            appContext = context.applicationContext
        }
        attempt()
    }

    private fun attempt() {
        val context = appContext ?: return
        val result = runCatching { init(context) }
        synchronized(this) {
            binding = false
            if (result.isSuccess) bound = true
        }
        result.onFailure {
            val cause = it.cause ?: it
            Log.w(TAG, "Could not bind XGIMI service", it)
            // Not an XGIMI firmware: there is nothing to retry.
            if (cause !is ClassNotFoundException) scheduleRetry()
        }
    }

    private fun init(context: Context) {
        val cls = Class.forName("com.xgimi.clients.XgimiAidlServiceManager")
        val instance = cls.getField("INSTANCE").get(null)
        val listener = Class.forName("com.xgimi.clients.XgimiAidlServiceManager\$IAidlConnectListener")
        if (!described) {
            described = true
            Log.i(TAG, "XgimiAidlServiceManager: ${cls.methods.map { it.name }.distinct().sorted()}; listener: ${listener.methods.map { it.name }}")
        }
        val callback = Proxy.newProxyInstance(listener.classLoader, arrayOf(listener)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "XgimiService.bind"
                else -> {
                    onListenerEvent(method.name)
                    defaultFor(method.returnType)
                }
            }
        }
        cls.getMethod("init", Context::class.java, listener).invoke(instance, context, callback)
    }

    /** The listener's methods aren't documented; its disconnect callback is recognised by name. */
    private fun onListenerEvent(name: String) {
        val lower = name.lowercase()
        val gone = "disconnect" in lower || "died" in lower || "unbind" in lower || "lost" in lower
        Log.i(TAG, "XGIMI service $name" + if (gone) " (gone)" else "")
        if (gone) {
            synchronized(this) { bound = false }
            scheduleRetry()
        } else {
            synchronized(this) {
                bound = true
                retries = 0
            }
        }
    }

    /** 2, 4, 8 ... 30 s apart, [MAX_RETRIES] times; a later [bind] call starts over. */
    private fun scheduleRetry() {
        val pause = synchronized(this) {
            if (bound || retries >= MAX_RETRIES) return
            (2_000L shl retries++).coerceAtMost(30_000L)
        }
        handler.postDelayed({
            synchronized(this) {
                if (bound || binding) return@postDelayed
                binding = true
            }
            Log.i(TAG, "Rebinding XGIMI service")
            attempt()
        }, pause)
    }

    /** What a proxied method with this return type must return in place of null. */
    private fun defaultFor(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> 0.toChar()
        else -> null
    }
}

/**
 * The picture parameters XGIMI's picture page edits (brightness, contrast, saturation, sharpness,
 * hue, colour temperature), through com.xgimi.video.MstPictureManager. Needs [XgimiService.bind].
 */
object PictureAdjust {
    const val BRIGHTNESS = 0
    const val CONTRAST = 1
    const val SATURATION = 2
    const val SHARPNESS = 3
    const val HUE = 4

    private const val TV = "GmTvManager"

    fun get(item: Int): Int? = Gmpf.int(MST_PICTURE, "getPictureItem", item)

    /** The range of the four sliders (and the hue): XGIMI's pages show 0..100. */
    const val ITEM_MAX = 100

    /** Brightness .. hue only; [value] is kept inside 0..[ITEM_MAX]. False for an unknown item. */
    fun set(item: Int, value: Int): Boolean {
        if (item !in BRIGHTNESS..HUE) return false
        return Gmpf.ok(MST_PICTURE, "setPictureItem", item, value.coerceIn(0, ITEM_MAX))
    }

    /** GmTvManager calls that take the input first, like XGIMI's picture page makes them. */
    private fun tv(name: String, vararg args: Any?): Result<Any?> {
        val source = Gmpf.int(TV, "getCurrentInputSource") ?: return Result.failure(IllegalStateException("no input source"))
        return Gmpf.call(TV, name, source, *args)
    }

    private fun tvInt(name: String): Int? = (tv(name).getOrNull() as? Number)?.toInt()

    /** Noise reduction: 0 off, 1 low, 2 medium, 3 high, 4 auto. */
    fun noiseReduction(): Int? = Gmpf.int(MST_PICTURE, "getNoiseReduction")
    fun setNoiseReduction(level: Int) { Gmpf.call(MST_PICTURE, "setNoiseReduction", level) }

    /** Motion compensation (MEMC): 0 off, 1 low, 2 medium, 3 high. */
    fun motion(): Int? = Gmpf.int(MST_PICTURE, "getMfcLevel")
    fun setMotion(level: Int) { Gmpf.call(MST_PICTURE, "setMfcLevel", level) }

    /** Gamma index: 0 = 1.8 ... 4 = 2.2 ... 8 = 2.6. */
    fun gamma(): Int? = tvInt("getTvGammaLevel")
    fun setGamma(level: Int) { tv("setTvGammaLevel", level) }

    fun dynamicContrast(): Boolean? = tv("getTvDynamicContrastEnable").getOrNull() as? Boolean
    fun setDynamicContrast(on: Boolean) { tv("setTvDynamicContrastEnable", on) }

    /** Local contrast: 0 off, 1 low, 2 medium, 3 high. */
    fun localContrast(): Int? = tvInt("getUcdLevel")
    fun setLocalContrast(level: Int) { tv("setUcdLevel", level) }

    fun hdr(): Boolean? = Gmpf.bool(TV, "getHdrEnable")
    fun setHdr(on: Boolean) { Gmpf.call(TV, "setHdrEnable", on) }

    /** 0 cool, 1 natural, 2 warm (MstPictureManager.COLOR_TEMP_*). */
    fun colorTemp(): Int? = Gmpf.int(MST_PICTURE, "getColorTemp")
    fun setColorTemp(value: Int) { Gmpf.call(MST_PICTURE, "setColorTemp", value) }
}


/**
 * Paired Bluetooth devices and connect/disconnect, through XGIMI's Bluetooth service
 * (com.xgimi.bluetooth.XDBluetoothManager, on the firmware's boot class path): the plain Android
 * API can't connect audio devices without system privileges.
 */
object XgimiBluetooth {
    class Device(val name: String, val address: String, val type: Int, val status: Int, internal val item: Any) {
        val connected get() = status == STATUS_CONNECTED
        val connecting get() = status == STATUS_CONNECTING
        /** Speakers and headphones (A2DP); the remote and phones are listed but not audio. */
        val audio get() = type == TYPE_A2DP || type == TYPE_HEADSET
        val remote get() = type == TYPE_REMOTE
    }

    @Volatile
    private var manager: Any? = null

    @Synchronized
    private fun manager(context: Context): Any? {
        manager?.let { return it }
        return runCatching {
            Class.forName("com.xgimi.bluetooth.XDBluetoothManager")
                .getConstructor(Context::class.java).newInstance(context.applicationContext)!!
                .also { manager = it }
        }.onFailure { Log.w("XgimiBluetooth", "XDBluetoothManager unavailable", it) }.getOrNull()
    }

    private val constants: Map<String, Int> by lazy {
        runCatching {
            val cls = Class.forName("com.xgimi.bluetooth.XDBluetoothDeviceItem")
            cls.fields.filter { it.type == Int::class.javaPrimitiveType && java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .associate { it.name to it.getInt(null) }
        }.getOrDefault(emptyMap())
    }
    private val STATUS_CONNECTED get() = constants["CONNECT_STATUS_CONNECTED"] ?: -1
    private val STATUS_CONNECTING get() = constants["CONNECT_STATUS_CONNECTING"] ?: -1
    private val TYPE_A2DP get() = constants["BTYPE_A2DP"] ?: -1
    private val TYPE_HEADSET get() = constants["BTYPE_HEADSET"] ?: -1
    private val TYPE_REMOTE get() = constants["BTYPE_REMOTE_CONTROL_HID"] ?: -1

    /** Paired devices with their connection state. Blocking binder call. */
    fun devices(context: Context): List<Device> {
        val m = manager(context) ?: return emptyList()
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val items = Gmpf.invoke(m, "getBondDevices").getOrThrow() as? List<Any> ?: emptyList()
            items.map { item ->
                val c = item.javaClass
                fun str(f: String) = runCatching { c.getField(f).get(item) as? String }.getOrNull()
                    ?: runCatching { c.getDeclaredField(f).apply { isAccessible = true }.get(item) as? String }.getOrNull().orEmpty()
                fun int(f: String) = runCatching { c.getField(f).getInt(item) }.getOrNull()
                    ?: runCatching { c.getDeclaredField(f).apply { isAccessible = true }.getInt(item) }.getOrDefault(-1)
                Device(str("BName"), str("BAddress"), int("BType"), int("BStatus"), item)
            }
        }.onFailure { Log.w("XgimiBluetooth", "getBondDevices failed", it) }.getOrDefault(emptyList())
    }

    fun connect(context: Context, device: Device): Boolean = call(context, "connectDevice", device)

    fun disconnect(context: Context, device: Device): Boolean = call(context, "disConnectDevice", device)

    private fun call(context: Context, name: String, device: Device): Boolean {
        val m = manager(context) ?: return false
        return Gmpf.invoke(m, name, device.item).getOrNull() as? Boolean ?: false
    }
}

/**
 * Game mode (lower input lag), as XGIMI's picture page sets it through com.xgimi.gmpf.api
 * DisplayManager: auto = type 1; on = type 0 + state 0; off = type 0 + state 1. The level (basic /
 * top speed) is the "game mode option". XGIMI only allows it with an HDMI signal.
 */
object GameMode {
    const val OFF = 0
    const val ON = 1
    const val AUTO = 2

    class State(val mode: Int)

    private const val DISPLAY = "DisplayManager"

    fun read(): State? = runCatching {
        val propClass = Class.forName("com.xgimi.gmpf.rp.GameModeProp")
        val prop = propClass.getConstructor().newInstance()
        Gmpf.call(DISPLAY, "getGameModeProp", prop).getOrThrow()
        val type = propClass.getField("type").getInt(prop)
        val state = propClass.getField("state").getInt(prop)
        State(if (type == 1) AUTO else if (state == 0) ON else OFF)
    }.onFailure { Log.w("GameMode", "read failed", it) }.getOrNull()

    /** False if any step was refused. */
    fun setMode(mode: Int): Boolean = when (mode) {
        AUTO -> Gmpf.ok(DISPLAY, "setGameModeType", 1)
        ON -> Gmpf.ok(DISPLAY, "setGameModeType", 0) && Gmpf.ok(DISPLAY, "setGameModeState", 0)
        else -> Gmpf.ok(DISPLAY, "setGameModeType", 0) && Gmpf.ok(DISPLAY, "setGameModeState", 1)
    }

    /**
     * Level when game mode is on: 0 basic, 1 top speed. XGIMI's HDMI player sends option 0 / 3
     * (1 / 2 on high-frame-rate models, which this one isn't: the driver turns 1 into 0). No getter
     * reports it back, so Beam remembers what it last set.
     */
    fun level(context: Context): Int = prefs(context).getInt("level", 0)

    fun setLevel(context: Context, level: Int): Boolean {
        val ok = Gmpf.ok(DISPLAY, "setGameModeOption", if (level == 1) TOP_SPEED else STANDARD)
        if (ok) prefs(context).edit().putInt("level", level).apply()
        return ok
    }

    private fun prefs(context: Context) = context.getSharedPreferences("gameMode", Context.MODE_PRIVATE)

    private const val STANDARD = 0
    private const val TOP_SPEED = 3
}

/**
 * HDMI behaviour: the firmware's "switch to HDMI when plugged in" (SystemManager), and whether a
 * device on HDMI 1 is connected (GmTvManager), used by Beam's own "start on HDMI" option.
 */
object Hdmi {
    fun autoSwitch(): Boolean? = Gmpf.bool("SystemManager", "getHdmiAutoSwitch")

    fun setAutoSwitch(on: Boolean) {
        Gmpf.call("SystemManager", "setHdmiAutoSwitch", on)
    }

    /**
     * True when something is plugged into HDMI [port] (1-based, GmTvManager.getHdmiConnectStatus).
     * The firmware numbers the ports in the order [Xgimi.hdmiInputs] lists them.
     */
    fun connected(port: Int): Boolean = Gmpf.bool("GmTvManager", "getHdmiConnectStatus", port) == true

    /** The ports (1-based) of the first [ports] that have something plugged in. */
    fun connectedPorts(ports: Int): List<Int> = (1..ports).filter(::connected)

    /**
     * XGIMI's own "boot source" (开机源): the firmware goes straight to HDMI after power-on, before
     * any launcher starts. Two system properties, as its settings page writes them.
     */
    fun bootToHdmi(): Boolean = XgimiCommon.property(BOOT_SOURCE) == "1"

    fun setBootToHdmi(context: Context, on: Boolean): Boolean {
        val value = if (on) "1" else "0"
        return XgimiCommon.setProperty(context, BOOT_SOURCE, value) &&
            XgimiCommon.setProperty(context, BOOT_ANIMATION_WAIT, value)
    }

    /**
     * Beam used to switch to HDMI itself after boot; carries that choice over to the firmware.
     * Retries in the background: XGIMI's service binds a moment after the launcher starts.
     */
    fun migrateBootSource(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.contains("bootSource")) return
        val hdmi = prefs.getString("bootSource", null) == "hdmi"
        Thread {
            repeat(20) {
                if (!hdmi || setBootToHdmi(context, true)) {
                    prefs.edit().remove("bootSource").apply()
                    return@Thread
                }
                Thread.sleep(1000)
            }
        }.start()
    }

    private const val BOOT_SOURCE = "persist.sys.hdmi.bootsource"
    private const val BOOT_ANIMATION_WAIT = "persist.sys.bootanim.alwayswait"
}

/**
 * XGIMI's common service (com.xgimi.api.XgimiCommonManager) runs as system: it writes system
 * properties apps can't, and holds the HDMI-CEC switches.
 */
object XgimiCommon {
    fun property(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrDefault("")

    /** False when XGIMI's service isn't bound yet (or refused). */
    fun setProperty(context: Context, name: String, value: String): Boolean =
        invoke(context, "setSystemProperties", name, value).isSuccess

    fun call(context: Context, name: String, vararg args: Any): Any? = invoke(context, name, *args).getOrNull()

    private fun invoke(context: Context, name: String, vararg args: Any): Result<Any?> {
        XgimiService.bind(context)
        return Gmpf.call("com.xgimi.api.XgimiCommonManager", name, *args)
    }
}

/**
 * HDMI-CEC as XGIMI's "CEC control" page switches it: control of HDMI devices (needed for ARC),
 * and "HDMI controls the projector" (a device turning on wakes the projector, and off with it).
 */
object Cec {
    fun control(context: Context): Boolean? = XgimiCommon.call(context, "isHdmiCecControlEnabled") as? Boolean

    fun setControl(context: Context, on: Boolean) {
        XgimiCommon.call(context, "setHdmiCecControlEnabled", on)
        // Turning control off also turns the wake-up off, like XGIMI's page does.
        if (!on) setWakeUp(context, false)
    }

    fun wakeUp(): Boolean? = Gmpf.bool("GmTvManager", "getCecWakeUpState")

    fun setWakeUp(context: Context, on: Boolean) {
        Gmpf.call("GmTvManager", "setCecWakeUp", on)
        XgimiCommon.call(context, "setHdmiCecAutoDeviceOffEnabled", on)
        XgimiCommon.call(context, "setHdmiCecAutoWakeupEnabled", on)
        if (on) XgimiCommon.call(context, "setHdmiCecControlEnabled", true)
    }
}

/** The chime at power-on (SystemManager.enablePowerOnMusic). */
object BootMusic {
    fun enabled(): Boolean? = Gmpf.bool("SystemManager", "isPowerOnMusicEnabled")
    fun set(on: Boolean) { Gmpf.call("SystemManager", "enablePowerOnMusic", on) }
}


/**
 * Motion and presence sensors, as XGIMI's settings switch them: re-run keystone when the projector
 * is moved (MotionDetectionManager.setAccTriggerAK, "实时画面校正"), refocus on tilt
 * (setAngTriggerAF), and eye protection that dims the light when someone stands in the beam
 * (DisplayManager.setHumanDetectOnOff).
 */
object Sensors {
    private const val MOTION = "MotionDetectionManager"
    private const val DISPLAY = "DisplayManager"
    private const val FACTORY = "GmFactoryManager"

    fun realtimeKeystone(): Boolean? = Gmpf.bool(MOTION, "getAccTriggerAK")
    fun setRealtimeKeystone(on: Boolean) { Gmpf.call(MOTION, "setAccTriggerAK", on) }

    fun motionFocus(): Boolean? = Gmpf.bool(MOTION, "getAngTriggerAF")
    fun setMotionFocus(on: Boolean) { Gmpf.call(MOTION, "setAngTriggerAF", on) }

    fun eyeProtection(): Boolean? = Gmpf.bool(DISPLAY, "getHumanDetectOnOff")
    fun setEyeProtection(on: Boolean) { Gmpf.call(DISPLAY, "setHumanDetectOnOff", on) }

    /** Auto keystone right after power-on ("开机自动校正"). */
    fun bootKeystone(): Boolean? = Gmpf.bool(FACTORY, "getPowerOnAKFlag")
    fun setBootKeystone(on: Boolean) { Gmpf.call(FACTORY, "savePowerOnAKFlag", on) }
}

/**
 * Idle time before XGIMI's screensaver starts: the plain screen_off_timeout, with the values its
 * settings page offers ("never" is Int.MAX_VALUE). Writing it needs the WRITE_SETTINGS app op,
 * granted over adb (tools/restore.ps1).
 */
object ScreensaverTimeout {
    /** In ascending order, so ← → in the panel is shorter / longer; "never" is the longest. */
    val options = listOf(
        60_000 to "1 мин",
        300_000 to "5 мин",
        600_000 to "10 мин",
        1_800_000 to "30 мин",
        3_600_000 to "1 час",
        Int.MAX_VALUE to "Никогда",
    )

    fun current(context: Context): Int =
        android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 300_000)

    fun canWrite(context: Context) = android.provider.Settings.System.canWrite(context)

    fun set(context: Context, value: Int): Boolean = runCatching {
        android.provider.Settings.System.putInt(context.contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, value)
    }.onFailure { Log.w("Screensaver", "can't set timeout", it) }.getOrDefault(false)
}

/** XGIMI's sound modes (GmAudioManager.set/getSoundeffect), numbered as its settings page sets them. */
object SoundMode {
    val modes = listOf(3 to "AI", 1 to "Кино", 2 to "Музыка", 12 to "Спорт", 4 to "Караоке")

    fun current(): Int? = Gmpf.int("GmAudioManager", "getSoundeffect")

    fun set(mode: Int) {
        Gmpf.call("GmAudioManager", "setSoundeffect", mode)
    }
}

/** eARC to an HDMI 2.1 sound system: XGIMI's "Auto" is on, "Off" is off (GmTvManager). */
object Earc {
    fun enabled(): Boolean? = Gmpf.bool("GmTvManager", "getEARCEnableState")
    fun set(on: Boolean) { Gmpf.call("GmTvManager", "setEARCEnable", on) }
}

/**
 * The two switches under XGIMI's Bluetooth page. Visibility is a global setting that XGIMI's
 * Bluetooth service watches and applies; absolute volume is a system property only system apps
 * may write, so it goes through XGIMI's common service (XgimiCommonManager.setSystemProperties).
 */
object BluetoothOptions {
    private const val DISCOVERABLE = "bluetooth_discoverable"
    private const val DISABLE_ABS_VOLUME = "persist.bluetooth.disableabsvol"

    fun discoverable(context: Context): Boolean =
        android.provider.Settings.Global.getInt(context.contentResolver, DISCOVERABLE, 1) == 1

    fun setDiscoverable(context: Context, on: Boolean) {
        runCatching { android.provider.Settings.Global.putInt(context.contentResolver, DISCOVERABLE, if (on) 1 else 0) }
            .onFailure { Log.w("BluetoothOptions", "can't set visibility", it) }
    }

    fun absoluteVolume(): Boolean = XgimiCommon.property(DISABLE_ABS_VOLUME) != "true"

    fun setAbsoluteVolume(context: Context, on: Boolean) {
        XgimiCommon.setProperty(context, DISABLE_ABS_VOLUME, (!on).toString())
    }
}

/** Android's own click sound for remote presses (sound_effects_enabled), XGIMI's "Key tone". */
object KeyTones {
    fun enabled(context: Context): Boolean =
        android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SOUND_EFFECTS_ENABLED, 0) == 1

    fun set(context: Context, on: Boolean): Boolean = runCatching {
        android.provider.Settings.System.putInt(
            context.contentResolver, android.provider.Settings.System.SOUND_EFFECTS_ENABLED, if (on) 1 else 0,
        ).also {
            val audio = context.getSystemService(android.media.AudioManager::class.java)
            if (on) audio.loadSoundEffects() else audio.unloadSoundEffects()
        }
    }.onFailure { Log.w("KeyTones", "can't set", it) }.getOrDefault(false)
}

/**
 * How the projector is mounted, as XGIMI's "projection mode" page sets it: automatic (flips by
 * the tilt sensor), table or ceiling, plus rear projection. The put mode is 0 table, 1 ceiling,
 * +2 for rear. The tilt (level) is nudged in 0.5° steps like its rotation page.
 */
object Projection {
    const val AUTO = -1
    const val TABLE = 0
    const val CEILING = 1
    private const val REAR = 2

    private const val DISPLAY = "DisplayManager"
    private const val MOTION = "MotionDetectionManager"

    private fun putMode(): Int? = Gmpf.int(DISPLAY, "getProjectorPutMode")
    private fun setPutMode(mode: Int) { Gmpf.call(DISPLAY, "setProjectorPutMode", mode) }

    /** [AUTO], [TABLE] or [CEILING]; null when unavailable. */
    fun mount(): Int? {
        val auto = Gmpf.bool(MOTION, "getAutoReverse") ?: return null
        val mode = putMode() ?: return null
        return if (auto) AUTO else mode and 1
    }

    fun setMount(mount: Int) {
        Gmpf.call(MOTION, "setAutoReverse", mount == AUTO)
        if (mount == AUTO) return
        val rear = (putMode() ?: 0) and REAR
        setPutMode(mount or rear)
    }

    fun rear(): Boolean? = putMode()?.let { it and REAR != 0 }

    fun setRear(on: Boolean) {
        val mode = putMode() ?: return
        setPutMode(if (on) mode or REAR else mode and REAR.inv())
    }

    /** Nudges the picture's tilt half a degree clockwise (true) or back. */
    fun tilt(clockwise: Boolean) {
        Gmpf.call("SystemManager", "setScreenRotation", if (clockwise) 5 else 6, 0.5f)
    }
}

/**
 * Manual keystone and digital zoom (DisplayManager). Corners are DLP pixels on the 1920x1080 chip,
 * kept in a 9x9 grid of which 4-point mode uses [0][0] top-left, [0][1] top-right, [1][0]
 * bottom-left and [1][1] bottom-right. Zoom steps shrink the picture (0 = full size).
 */
object Keystone {
    const val WIDTH = 1920
    const val HEIGHT = 1080

    private const val DISPLAY = "DisplayManager"

    private val coordsClass by lazy { runCatching { Class.forName("com.xgimi.gmpf.rp.KeyStoneFullCoordinates") }.getOrNull() }

    /** The firmware's current correction, filled in; null if unavailable. */
    private fun readFull(): Any? {
        val cls = coordsClass ?: return null
        val full = cls.getConstructor().newInstance()
        return if (Gmpf.ok(DISPLAY, "getCorrectKeystone", full)) full else null
    }

    /** The four corners TL, TR, BL, BR of the 9x9 grid (see above) in [full]. */
    @Suppress("UNCHECKED_CAST")
    private fun cornerPoints(full: Any): List<Any> {
        val grid = coordsClass!!.getField("coordinates").get(full) as Array<Array<Any>>
        return listOf(grid[0][0], grid[0][1], grid[1][0], grid[1][1])
    }

    /** x0,y0 .. x3,y3 for TL, TR, BL, BR; null if unavailable. */
    fun corners(): List<Int>? = runCatching {
        val full = readFull() ?: return null
        cornerPoints(full).flatMap { p ->
            listOf((p.javaClass.getField("x").get(p) as Short).toInt(), (p.javaClass.getField("y").get(p) as Short).toInt())
        }
    }.onFailure { Log.w("Keystone", "read failed", it) }.getOrNull()

    /** [values] (x0,y0 .. x3,y3 for TL, TR, BL, BR) with every coordinate kept inside the 1920x1080 chip. */
    fun clamp(values: List<Int>): List<Int> =
        values.mapIndexed { i, v -> v.coerceIn(0, (if (i % 2 == 0) WIDTH else HEIGHT) - 1) }

    /**
     * Whether the corners make a proper four-sided picture: a convex quadrilateral with no
     * collapsed or crossed side (every turn around TL, TR, BR, BL goes the same way). A corner
     * dragged past its neighbour, or all four onto a line, would give an unwatchable picture.
     */
    fun isValid(values: List<Int>): Boolean {
        if (values.size != 8) return false
        val xs = intArrayOf(values[0], values[2], values[6], values[4])
        val ys = intArrayOf(values[1], values[3], values[7], values[5])
        var direction = 0
        for (i in 0..3) {
            val j = (i + 1) % 4
            val k = (i + 2) % 4
            val cross = (xs[j] - xs[i]).toLong() * (ys[k] - ys[j]) - (ys[j] - ys[i]).toLong() * (xs[k] - xs[j])
            val turn = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (turn == 0 || (direction != 0 && turn != direction)) return false
            direction = turn
        }
        return true
    }

    /** Clamps [values] and writes them; false if they aren't a valid picture ([isValid]) or the firmware refused. */
    fun setCorners(values: List<Int>): Boolean = runCatching {
        require(values.size == 8)
        val corners = clamp(values)
        if (!isValid(corners)) return false
        val full = readFull() ?: return false
        cornerPoints(full).forEachIndexed { i, p ->
            p.javaClass.getField("x").set(p, corners[i * 2].toShort())
            p.javaClass.getField("y").set(p, corners[i * 2 + 1].toShort())
        }
        Gmpf.ok(DISPLAY, "correctKeystone", full)
    }.onFailure { Log.w("Keystone", "write failed", it) }.getOrDefault(false)

    /** XGIMI's digital zoom range on this model (ZoomStepRange.zoomOutDigtalMaxNum). */
    const val MAX_ZOOM = 32

    /** The picture's size at the last zoom step, as a percentage of full size: about half. */
    const val MIN_SIZE_PERCENT = 50

    /** Rough size of the picture at zoom step [zoom] (0 = full size, [MAX_ZOOM] = [MIN_SIZE_PERCENT]). */
    fun sizePercent(zoom: Int): Int = 100 - (100 - MIN_SIZE_PERCENT) * zoom.coerceIn(0, MAX_ZOOM) / MAX_ZOOM

    /** The zoom step Beam last set; the firmware's getter doesn't report it back. */
    fun savedZoom(context: Context): Int = context.getSharedPreferences("keystone", Context.MODE_PRIVATE).getInt("zoom", 0)

    fun saveZoom(context: Context, step: Int) {
        context.getSharedPreferences("keystone", Context.MODE_PRIVATE).edit().putInt("zoom", step).apply()
    }

    fun zoom(): Int? = Gmpf.int(DISPLAY, "getCurrentZoomStep", 0)

    fun setZoom(step: Int): Boolean = Gmpf.ok(DISPLAY, "setDigitalZoomStep", step.coerceIn(0, MAX_ZOOM))
}
