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

/**
 * Idle time before XGIMI's screensaver starts: the plain screen_off_timeout, with the values its
 * settings page offers ("never" is Int.MAX_VALUE). Writing it needs the WRITE_SETTINGS app op,
 * granted over adb (tools/restore.ps1).
 */
object ScreensaverTimeout {
    /** In ascending order, so ← → in the panel is shorter / longer; "never" is the longest. */
    val options get() = listOf(
        60_000 to tr(R.string.min_1),
        300_000 to tr(R.string.min_5),
        600_000 to tr(R.string.min_10),
        1_800_000 to tr(R.string.min_30),
        3_600_000 to tr(R.string.hour_1),
        Int.MAX_VALUE to tr(R.string.never),
    )

    fun current(context: Context): Int =
        android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 300_000)

    fun canWrite(context: Context) = android.provider.Settings.System.canWrite(context)

    fun set(context: Context, value: Int): Boolean = runCatching {
        android.provider.Settings.System.putInt(context.contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, value)
    }.onFailure { Log.w("Screensaver", "can't set timeout", it) }.getOrDefault(false)
}

/**
 * Which screensaver Android runs: the installed ones (Aerial Views...) and XGIMI's own. Written
 * to the secure setting the system reads, which the WRITE_SECURE_SETTINGS grant allows.
 */
object Screensavers {
    private const val KEY = "screensaver_components"

    /** XGIMI's default: it opens the "Any Door" scenes. */
    val XGIMI = android.content.ComponentName("com.xgimi.screensaver", "com.xgimi.screensaver.service.ScreenSaverDreamService")

    class Choice(val component: android.content.ComponentName, val label: String)

    /** XGIMI's other dream service (the scenes themselves) is left out: it is what its default opens. */
    fun installed(context: Context): List<Choice> {
        val pm = context.packageManager
        return pm.queryIntentServices(android.content.Intent(android.service.dreams.DreamService.SERVICE_INTERFACE), 0)
            .map { android.content.ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) to it }
            .filter { (component, _) -> !component.packageName.startsWith("com.xgimi.") || component == XGIMI }
            .map { (component, info) ->
                Choice(component, if (component == XGIMI) tr(R.string.screensaver_xgimi) else info.loadLabel(pm).toString())
            }
    }

    fun current(context: Context): android.content.ComponentName? =
        android.provider.Settings.Secure.getString(context.contentResolver, KEY)
            ?.split(',')?.firstOrNull()?.let(android.content.ComponentName::unflattenFromString)

    fun set(context: Context, component: android.content.ComponentName): Boolean = runCatching {
        val resolver = context.contentResolver
        android.provider.Settings.Secure.putString(resolver, KEY, component.flattenToString()) &&
            android.provider.Settings.Secure.putInt(resolver, "screensaver_enabled", 1)
    }.onFailure { Log.w("Screensaver", "can't choose the screensaver", it) }.getOrDefault(false)
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
