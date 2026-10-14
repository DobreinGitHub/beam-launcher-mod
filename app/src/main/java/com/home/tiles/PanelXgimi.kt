package com.home.tiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

/** Beam's version and who made it: the original author and this build's modifications. */
@Composable
internal fun AboutBeamPage() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 12.dp)) {
        version?.let { AboutRow(tr(R.string.version), it) }
        AboutRow(tr(R.string.made_by), "tonisaf")
        T(BEAM_REPO, 14.sp, Modifier.fillMaxWidth().padding(bottom = 5.dp), color = PanelDim, align = TextAlign.End)
        AboutRow(tr(R.string.modified_by), "Dobrein")
        T(BUILD_REPO, 14.sp, Modifier.fillMaxWidth().padding(bottom = 5.dp), color = PanelDim, align = TextAlign.End)
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        T(label, 15.sp, color = PanelDim)
        Spacer(Modifier.weight(1f))
        T(value, 15.sp, color = PanelText)
    }
}

private const val BEAM_REPO = "github.com/tonisaf/beam-launcher"
private const val BUILD_REPO = "github.com/DobreinGitHub/beam-launcher-mod"

/**
 * The screensaver: which one runs (XGIMI Atmosphere or an installed one like Aerial Views),
 * its own settings, and how long the projector waits before starting it.
 */
@Composable
internal fun ScreensaverPage(onSetup: (android.content.ComponentName) -> Unit) {
    val context = LocalContext.current
    val choices by produceState(emptyList<Screensavers.Choice>()) {
        value = withContext(Dispatchers.IO) { Screensavers.installed(context) }
    }
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
        T(tr(R.string.no_write_settings), 14.sp, color = PanelDim, maxLines = 3)
    }
}
