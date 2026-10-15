package com.home.tiles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Beam's version and who made it, and its updates: what the last check found and when, one
 * button that checks, then downloads and installs, and the daily check's switch.
 */
@Composable
internal fun AboutBeamPage(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    remember { Updates.init(context) }
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 12.dp)) {
        version?.let { AboutRow(tr(R.string.version), it) }
        AboutRow(tr(R.string.made_by), "tonisaf")
        T(BEAM_REPO, 14.sp, Modifier.fillMaxWidth().padding(bottom = 5.dp), color = PanelDim, align = TextAlign.End)
        AboutRow(tr(R.string.modified_by), "Dobrein")
        T(BUILD_REPO, 14.sp, Modifier.fillMaxWidth().padding(bottom = 5.dp), color = PanelDim, align = TextAlign.End)
    }

    Section(tr(R.string.updates))
    val release = Updates.release
    val status = when {
        Updates.needsGrant -> tr(R.string.update_needs_grant)
        Updates.progress >= 0 -> tr(R.string.update_downloading, Updates.progress)
        Updates.busy -> tr(R.string.update_checking)
        Updates.error -> tr(R.string.update_failed)
        Updates.ready != null && release != null -> tr(R.string.update_ready, release.tag.removePrefix("v"))
        release != null -> tr(R.string.update_available, release.tag.removePrefix("v"))
        Updates.checkedAt > 0 -> tr(R.string.update_latest)
        else -> tr(R.string.update_never)
    }
    T(status, 16.sp, color = if (release != null) ScrimText else ScrimDim, maxLines = 4)
    if (Updates.checkedAt > 0) {
        val format = remember(AppLanguage.locale) { SimpleDateFormat("d MMMM, HH:mm", AppLanguage.locale) }
        T(tr(R.string.update_checked_at, format.format(Date(Updates.checkedAt))), 14.sp, color = ScrimDim)
    }
    // What's new in the release found, briefly.
    release?.notes?.lineSequence()?.map { it.trim().removePrefix("- ").removePrefix("* ") }
        ?.filter { it.isNotEmpty() && !it.startsWith("#") }?.take(3)?.toList()?.takeIf { it.isNotEmpty() }?.let { lines ->
            Spacer(Modifier.height(6.dp))
            lines.forEach { T("· $it", 14.sp, color = ScrimDim, maxLines = 2) }
        }
    Spacer(Modifier.height(12.dp))
    val action = when {
        Updates.ready != null -> tr(R.string.update_install)
        release != null -> tr(R.string.update_download)
        else -> tr(R.string.update_check)
    }
    ListRow(action) { if (!Updates.busy) Updates.act(context, onDismiss) }
    var auto by remember { mutableStateOf(Updates.autoEnabled(context)) }
    Toggle(tr(R.string.update_auto), auto, Modifier.fillMaxWidth()) {
        auto = !auto
        Updates.setAuto(context, auto)
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        T(label, 16.sp, color = PanelDim)
        Spacer(Modifier.weight(1f))
        T(value, 16.sp, color = PanelText)
    }
}

private const val BEAM_REPO = "github.com/tonisaf/beam-launcher"
private const val BUILD_REPO = "github.com/DobreinGitHub/beam-launcher-mod"

/**
 * The screensaver: which one runs (XGIMI's own or an installed one like Aerial Views),
 * its own settings, and how long the projector waits before starting it.
 */
@Composable
internal fun ScreensaverPage(onSetup: (android.content.ComponentName) -> Unit) {
    val context = LocalContext.current
    // Read at once (a quick package query): read later, the page had already put the selection
    // on the timeout below, the only control there was yet.
    val choices = remember { Screensavers.installed(context) }
    var current by remember { mutableStateOf(Screensavers.current(context)) }
    if (choices.isNotEmpty()) {
        Section(tr(R.string.screensaver))
        val index = choices.indexOfFirst { it.component == current }.coerceAtLeast(0)
        Selector(tr(R.string.screensaver_which), choices[index].label, Modifier.fillMaxWidth()) { step ->
            val next = choices[(index + step).mod(choices.size)]
            if (Screensavers.set(context, next.component)) current = Screensavers.current(context)
        }
        Spacer(Modifier.height(10.dp))
        ListRow(tr(R.string.screensaver_setup)) { onSetup(choices[index].component) }
    }
    var timeout by remember { mutableStateOf(ScreensaverTimeout.current(context)) }
    val options = ScreensaverTimeout.options
    Section(tr(R.string.start_after))
    if (ScreensaverTimeout.canWrite(context)) {
        // Unknown values (set elsewhere) show as the nearest longer option.
        val index = options.indices.filter { options[it].first >= timeout }.minBy { options[it].first }
        Selector(tr(R.string.idle), options[index].second, Modifier.fillMaxWidth()) { step ->
            val next = options[(index + step).coerceIn(0, options.lastIndex)].first
            if (ScreensaverTimeout.set(context, next)) timeout = ScreensaverTimeout.current(context)
        }
    } else {
        T(tr(R.string.no_write_settings), 14.sp, color = ScrimDim, maxLines = 3)
    }
}
