package com.home.tiles

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

fun Context.launchApp(entry: AppEntry) = start(
    Intent(Intent.ACTION_MAIN)
        .setComponent(entry.component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
)

fun Context.launchPackage(pkg: String) {
    val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
        ?: packageManager.getLaunchIntentForPackage(pkg)
    if (intent == null) toast(tr(R.string.app_not_installed)) else start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun Context.isInstalled(pkg: String) = runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess

/** XGIMI has no settings activities: its settings UI and quick panel are overlays drawn by services. */
private fun Context.startXgimiPanel(action: String): Boolean = runCatching {
    startService(Intent(action).setPackage(XGIMI_SETTINGS)) != null
}.getOrDefault(false)

fun Context.openSettings() {
    if (!startXgimiPanel("com.xgimi.settings.SETTINGS")) {
        start(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Same panel as the gear key on the remote: network, Bluetooth, input source, focus. */
fun Context.openQuickPanel() {
    if (!startXgimiPanel("com.xgimi.misckey.MISCKEY")) openSettings()
}

private const val XGIMI_SETTINGS = "com.android.newsettings"

fun Context.openAppInfo(pkg: String) = start(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)

@Suppress("DEPRECATION")
fun Context.uninstall(pkg: String) = start(
    Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)

private fun Context.start(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: Exception) {
        toast(tr(R.string.cant_open))
    }
}

private fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
