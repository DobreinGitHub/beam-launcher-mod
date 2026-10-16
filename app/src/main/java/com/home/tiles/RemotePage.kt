package com.home.tiles

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Input
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Кнопки пульта»: the remote's app keys and what each does. A drawing of the top of the remote
 * shows the key of the row selected; pressing a key on the remote jumps to its row (and tells which
 * of the two remotes this is). ← → step through the common actions, OK opens them all with the apps.
 */
@Composable
internal fun RemoteButtonsPage() {
    val context = LocalContext.current
    remember { RemoteKeys.start(context) }
    DisposableEffect(Unit) {
        RemoteKeys.capture(true)
        onDispose {
            RemoteKeys.capture(false)
            PanelSubBack.handler = null
        }
    }
    val apps by produceState(emptyList<AppEntry>()) {
        value = withContext(Dispatchers.IO) { AppRepository(context).loadApps().sortedBy { it.label.lowercase() } }
    }
    val model = RemoteKeys.model
    val rowFocus = remember { HashMap<Int, FocusRequester>() }
    fun focusOf(code: Int) = rowFocus.getOrPut(code) { FocusRequester() }
    var selected by remember { mutableStateOf<Int?>(null) }
    var picking by remember { mutableStateOf<RemoteKeys.Key?>(null) }
    // The row whose list is open, to put the selection back on when it closes.
    var returnTo by remember { mutableStateOf<Int?>(null) }

    // A press on the remote points at its row (leaving the list, if it was open).
    val pressed = RemoteKeys.pressed
    LaunchedEffect(pressed) {
        if (pressed.second == 0) return@LaunchedEffect
        picking = null
        withFrameNanos {}
        runCatching { focusOf(pressed.first).requestFocus() }
    }

    val choosing = picking
    if (choosing != null) {
        SideEffect {
            PanelSubBack.handler = {
                picking = null
                true
            }
        }
        ActionList(choosing, apps) { action ->
            RemoteKeys.set(choosing.code, action)
            picking = null
        }
        DisposableEffect(choosing) { onDispose { PanelSubBack.handler = null } }
        return
    }
    // Back from the list: on the row it was opened from.
    LaunchedEffect(Unit) {
        val back = returnTo ?: return@LaunchedEffect
        withFrameNanos {}
        runCatching { focusOf(back).requestFocus() }
    }

    RemoteDrawing(model, highlighted = selected)
    T(tr(R.string.remote_hint), 14.sp, Modifier.padding(top = 4.dp, bottom = 14.dp), color = ScrimDim, maxLines = 3)
    model.keys.forEach { key ->
        KeyRow(
            key,
            RemoteKeys.actions[key.code].orEmpty(),
            Modifier.focusRequester(focusOf(key.code)),
            onFocus = { if (it) selected = key.code else if (selected == key.code) selected = null },
            onOpen = {
                returnTo = key.code
                picking = key
            },
        )
        Spacer(Modifier.height(8.dp))
    }
    Spacer(Modifier.height(10.dp))
    Selector(tr(R.string.remote_model), modelName(model), Modifier.fillMaxWidth()) { delta ->
        RemoteKeys.chooseModel(RemoteKeys.Model.entries[(model.ordinal + delta).mod(RemoteKeys.Model.entries.size)])
    }
    // tonisaf's module holds the same keys: the firmware refuses them to Beam Mod while it is there.
    // (Our own com.beam.remote, the entry from XGIMI's settings, is signed like Beam Mod: not that one.)
    val moduleThere = remember(RemoteKeys.refused.size) {
        val pm = context.packageManager
        runCatching { pm.getPackageInfo(RemoteKeys.OLD_MODULE, 0) }.isSuccess &&
            pm.checkSignatures(context.packageName, RemoteKeys.OLD_MODULE) != android.content.pm.PackageManager.SIGNATURE_MATCH
    }
    if (moduleThere) {
        Spacer(Modifier.height(18.dp))
        ListRow(tr(R.string.remote_module_remove), tr(R.string.remote_module)) {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:${RemoteKeys.OLD_MODULE}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}

private fun modelName(model: RemoteKeys.Model) = model.keys.take(2).joinToString(" · ") { it.name }

/** The row of one key: its name and, under it, what the firmware does with it; the action at the right. */
@Composable
private fun KeyRow(key: RemoteKeys.Key, action: String, modifier: Modifier, onFocus: (Boolean) -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    // ← → walk the common actions; an app chosen from the list stays among them while it is set.
    val quick = buildList {
        addAll(QuickActions)
        if (RemoteKeys.packageOf(action) != null) add(action)
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(if (focused) FocusBg else CardBg, RoundedCornerShape(16.dp))
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                if (code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER) {
                    if (event.type == KeyEventType.KeyUp) {
                        Sounds.activate()
                        onOpen()
                    }
                    return@onPreviewKeyEvent true
                }
                if (code != AndroidKeyEvent.KEYCODE_DPAD_LEFT && code != AndroidKeyEvent.KEYCODE_DPAD_RIGHT) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    Sounds.activate()
                    val step = if (code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    val index = quick.indexOf(action).coerceAtLeast(0)
                    RemoteKeys.set(key.code, quick[(index + step).mod(quick.size)])
                }
                true
            }
            .onFocusChanged {
                focused = it.isFocused
                onFocus(it.isFocused)
                if (it.isFocused) Sounds.navigate()
            }
            .focusable()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            T(key.name, 16.sp, color = if (focused) FocusText else PanelText)
            T(
                stockText(context, key.stock), 14.sp, Modifier.padding(top = 2.dp),
                color = if (focused) FocusText.copy(alpha = 0.7f) else PanelDim,
            )
        }
        Spacer(Modifier.width(10.dp))
        T("‹  ${actionName(context, action)}  ›", 16.sp, Modifier.widthIn(max = 150.dp), color = if (focused) FocusText else PanelText, align = androidx.compose.ui.text.style.TextAlign.End)
    }
}

private val QuickActions = listOf(
    "", RemoteKeys.NONE, RemoteKeys.PANEL, RemoteKeys.HOME, RemoteKeys.PLAY_PAUSE, RemoteKeys.NEXT, RemoteKeys.PREVIOUS,
)

/** Every action, then the apps, for [key]; the one set has its check mark and the selection. */
@Composable
private fun ActionList(key: RemoteKeys.Key, apps: List<AppEntry>, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val current = RemoteKeys.actions[key.code].orEmpty()
    val currentFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(apps.isNotEmpty()) {
        withFrameNanos {}
        if (runCatching { currentFocus.requestFocus() }.isFailure) runCatching { firstFocus.requestFocus() }
    }
    @Composable
    fun option(action: String, text: String) {
        val chosen = action == current
        Chip(
            text, chosen,
            Modifier
                .fillMaxWidth()
                .then(if (chosen) Modifier.focusRequester(currentFocus) else Modifier)
                .then(if (action == QuickActions.first()) Modifier.focusRequester(firstFocus) else Modifier),
        ) { onPick(action) }
        Spacer(Modifier.height(8.dp))
    }
    T(key.name, 20.sp, color = ScrimText, weight = FontWeight.Medium)
    T(stockText(context, key.stock), 14.sp, Modifier.padding(top = 2.dp), color = ScrimDim)
    Section(tr(R.string.remote_actions))
    QuickActions.forEach { option(it, actionName(context, it)) }
    if (apps.isNotEmpty()) {
        Section(tr(R.string.remote_apps))
        apps.forEach { option(RemoteKeys.app(it.pkg), it.label) }
    }
}

private fun actionName(context: android.content.Context, action: String): String = when (action) {
    "" -> tr(R.string.action_firmware)
    RemoteKeys.NONE -> tr(R.string.nothing)
    RemoteKeys.PANEL -> tr(R.string.action_panel)
    RemoteKeys.HOME -> tr(R.string.home_screen)
    RemoteKeys.PLAY_PAUSE -> tr(R.string.action_play_pause)
    RemoteKeys.NEXT -> tr(R.string.action_next)
    RemoteKeys.PREVIOUS -> tr(R.string.action_previous)
    else -> RemoteKeys.packageOf(action)?.let { pkg ->
        val pm = context.packageManager
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(tr(R.string.pkg_missing, pkg))
    } ?: action
}

/** What the firmware does with a key left to it. */
private fun stockText(context: android.content.Context, stock: RemoteKeys.Stock): String = when (stock) {
    is RemoteKeys.Stock.App ->
        if (runCatching { context.packageManager.getPackageInfo(stock.pkg, 0) }.isSuccess) tr(R.string.stock_opens, stock.name)
        else tr(R.string.stock_missing, stock.name)
    RemoteKeys.Stock.Atmosphere -> tr(R.string.stock_ambient)
    RemoteKeys.Stock.Source -> tr(R.string.stock_source)
}

// The remote as it is: white plastic, slightly darker keys, grey print; the key in hand in XGIMI's blue.
private val RemoteBody = Color(0xFFECEEEB)
private val RemoteKey = Color(0xFFE0E2DE)
private val RemoteEdge = Color(0x1F000000)
private val RemotePrint = Color(0xFF6E7173)
private val RemoteLit = Color(0xFF497AF5)

/**
 * The top of the remote, drawn after the real one (photos of both models): power and mute, the two
 * pills of app keys and the round keys under them, fading out below; short, so the page fits the
 * screen. The key of [highlighted] lights up.
 */
@Composable
private fun RemoteDrawing(model: RemoteKeys.Model, highlighted: Int?) {
    Box(Modifier.fillMaxWidth().padding(bottom = 4.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .width(184.dp)
                .height(160.dp)
                // Fades out below: the rest of the remote isn't what this page is about.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0.72f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                }
                .background(RemoteBody, RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp))
                .padding(start = 14.dp, end = 14.dp, top = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RoundKey { Box(Modifier.size(8.dp).background(Color(0xFFC62828), CircleShape)) }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(4.dp).background(Color(0xFF55585A), CircleShape))
                Spacer(Modifier.weight(1f))
                RoundKey { KeyIcon(Icons.AutoMirrored.Outlined.VolumeOff) }
            }
            Spacer(Modifier.height(10.dp))
            model.keys.chunked(2).forEach { pair ->
                Pill {
                    pair.forEachIndexed { i, key ->
                        PillHalf(key, lit = key.code == highlighted, left = i == 0)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RoundKey { KeyIcon(Icons.Outlined.CenterFocusWeak) }
                RoundKey { KeyIcon(Icons.Outlined.Mic) }
                RoundKey { Diamonds() }
            }
        }
    }
}

@Composable
private fun RoundKey(content: @Composable () -> Unit) {
    Box(
        Modifier.size(36.dp).background(RemoteKey, CircleShape).border(1.dp, RemoteEdge, CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun KeyIcon(icon: ImageVector) {
    Image(icon, null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(RemotePrint))
}

/** The menu key's three small diamonds. */
@Composable
private fun Diamonds() {
    Canvas(Modifier.size(18.dp)) {
        val s = size.width * 0.3f
        listOf(Offset(0.5f, 0.2f), Offset(0.78f, 0.5f), Offset(0.5f, 0.8f)).forEach { (x, y) ->
            val c = Offset(size.width * x, size.height * y)
            rotate(45f, c) { drawRect(RemotePrint, Offset(c.x - s / 2, c.y - s / 2), Size(s, s)) }
        }
    }
}

@Composable
private fun Pill(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(RemoteKey)
            .border(1.dp, RemoteEdge, RoundedCornerShape(14.dp)),
        content = content,
    )
}

@Composable
private fun RowScope.PillHalf(key: RemoteKeys.Key, lit: Boolean, left: Boolean) {
    val print = if (lit) Color.White else RemotePrint
    Row(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(if (lit) RemoteLit else Color.Transparent),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keyIcon(key)?.let {
            Image(it, null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(print))
            Spacer(Modifier.width(4.dp))
        }
        T(key.name, 12.sp, color = print, weight = FontWeight.Medium)
    }
    // The seam between the two keys of a pill.
    if (left) Box(Modifier.width(1.dp).fillMaxHeight().background(RemoteEdge))
}

private fun keyIcon(key: RemoteKeys.Key): ImageVector? = when (key.stock) {
    RemoteKeys.Stock.Atmosphere -> Icons.Outlined.FilterVintage
    RemoteKeys.Stock.Source -> Icons.AutoMirrored.Outlined.Input
    is RemoteKeys.Stock.App -> if (key.code == 2119) Icons.Outlined.SmartDisplay else null
}

/** Opens the settings page straight on «Кнопки пульта»: once, for the next page that opens. */
internal object PanelOpenOn {
    @Volatile private var remoteButtons = false
    fun requestRemoteButtons() { remoteButtons = true }
    fun takeRemoteButtons(): Boolean = remoteButtons.also { remoteButtons = false }
}

/**
 * The way in from XGIMI's settings (Общие → «Кнопки пульта»): that entry opens com.beam.remote, our
 * small app of that name (the remote-entry module), which comes here. Open only to apps signed like
 * Beam Mod (the OPEN_SETTINGS permission).
 */
class RemoteButtonsActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        PanelOpenOn.requestRemoteButtons()
        if (!PanelOverlay.show()) {
            PanelRequests.count.intValue++
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}

/** A section's own Back, for a view inside it (the remote keys' action list): returns true if it handled Back. */
internal object PanelSubBack {
    var handler: (() -> Boolean)? = null
}
