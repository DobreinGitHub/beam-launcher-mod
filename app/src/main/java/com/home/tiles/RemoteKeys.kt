package com.home.tiles

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The remote's app keys: the ones XGIMI wires to Chinese video apps, Ambient and Source. They are
 * taken through XGIMI's own key service: an app registers a key code with it, and the firmware then
 * hands that app the press instead of doing its own action. A key nobody registers keeps the
 * firmware's action, which is what «Как в прошивке» means here.
 *
 * The protocol is the one tonisaf's remote-buttons module uses (MIT,
 * github.com/tonisaf/xgimi-play6-russifier): bind com.xgimi.xgimiservice/.services.XgimiServices
 * (IXgimiService); its call 1 returns IGimiCommon, whose call 3 registers a key code with a listener
 * (a reply of 0 means refused, the key held by another app) and call 4 releases it; the listener's
 * call 1 carries the key code that was pressed.
 *
 * Runs in Beam's process, which the accessibility service (PanelOverlay) keeps alive.
 */
object RemoteKeys {
    private const val TAG = "BeamKeys"
    private const val SERVICE = "com.xgimi.aidl.IXgimiService"
    private const val COMMON = "com.xgimi.aidl.IGimiCommon"
    private const val LISTENER = "com.xgimi.aidl.IGimiInterceptKeyListener"
    private const val RETRY_MS = 5_000L

    /** tonisaf's module: if installed, it holds the same keys and Beam's registrations are refused. */
    const val OLD_MODULE = "com.beam.remote"

    /** What the firmware does with a key nobody has taken. */
    sealed interface Stock {
        data class App(val pkg: String, val name: String) : Stock
        data object Atmosphere : Stock
        data object Source : Stock
    }

    class Key(val code: Int, val name: String, val stock: Stock)

    /** The two remotes sold with the Play 6, their app keys in the order they sit (two pills of two). */
    enum class Model(val keys: List<Key>) {
        // 极光TV | bilibili, Ambient | Source.
        Jiguang(
            listOf(
                Key(2119, "极光TV", Stock.App("com.ktcp.tvvideo", "极光TV")),
                Key(2126, "bilibili", Stock.App("com.xiaodianshi.tv.yst", "bilibili")),
                Key(2116, "Ambient", Stock.Atmosphere),
                Key(2115, "Source", Stock.Source),
            ),
        ),
        // Youku | Tencent, iQIYI | Mango.
        Classic(
            listOf(
                Key(2118, "Youku", Stock.App("com.cibn.tv", "Youku")),
                Key(2119, "Tencent", Stock.App("com.ktcp.tvvideo", "Tencent Video")),
                Key(2120, "iQIYI", Stock.App("com.gitvjimi.video", "iQIYI")),
                Key(2121, "Mango", Stock.App("com.hunantv.license", "Mango TV")),
            ),
        ),
    }

    // Actions: "" the firmware's own, or one of these, or "app:<package>".
    const val NONE = "none"
    const val PANEL = "panel"
    const val HOME = "home"
    const val PLAY_PAUSE = "media:play_pause"
    const val NEXT = "media:next"
    const val PREVIOUS = "media:previous"
    private const val APP = "app:"

    fun app(pkg: String) = APP + pkg
    fun packageOf(action: String) = action.removePrefix(APP).takeIf { action.startsWith(APP) }

    /** Action per key code; absent means the firmware's own. */
    val actions = mutableStateMapOf<Int, String>()
    var model by mutableStateOf(Model.Jiguang); private set
    /** The key last pressed while the page was open (its code), counted so the same key again counts. */
    var pressed by mutableStateOf(0 to 0); private set
    /** Keys the firmware refused to hand over (held by another app). */
    val refused = mutableStateMapOf<Int, Boolean>()

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: Handler
    private var connection: Connection? = null // main thread
    /** While the page is open every app key is taken, so a press points at its row instead of acting. */
    private var capturing = false // main thread
    private var lastKey = 0
    private var lastTime = 0L

    fun start(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        migrate()
        for ((name, value) in prefs.all) {
            val code = name.removePrefix("remoteKey").toIntOrNull() ?: continue
            if (name.startsWith("remoteKey") && value is String && value.isNotEmpty()) actions[code] = value
        }
        model = Model.entries.firstOrNull { it.name == prefs.getString("remoteModel", null) } ?: Model.Jiguang
        worker = Handler(HandlerThread("BeamKeys").apply { start() }.looper)
        connect()
    }

    /** Releases from the remote-button stubs of earlier releases: buttons 1–4 were keys 2118–2121. */
    private fun migrate() {
        if (prefs.getBoolean("remoteKeysMigrated", false)) return
        val edit = prefs.edit()
        for (i in 0..3) {
            val old = prefs.getString("button$i", "").orEmpty()
            if (old.isNotEmpty()) edit.putString("remoteKey${2118 + i}", old)
            edit.remove("button$i")
        }
        edit.putBoolean("remoteKeysMigrated", true).apply()
    }

    fun set(code: Int, action: String) {
        if (action.isEmpty()) actions.remove(code) else actions[code] = action
        prefs.edit().putString("remoteKey$code", action).apply()
        resync()
    }

    fun chooseModel(value: Model) {
        model = value
        prefs.edit().putString("remoteModel", value.name).apply()
    }

    /** The page is open (or closed): take every app key so presses point at their rows. */
    fun capture(on: Boolean) {
        if (!::appContext.isInitialized || capturing == on) return
        capturing = on
        resync()
    }

    private fun wanted(): Set<Int> =
        if (capturing) Model.entries.flatMap { it.keys }.map { it.code }.toSet() else actions.keys.toSet()

    private fun resync() {
        val current = connection ?: return
        val keys = wanted()
        worker.post { current.sync(keys) }
    }

    private fun connect() {
        if (connection != null) return
        val next = Connection()
        connection = next
        val bound = runCatching {
            appContext.bindService(
                Intent().setComponent(ComponentName("com.xgimi.xgimiservice", "com.xgimi.xgimiservice.services.XgimiServices")),
                next, Context.BIND_AUTO_CREATE,
            )
        }.getOrElse { Log.w(TAG, "bind", it); false }
        if (!bound) drop(next)
    }

    /** Forgets [current] and tries again a little later (XGIMI's service restarting, say). */
    private fun drop(current: Connection) {
        if (connection !== current) return
        connection = null
        current.live = false
        runCatching { appContext.unbindService(current) }
        worker.post { current.owned.clear() }
        main.postDelayed(::connect, RETRY_MS)
    }

    private fun onKey(code: Int) {
        if (capturing) {
            // A key only one remote has says which remote this is (极光TV and Tencent are the same key).
            Model.entries.singleOrNull { m -> m.keys.any { it.code == code } }?.let { chooseModel(it) }
            pressed = code to pressed.second + 1
            return
        }
        // The remote repeats a held key: one action per press.
        val now = SystemClock.elapsedRealtime()
        if (code == lastKey && now - lastTime < 400) return
        lastKey = code
        lastTime = now
        perform(actions[code] ?: return)
    }

    private fun perform(action: String) {
        Log.i(TAG, "action $action")
        runCatching {
            when {
                action == NONE -> {}
                action == PANEL -> if (!PanelOverlay.show()) {
                    PanelRequests.count.intValue++
                    appContext.startActivity(Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                action == HOME -> appContext.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                action.startsWith("media:") -> media(
                    when (action) {
                        PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                        else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    },
                )
                else -> packageOf(action)?.let { appContext.launchPackage(it) }
            }
        }.onFailure { Log.w(TAG, "action $action", it) }
    }

    private fun media(code: Int) {
        val audio = appContext.getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    private class Connection : ServiceConnection {
        @Volatile var live = true
        // Worker thread only from here on.
        var common: IBinder? = null
        val owned = HashSet<Int>()
        val death = IBinder.DeathRecipient { main.post { drop(this) } }
        val listener = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) {
                    reply?.writeString(LISTENER)
                    return true
                }
                if (code == 1) {
                    data.enforceInterface(LISTENER)
                    val key = data.readLong().toInt()
                    main.post { if (live) onKey(key) }
                    reply?.writeNoException()
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val keys = wanted()
            worker.post {
                try {
                    if (binder.interfaceDescriptor != SERVICE) error("unknown service ${binder.interfaceDescriptor}")
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(SERVICE)
                        if (!binder.transact(1, data, reply, 0)) error("getGimiCommon")
                        reply.readException()
                        common = reply.readStrongBinder()
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                    val common = common ?: error("no IGimiCommon")
                    if (common.interfaceDescriptor != COMMON) error("unknown common ${common.interfaceDescriptor}")
                    common.linkToDeath(death, 0)
                    sync(keys)
                } catch (e: Exception) {
                    Log.w(TAG, "connect", e)
                    main.post { drop(this) }
                }
            }
        }

        /** Takes exactly [wanted]: releases what is no longer wanted, registers what is new. */
        fun sync(wanted: Set<Int>) {
            val common = common ?: return
            try {
                for (key in owned.filter { it !in wanted }) {
                    transact(common, key, add = false)
                    owned.remove(key)
                }
                for (key in wanted.filter { it !in owned }) {
                    val granted = transact(common, key, add = true)
                    if (granted) owned.add(key)
                    main.post { if (granted) refused.remove(key) else refused[key] = true }
                }
            } catch (e: Exception) {
                Log.w(TAG, "sync", e)
                main.post { drop(this) }
            }
        }

        private fun transact(common: IBinder, key: Int, add: Boolean): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(COMMON)
                data.writeLong(key.toLong())
                if (add) data.writeStrongBinder(listener)
                if (!common.transact(if (add) 3 else 4, data, reply, 0)) error("transaction ${if (add) 3 else 4}")
                reply.readException()
                val granted = !add || reply.readInt() != 0
                Log.i(TAG, "${if (add) "register" else "release"} $key granted=$granted")
                return granted
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = drop(this)
        override fun onBindingDied(name: ComponentName) = drop(this)
        override fun onNullBinding(name: ComponentName) = drop(this)
    }
}
