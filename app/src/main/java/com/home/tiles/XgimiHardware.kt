package com.home.tiles

import android.content.Context
import android.util.Log
import java.lang.reflect.Proxy

/**
 * The projector's light-source brightness, as XGIMI's own settings drive it: level 0..10 through
 * com.xgimi.gmpf.api.DisplayManager in the com.xgimi.api platform library (reflection, because it
 * only exists on XGIMI firmware). Null/false when unavailable.
 */
object Lumens {
    const val MAX = 10

    private val manager: Pair<Class<*>, Any>? by lazy {
        runCatching {
            val cls = Class.forName("com.xgimi.gmpf.api.DisplayManager")
            cls to cls.getMethod("getInstance").invoke(null)!!
        }.onFailure { Log.w("Lumens", "DisplayManager unavailable", it) }.getOrNull()
    }

    private fun call(name: String, vararg args: Any): Any? {
        val (cls, dm) = manager ?: return null
        return runCatching {
            val method = cls.methods.first { it.name == name && it.parameterTypes.size == args.size }
            method.invoke(dm, *args)
        }.onFailure { Log.w("Lumens", "$name failed", it) }.getOrNull()
    }

    fun level(): Int? = (call("getDlpLumensLevel") as? Number)?.toInt()

    fun mode(): Int? = (call("getDlpLumensMode") as? Number)?.toInt()

    fun setLevel(level: Int): Boolean {
        if (manager == null) return false
        call("setDlpLumensLevel", level.coerceIn(0, MAX).toByte())
        return true
    }
}

/**
 * The current picture mode, as a number [Xgimi.setPictureMode] takes. GmTvManager reports the
 * mode of the current input; it numbers AI picture 10 where the settings app sends 16.
 */
object PictureMode {
    fun current(): Int? = runCatching {
        val cls = Class.forName("com.xgimi.gmpf.api.GmTvManager")
        val tv = cls.getMethod("getInstance").invoke(null)
        val source = cls.getMethod("getCurrentInputSource").invoke(tv) as Int
        val mode = cls.getMethod("getPictureMode", Int::class.javaPrimitiveType).invoke(tv, source) as Int
        if (mode == 10) 16 else mode
    }.onFailure { Log.w("PictureMode", "Could not read picture mode", it) }.getOrNull()

    /**
     * Switches the mode the way XGIMI's settings app does internally (MstPictureManager, same
     * numbers). Needs [XgimiService.bind]; false if the service isn't bound yet.
     */
    fun set(mode: Int): Boolean = runCatching {
        val cls = Class.forName("com.xgimi.video.MstPictureManager")
        val pm = cls.getMethod("getInstance").invoke(null)
        cls.getMethod("setPictureMode", Int::class.javaPrimitiveType).invoke(pm, mode)
    }.onFailure { Log.w("PictureMode", "setPictureMode failed", it) }.isSuccess
}

/** XGIMI eco mode (dimmer, quieter), via com.xgimi.gmpf.api.SystemManager like the stock panel. */
object Eco {
    private val manager: Pair<Class<*>, Any>? by lazy {
        runCatching {
            val cls = Class.forName("com.xgimi.gmpf.api.SystemManager")
            cls to cls.getMethod("getInstance").invoke(null)!!
        }.onFailure { Log.w("Eco", "SystemManager unavailable", it) }.getOrNull()
    }

    fun enabled(): Boolean? = manager?.let { (cls, sm) ->
        runCatching { cls.getMethod("getEcoState").invoke(sm) as Boolean }.getOrNull()
    }

    fun set(on: Boolean): Boolean = manager?.let { (cls, sm) ->
        runCatching { cls.getMethod("setEcoState", Boolean::class.javaPrimitiveType).invoke(sm, on) }.isSuccess
    } ?: false
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

    private val manager: Pair<Class<*>, Any>? by lazy {
        runCatching {
            val cls = Class.forName("com.xgimi.gmpf.api.GmAudioManager")
            cls to cls.getMethod("getInstance").invoke(null)!!
        }.onFailure { Log.w("SoundOutput", "GmAudioManager unavailable", it) }.getOrNull()
    }

    private fun call(name: String, vararg args: Any): Any? {
        val (cls, am) = manager ?: return null
        return runCatching {
            cls.methods.first { it.name == name && it.parameterTypes.size == args.size }.invoke(am, *args)
        }.onFailure { Log.w("SoundOutput", "$name failed", it) }.getOrNull()
    }

    val available get() = manager != null

    fun auto(): Boolean? = (call("getAudioDeviceSwitchMode") as? Number)?.let { it.toInt() == 0 }

    fun output(): Int? = (call("getAudioOutput") as? Number)?.toInt()

    fun connected(device: Int): Boolean = call("isAudioDeviceConnected", device.toByte()) == true

    fun setAuto(on: Boolean) {
        call("setAudioDeviceSwitchMode", (if (on) 0 else 1).toByte())
    }

    /** Binds XGIMI's service, which [setOutput] needs; see [XgimiService.bind]. */
    fun prepare(context: Context) = XgimiService.bind(context)

    /**
     * Picks the output the way XGIMI's page does (VoiceHelper.setAudioDevice): through the audio
     * service in com.xgimi.api.XgimiAudioManager, which also moves Android's routing. The plain
     * GmAudioManager.setAudioOutput only switches the amplifier, so a Bluetooth speaker kept playing.
     */
    fun setOutput(device: Int) {
        val routed = runCatching {
            val cls = Class.forName("com.xgimi.api.XgimiAudioManager")
            val xam = cls.getMethod("getInstance").invoke(null)
            cls.getMethod("setAudioDeviceOn", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(xam, device, 0)
        }.onFailure { Log.w("SoundOutput", "setAudioDeviceOn failed", it) }.isSuccess
        if (!routed) call("setAudioOutput", device.toByte())
    }
}

/**
 * Parts of com.xgimi.api (XgimiAudioManager, MstPictureManager) talk to com.xgimi.xgimiservice,
 * which the library binds only after XgimiAidlServiceManager.init(context, listener), as XGIMI's
 * settings do on start. Call [bind] ahead of using them; the binding is asynchronous.
 */
object XgimiService {
    @Volatile
    private var bindRequested = false

    fun bind(context: Context) {
        if (bindRequested) return
        bindRequested = true
        runCatching {
            val cls = Class.forName("com.xgimi.clients.XgimiAidlServiceManager")
            val instance = cls.getField("INSTANCE").get(null)
            val listener = Class.forName("com.xgimi.clients.XgimiAidlServiceManager\$IAidlConnectListener")
            val callback = Proxy.newProxyInstance(listener.classLoader, arrayOf(listener)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "XgimiService.bind"
                    else -> {
                        Log.i("XgimiService", "XGIMI service ${method.name}")
                        null
                    }
                }
            }
            cls.getMethod("init", Context::class.java, listener).invoke(instance, context.applicationContext, callback)
        }.onFailure {
            bindRequested = false
            Log.w("XgimiService", "Could not bind XGIMI service", it)
        }
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

    private fun manager(): Pair<Class<*>, Any>? = runCatching {
        val cls = Class.forName("com.xgimi.video.MstPictureManager")
        cls to cls.getMethod("getInstance").invoke(null)!!
    }.onFailure { Log.w("PictureAdjust", "MstPictureManager unavailable", it) }.getOrNull()

    fun get(item: Int): Int? = manager()?.let { (cls, pm) ->
        runCatching { cls.getMethod("getPictureItem", Int::class.javaPrimitiveType).invoke(pm, item) as Int }.getOrNull()
    }

    fun set(item: Int, value: Int) {
        manager()?.let { (cls, pm) ->
            runCatching {
                cls.getMethod("setPictureItem", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(pm, item, value)
            }.onFailure { Log.w("PictureAdjust", "setPictureItem failed", it) }
        }
    }

    private fun mst(name: String, vararg args: Any): Any? = manager()?.let { (cls, pm) ->
        runCatching { cls.methods.first { it.name == name && it.parameterTypes.size == args.size }.invoke(pm, *args) }
            .onFailure { Log.w("PictureAdjust", "$name failed", it) }.getOrNull()
    }

    /** GmTvManager calls that take the input first, like XGIMI's picture page makes them. */
    private fun tv(name: String, vararg args: Any): Any? = runCatching {
        val cls = Class.forName("com.xgimi.gmpf.api.GmTvManager")
        val tv = cls.getMethod("getInstance").invoke(null)
        val source = cls.getMethod("getCurrentInputSource").invoke(tv) as Int
        val all = arrayOf<Any>(source, *args)
        cls.methods.first { it.name == name && it.parameterTypes.size == all.size }.invoke(tv, *all)
    }.onFailure { Log.w("PictureAdjust", "$name failed", it) }.getOrNull()

    /** Noise reduction: 0 off, 1 low, 2 medium, 3 high, 4 auto. */
    fun noiseReduction(): Int? = mst("getNoiseReduction") as? Int
    fun setNoiseReduction(level: Int) { mst("setNoiseReduction", level) }

    /** Motion compensation (MEMC): 0 off, 1 low, 2 medium, 3 high. */
    fun motion(): Int? = mst("getMfcLevel") as? Int
    fun setMotion(level: Int) { mst("setMfcLevel", level) }

    /** Gamma index: 0 = 1.8 ... 4 = 2.2 ... 8 = 2.6. */
    fun gamma(): Int? = tv("getTvGammaLevel") as? Int
    fun setGamma(level: Int) { tv("setTvGammaLevel", level) }

    fun dynamicContrast(): Boolean? = tv("getTvDynamicContrastEnable") as? Boolean
    fun setDynamicContrast(on: Boolean) { tv("setTvDynamicContrastEnable", on) }

    /** Local contrast: 0 off, 1 low, 2 medium, 3 high. */
    fun localContrast(): Int? = tv("getUcdLevel") as? Int
    fun setLocalContrast(level: Int) { tv("setUcdLevel", level) }

    fun hdr(): Boolean? = runCatching {
        val cls = Class.forName("com.xgimi.gmpf.api.GmTvManager")
        cls.getMethod("getHdrEnable").invoke(cls.getMethod("getInstance").invoke(null)) as Boolean
    }.getOrNull()

    fun setHdr(on: Boolean) {
        runCatching {
            val cls = Class.forName("com.xgimi.gmpf.api.GmTvManager")
            cls.getMethod("setHdrEnable", Boolean::class.javaPrimitiveType).invoke(cls.getMethod("getInstance").invoke(null), on)
        }.onFailure { Log.w("PictureAdjust", "setHdrEnable failed", it) }
    }

    /** 0 cool, 1 natural, 2 warm (MstPictureManager.COLOR_TEMP_*). */
    fun colorTemp(): Int? = manager()?.let { (cls, pm) ->
        runCatching { cls.getMethod("getColorTemp").invoke(pm) as Int }.getOrNull()
    }

    fun setColorTemp(value: Int) {
        manager()?.let { (cls, pm) ->
            runCatching { cls.getMethod("setColorTemp", Int::class.javaPrimitiveType).invoke(pm, value) }
                .onFailure { Log.w("PictureAdjust", "setColorTemp failed", it) }
        }
    }
}

