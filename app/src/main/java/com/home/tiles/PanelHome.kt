package com.home.tiles

import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AppearancePage(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Like XGIMI's «Стиль темы» by default (where the firmware has it), or light or dark here.
    val themes = buildList {
        if (LauncherSettings.xgimiDark != null) add(tr(R.string.theme_xgimi))
        add(tr(R.string.theme_light))
        add(tr(R.string.theme_dark))
    }
    val theme = when {
        LauncherSettings.followXgimi -> 0
        LauncherSettings.dark -> themes.lastIndex
        else -> themes.lastIndex - 1
    }
    // OK opens XGIMI's own «Стиль темы» list (and follows it again), in this page's place.
    val openXgimiTheme: (() -> Unit)? = if (LauncherSettings.xgimiDark == null) null else ({
        LauncherSettings.followXgimi = true
        onDismiss()
        context.openXgimiPage(XGIMI_THEME_PAGE)
    })
    Selector(tr(R.string.theme), themes[theme], Modifier.fillMaxWidth(), onClick = openXgimiTheme) { step ->
        val next = (theme + step).mod(themes.size)
        when (next) {
            themes.lastIndex -> LauncherSettings.dark = true
            themes.lastIndex - 1 -> LauncherSettings.dark = false
            else -> LauncherSettings.followXgimi = true
        }
    }
    Section(tr(R.string.background))
    BackgroundCards(Backgrounds.indices.filter { !Backgrounds[it].xmb })
    Spacer(Modifier.height(ControlGap))
    XmbCard()
    Spacer(Modifier.height(ControlGap))
    Toggle(
        tr(R.string.ambient_tint),
        LauncherSettings.ambientTint,
        Modifier.fillMaxWidth(),
        subtitle = tr(R.string.ambient_tint_hint),
    ) { LauncherSettings.ambientTint = !LauncherSettings.ambientTint }
    Section(tr(R.string.interface_section))
    // Each language is named in itself, so it can be found whatever the interface is set to.
    val languages = listOf(AppLanguage.SYSTEM to tr(R.string.language_system), AppLanguage.RU to "Русский", AppLanguage.EN to "English")
    val language = languages.indexOfFirst { it.first == AppLanguage.current }.coerceAtLeast(0)
    Selector(tr(R.string.language), languages[language].second, Modifier.fillMaxWidth()) { step ->
        AppLanguage.current = languages[(language + step).mod(languages.size)].first
    }
    Spacer(Modifier.height(ControlGap))
    // XGIMI's «Звук нажатия кнопок» itself: the same setting, so the two switches agree.
    var keyTones by remember { mutableStateOf(Sounds.keyTones()) }
    Toggle(tr(R.string.nav_sounds), keyTones, Modifier.fillMaxWidth(), subtitle = tr(R.string.nav_sounds_xgimi)) {
        if (Sounds.setKeyTones(!keyTones)) keyTones = !keyTones
    }
}

/**
 * The gradients as cards in a row, each with its name. The one the selection rests
 * on is the background at once, no OK: the home screen beside the panel shows it full size.
 * Coming into the row, the selection lands on the current one.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BackgroundCards(gradients: List<Int>) {
    val requesters = remember(gradients) { gradients.associateWith { FocusRequester() } }
    Row(
        Modifier
            .fillMaxWidth()
            .focusProperties { enter = { requesters[LauncherSettings.background] ?: FocusRequester.Default } }
            .focusGroup()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        gradients.forEach { i ->
            BackgroundCard(i, LauncherSettings.background == i, Modifier.focusRequester(requesters.getValue(i)))
        }
    }
}

@Composable
private fun BackgroundCard(index: Int, chosen: Boolean, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier
                .size(96.dp, 54.dp)
                .panelControl({ on -> focused = on; if (on) LauncherSettings.background = index }) { LauncherSettings.background = index }
                .clip(shape)
                .background(Colors.presetBrush(Backgrounds[index]))
                .border(
                    if (focused) 3.dp else if (chosen) 2.dp else 1.dp,
                    if (focused) FocusBg else if (chosen) Accent else Color(0x33FFFFFF),
                    shape,
                ),
        )
        Spacer(Modifier.height(6.dp))
        T(Backgrounds[index].name, 14.sp, color = if (focused || chosen) ScrimText else ScrimDim)
    }
}

/** Between controls in a section; Section puts its own gaps around a section's name. */
private val ControlGap = 10.dp

/**
 * XMB apart from the gradients: an animated background of another kind, as a card of its own
 * with a small picture of its waves. Chosen, its options open inside the card: the colour (by
 * month like the PS3, or fixed) and the animation switch.
 */
@Composable
private fun XmbCard() {
    val index = Backgrounds.indexOfFirst { it.xmb }
    val chosen = LauncherSettings.background == index
    var focused by remember { mutableStateOf(false) }
    val fg = if (focused) FocusText else PanelText
    Column(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(16.dp))) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (focused) FocusBg else Color.Transparent, RoundedCornerShape(16.dp))
                .panelControl({ focused = it }) { LauncherSettings.background = index }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The waves drawn still: the real ones move behind the page anyway once chosen.
            Box(
                Modifier
                    .size(72.dp, 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Colors.presetBrush(Backgrounds[index]))
                    .drawBehind {
                        listOf(0.45f to 0.55f, 0.6f to 0.3f).forEach { (y, a) ->
                            val path = Path()
                            val steps = 24
                            for (s in 0..steps) {
                                val x = size.width * s / steps
                                val wy = size.height * (y + 0.12f * kotlin.math.sin(s / steps.toFloat() * 2f * Math.PI.toFloat() + y * 6f))
                                if (s == 0) path.moveTo(x, wy) else path.lineTo(x, wy)
                            }
                            drawPath(path, Color.White.copy(alpha = a), style = Stroke(1.5.dp.toPx()))
                        }
                    },
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                T(Backgrounds[index].name, 16.sp, color = fg)
                T("PlayStation 3", 14.sp, color = if (focused) FocusText.copy(alpha = 0.7f) else PanelDim)
            }
            if (chosen) Image(Icons.Rounded.Check, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(if (focused) FocusText else Accent))
        }
        if (chosen) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                XmbOptions()
            }
        }
    }
}

/**
 * The home screen's section: the now-playing bar, and the channel rows, every channel the apps
 * publish grouped under its app, with a few of its posters, so «Популярное» in one app can be
 * told from «Популярное» in another. A switch each; switched on, a row joins the home screen's end
 * (the order is changed there). Channels new since this list was last open are marked «Новый».
 */
@Composable
internal fun HomePage() {
    val context = LocalContext.current
    val channels by produceState(emptyList<TvChannel>()) {
        value = withContext(Dispatchers.IO) { queryTvChannels(context).filter { it.items.isNotEmpty() } }
    }
    // Shown here once: the marks go when the list closes (not as it opens, so they can be seen).
    val seen = remember { LauncherSettings.seenChannels }
    val latest by rememberUpdatedState(channels)
    DisposableEffect(Unit) {
        onDispose { if (latest.isNotEmpty()) LauncherSettings.markChannelsSeen(latest.map { it.key }) }
    }
    Section(tr(R.string.show))
    Toggle(tr(R.string.now_playing), LauncherSettings.nowPlaying, Modifier.fillMaxWidth()) {
        LauncherSettings.nowPlaying = !LauncherSettings.nowPlaying
    }
    Section(tr(R.string.second_row))
    T(tr(R.string.channel_rows_hint), 14.sp, color = ScrimDim, maxLines = 3)
    // Auto shows the row it picks as switched on; switching any row turns auto into a plain list.
    val shown = shownChannelRows(LauncherSettings.secondRow, channels)
    // Watch Next (no app of its own) first, then the apps with rows on the home screen, then the
    // others, by name; each app's channels in its order. Settled as the list opens, so switching
    // rows doesn't reorder it under the selection.
    val onAtOpen = remember(channels.isNotEmpty()) { shownChannelRows(LauncherSettings.secondRow, channels).toSet() }
    val groups = channels.groupBy { it.pkg }.entries.sortedWith(
        compareBy(
            { it.key.isNotEmpty() },
            { group -> group.value.none { it.key in onAtOpen } },
            { it.value.first().app?.lowercase() ?: it.key },
        ),
    )
    groups.forEach { (pkg, list) ->
        if (pkg.isNotEmpty()) {
            Row(Modifier.padding(start = 4.dp, top = 16.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(pkg, 20.dp)
                Spacer(Modifier.width(8.dp))
                T(list.first().app ?: pkg, 14.sp, color = ScrimDim)
            }
        }
        list.forEach { channel ->
            Spacer(Modifier.height(8.dp))
            ChannelToggle(channel, channel.key in shown, isNew = seen != null && channel.key !in seen) {
                LauncherSettings.secondRow = toggleChannelRow(LauncherSettings.secondRow, channel.key, channels)
            }
        }
    }
}

/** A channel's switch: its name (and «Новый»), its first posters under it, the switch at the right. */
@Composable
private fun ChannelToggle(channel: TvChannel, checked: Boolean, isNew: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fg = if (focused) FocusText else PanelText
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (focused) FocusBg else CardBg, RoundedCornerShape(16.dp))
            .panelControl({ focused = it }, onClick)
            .padding(start = 16.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(channel.name, 16.sp, Modifier.weight(1f, fill = false), color = fg)
                if (isNew) {
                    Spacer(Modifier.width(8.dp))
                    T(
                        tr(R.string.row_new),
                        12.sp,
                        Modifier.background(MarkOrange, CircleShape).padding(horizontal = 7.dp, vertical = 1.dp),
                        color = Color.White,
                        weight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ChannelThumbs(channel)
        }
        Spacer(Modifier.width(12.dp))
        SwitchTrack(checked, focused)
    }
}

/** Assigns the remote's four shortcut keys; left/right cycles the action, like XGIMI's selectors. */
@Composable
internal fun RemoteButtonsSection() {
    val context = LocalContext.current
    val choices by produceState(listOf("" to tr(R.string.nothing), RemoteButtons.PANEL to tr(R.string.this_panel), RemoteButtons.HOME to tr(R.string.home_screen))) {
        val apps = withContext(Dispatchers.IO) { AppRepository(context).loadApps().sortedBy { it.label.lowercase() } }
        value = value + apps.map { RemoteButtons.app(it.pkg) to it.label }
    }
    val requesters = remember { List(4) { FocusRequester() } }
    val pressed = RemoteButtons.lastPressed.intValue
    LaunchedEffect(pressed) {
        if (pressed >= 0) {
            runCatching { requesters[pressed].requestFocus() }
            RemoteButtons.lastPressed.intValue = -1
        }
    }

    Section(tr(R.string.app_buttons))
    T(tr(R.string.app_buttons_hint), 14.sp, color = ScrimDim, maxLines = 3)
    for (i in 0..3) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            val current = RemoteButtons.actions[i]
            // An assignment that isn't in the list yet (the apps are still loading) or any more
            // (the app was removed) is shown as it is, and ← → step from it: not from "Ничего".
            val options = if (choices.any { it.first == current }) choices else choices + (current to assignedLabel(context, current))
            val index = options.indexOfFirst { it.first == current }
            Selector(
                label = tr(R.string.button_n, i + 1),
                value = options[index].second,
                modifier = Modifier.weight(1f).focusRequester(requesters[i]),
            ) { delta ->
                RemoteButtons.set(i, options[(index + delta).mod(options.size)].first)
            }
        }
    }
}

/** What a button assigned to [action] is called: the app's label, or its package if it is gone. */
private fun assignedLabel(context: android.content.Context, action: String): String {
    val pkg = RemoteButtons.packageOf(action) ?: return tr(R.string.nothing)
    val pm = context.packageManager
    return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(tr(R.string.pkg_missing, pkg))
}

/** XMB colour (by month like the PS3, or fixed) and the animation switch. */
@Composable
private fun XmbOptions() {
    Spacer(Modifier.height(ControlGap))
    Row(Modifier.fillMaxWidth()) {
        Chip(tr(R.string.color_by_month), LauncherSettings.xmbColor < 0, Modifier.weight(1f)) { LauncherSettings.xmbColor = -1 }
    }
    // Two rows of six: January-June, July-December.
    XmbColors.chunked(6).forEachIndexed { row, colors ->
        Spacer(Modifier.height(ControlGap))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            colors.forEachIndexed { col, color ->
                val index = row * 6 + col
                ColorDot(color, LauncherSettings.xmbColor == index) { LauncherSettings.xmbColor = index }
            }
        }
    }
    Spacer(Modifier.height(ControlGap))
    Row(Modifier.fillMaxWidth()) {
        Toggle(tr(R.string.background_animation), LauncherSettings.bgAnimation, Modifier.weight(1f)) {
            LauncherSettings.bgAnimation = !LauncherSettings.bgAnimation
        }
    }
}
