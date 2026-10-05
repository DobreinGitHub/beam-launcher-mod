package com.home.tiles

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * Projector functions. The remote's focus key ends up as an AF_AK command to XGIMI SystemUI
 * (via com.xgimi.windowsystem), so we send the same command directly.
 * Types come from FocusUIV2.receiveIntent in the firmware's SystemUI.
 */
object Xgimi {
    private const val TYPE_MANUAL_FOCUS = 1
    private const val TYPE_AUTO_FOCUS = 8
    private const val TYPE_AUTO_KEYSTONE = 10

    fun autoFocus(context: Context) = focusCommand(context, TYPE_AUTO_FOCUS)

    /**
     * Auto keystone the way XGIMI's own settings start it: ProjectorFocusManager.newAutoKst(9)
     * from the com.xgimi.api library (the AF_AK type 10 command reaches SystemUI but no longer
     * starts the ToF measurement on this firmware). Falls back to the SystemUI command.
     */
    fun autoKeystone(context: Context) {
        // It starts from a full-size picture, so put the size back (and Beam's note of it) rather
        // than assume the measurement does: the size setting would otherwise show 100% over a
        // shrunk picture.
        if (Keystone.setZoom(0)) Keystone.saveZoom(context, 0)
        val started = runCatching {
            val cls = Class.forName("com.xgimi.gmpf.api.ProjectorFocusManager")
            val pfm = cls.getMethod("getInstance").invoke(null)
            cls.getMethod("newAutoKst", Int::class.javaPrimitiveType).invoke(pfm, AUTO_KST_SETTINGS)
        }.isSuccess
        if (!started) focusCommand(context, TYPE_AUTO_KEYSTONE)
    }

    private const val AUTO_KST_SETTINGS = 9

    /** Opens XGIMI's manual focus overlay, driven with the remote's arrows. */
    fun manualFocus(context: Context) = focusCommand(context, TYPE_MANUAL_FOCUS)

    private fun focusCommand(context: Context, type: Int) = startService(
        context,
        Intent("com.xgimi.systemui.action.AF_AK")
            .setPackage("com.xgimi.systemui")
            .putExtra("type", type)
            .putExtra("from", context.packageName),
    )

    /** Same menu as the power key: screen off, power off, restart, sleep timer. */
    fun powerMenu(context: Context) =
        startService(context, Intent("com.xgimi.action.WINODWSYSTEM").setPackage("com.xgimi.systemui"))

    /**
     * The settings app's ActionService takes a page route in "data" (Settings://...), or the
     * "changePictureMode" command with a GmTvManager picture mode number.
     */
    fun openSettingsPage(context: Context, route: String) = startService(
        context,
        Intent("com.xgimi.settings.SETTINGS").setPackage(SETTINGS_PKG).putExtra("data", route),
    )

    /**
     * Directly through XGIMI's picture service when it's bound; otherwise via the settings app's
     * command, which also leaves XGIMI's picture page open behind whatever is on screen.
     */
    fun setPictureMode(context: Context, mode: Int) {
        XgimiService.bind(context)
        if (PictureMode.set(mode)) return
        startService(
            context,
            Intent("com.xgimi.settings.SETTINGS").setPackage(SETTINGS_PKG)
                .putExtra("data", "changePictureMode")
                .putExtra("pictureModeValue", mode),
        )
    }

    /** Picture modes this model lists, in XGIMI's order, with the numbers its settings app sends. */
    val pictureModes get() = listOf(
        tr(R.string.pm_ai) to 16,
        tr(R.string.pm_movie) to 1,
        tr(R.string.pm_sport) to 9,
        tr(R.string.pm_tv) to 7,
        tr(R.string.pm_custom) to 3,
        tr(R.string.pm_office) to 25,
        tr(R.string.pm_performance) to PICTURE_PERFORMANCE,
    )

    /** Brightest mode: drives the light source harder; XGIMI warns about heat before enabling it. */
    const val PICTURE_PERFORMANCE = 5

    /** XGIMI's picture mode page (the list with the AI picture settings behind "AI ›"). */
    const val PAGE_PICTURE = "Settings://com.xgimi.settings.image/mode"

    const val PAGE_SOUND_OUTPUT = "Settings://com.xgimi.settings.sound/soundOutput"
    const val PAGE_BLUETOOTH = "Settings://com.xgimi.settings.bluetooth"
    const val PAGE_ZOOM = "Settings://com.xgimi.settings.picture/zoom_displacement"
    const val PAGE_WIFI = "Settings://com.xgimi.settings.net/wifi"
    const val PAGE_KEYSTONE = "Settings://com.xgimi.settings.picture/keyStone"
    const val PAGE_ROTATE = "Settings://com.xgimi.settings.picture/rotate"
    /** HDMI source page: CEC, boot source, plug-and-play. */
    const val PAGE_HDMI = "Settings://com.xgimi.settings.signalSource/"
    /** Full settings on "Picture correction": focus, reset picture, correction settings. */
    const val PAGE_CORRECTION = "Settings://com.xgimi.settings/projection_screen"

    /** "Any Door" (任意门): XGIMI's ambient scenes, also used as the screensaver. */
    const val SCREENSAVER_APP = "com.xgimi.atmosphere"

    private const val SETTINGS_PKG = "com.android.newsettings"

    /**
     * Starts one of XGIMI's services. When it can't be started, the message says why (and the log
     * has the details): no such service here, Android refusing a background start, or no access.
     */
    private fun startService(context: Context, intent: Intent) {
        val problem = try {
            if (context.startService(intent) != null) null else tr(R.string.unavailable_here)
        } catch (e: IllegalStateException) {
            // Android 8+: an app that isn't in the foreground may not start services.
            Log.w(TAG, "${intent.action}: background start refused", e)
            tr(R.string.cant_now_background)
        } catch (e: SecurityException) {
            Log.w(TAG, "${intent.action}: no access", e)
            tr(R.string.no_xgimi_service)
        } catch (e: Exception) {
            Log.w(TAG, "${intent.action} failed", e)
            tr(R.string.command_failed)
        }
        if (problem != null) toast(context, problem)
    }

    /** A toast from any thread: it needs the main thread's looper. */
    private fun toast(context: Context, text: String) {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
    }

    private const val TAG = "Xgimi"

    /** [device]: the CEC name of what is plugged in, when it gave one. */
    class Input(val label: String, val id: String, val device: String? = null)

    /**
     * One entry per HDMI port. A device that introduces itself over HDMI-CEC (e.g. a console) shows
     * up as a second input whose parent is the port; it names the port's entry instead of doubling it.
     */
    fun hdmiInputs(context: Context): List<Input> {
        val tv = context.getSystemService(TvInputManager::class.java) ?: return emptyList()
        val hdmi = tv.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI }
        val ports = hdmi.filter { it.parentId == null }
        return ports.mapIndexed { i, port ->
            val device = hdmi.firstOrNull { it.parentId == port.id }?.loadLabel(context)?.toString()?.takeIf { it.isNotBlank() }
            val fallback = if (ports.size == 1) "HDMI" else "HDMI ${i + 1}"
            Input(device ?: fallback, port.id, device)
        }
    }

    /**
     * Through XGIMI's HDMI player, as its own source list does. A plain ACTION_VIEW of the same
     * URI resolves to the stock AOSP Live TV app, which shows only a black screen here.
     */
    fun openInput(context: Context, input: Input) {
        val uri = TvContract.buildChannelUriForPassthroughInput(input.id)
        val xgimi = Intent(HDMI_PLAYER_ACTION, uri).setPackage(HDMI_PLAYER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val plain = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(xgimi) }
            .recoverCatching { context.startActivity(plain) }
            .onFailure {
                Log.w(TAG, "Could not open HDMI input ${input.id}", it)
                toast(context, tr(R.string.input_switch_failed))
            }
    }

    private const val HDMI_PLAYER = "com.xgimi.xhplayer"
    private const val HDMI_PLAYER_ACTION = "com.xgimi.action.hdmiPlayer"
}
