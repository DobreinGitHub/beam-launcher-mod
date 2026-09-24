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

    /**
     * Picks the output the way XGIMI's page does (VoiceHelper.setAudioDevice): through the audio
     * service in com.xgimi.api.XgimiAudioManager, which also moves Android's routing. The plain
     * GmAudioManager.setAudioOutput only switches the amplifier, so a Bluetooth speaker kept playing.
     */
    /**
     * XgimiAudioManager talks to com.xgimi.xgimiservice, which the library binds only after
     * XgimiAidlServiceManager.init(context, listener), as XGIMI's settings do on start. Call this
     * ahead of [setOutput]; the binding is asynchronous.
     */
    fun prepare(context: Context) {
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
                    "toString" -> "SoundOutput.bind"
                    else -> {
                        Log.i("SoundOutput", "XGIMI service ${method.name}")
                        null
                    }
                }
            }
            cls.getMethod("init", Context::class.java, listener).invoke(instance, context.applicationContext, callback)
        }.onFailure {
            bindRequested = false
            Log.w("SoundOutput", "Could not bind XGIMI service", it)
        }
    }

    @Volatile
    private var bindRequested = false

    fun setOutput(device: Int) {
        val routed = runCatching {
            val cls = Class.forName("com.xgimi.api.XgimiAudioManager")
            val xam = cls.getMethod("getInstance").invoke(null)
            cls.getMethod("setAudioDeviceOn", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(xam, device, 0)
        }.onFailure { Log.w("SoundOutput", "setAudioDeviceOn failed", it) }.isSuccess
        if (!routed) call("setAudioOutput", device.toByte())
    }
}
