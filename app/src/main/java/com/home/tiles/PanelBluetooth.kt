package com.home.tiles

import android.os.SystemClock
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PENDING_TIMEOUT_MS = 15_000L

/** Search for new devices; OK on one pairs it (speakers then connect by themselves). */
@Composable
private fun NewDevicesSection(paired: List<XgimiBluetooth.Device>?) {
    val context = LocalContext.current
    val scanning = BluetoothScan.scanning.value
    Section(if (scanning) tr(R.string.bt_new_searching) else tr(R.string.bt_new_devices))
    ListRow(if (scanning) tr(R.string.bt_stop_search) else tr(R.string.bt_search)) {
        if (scanning) BluetoothScan.stop(context) else BluetoothScan.start(context)
    }
    Spacer(Modifier.height(8.dp))
    BluetoothScan.found.forEach { device ->
        val state = BluetoothScan.pairing[device.address]
        Chip(
            device.name,
            state == BluetoothScan.PAIRED,
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            note = listOfNotNull(state ?: tr(R.string.bt_ok_connect), device.kind).joinToString(" · "),
        ) {
            if (state != BluetoothScan.PAIRING) BluetoothScan.pair(context, device.address)
        }
    }
    // Until XGIMI's list (refreshed every two seconds) picks the new device up.
    BluetoothScan.pairing.filterValues { it == BluetoothScan.PAIRED }.keys.forEach { address ->
        if (paired != null && paired.none { it.address == address }) {
            T(tr(R.string.bt_paired_address, address), 14.sp, color = PanelDim)
        }
    }
    if (!scanning && BluetoothScan.found.isEmpty()) {
        T(tr(R.string.bt_pairing_hint), 14.sp, color = PanelDim)
    }
}

/**
 * Paired Bluetooth devices; a click connects or disconnects one (e.g. switching sound between a
 * speaker and the projector). Refreshed every two seconds while open, since connecting takes a
 * moment and XGIMI's calls don't report the outcome.
 */
@Composable
internal fun BluetoothPage(onXgimiPage: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) { onDispose { BluetoothScan.stop(context) } }
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<XgimiBluetooth.Device>?>(null) }
    // Addresses we just asked to (dis)connect (wanted state, time asked), shown as "…" until the
    // state changes. Gives up after [PENDING_TIMEOUT_MS]: a speaker that is off never gets there.
    var pending by remember { mutableStateOf(emptyMap<String, Pair<Boolean, Long>>()) }
    LaunchedEffect(Unit) {
        while (true) {
            val list = withContext(Dispatchers.IO) { XgimiBluetooth.devices(context) }
            devices = list
            val now = SystemClock.elapsedRealtime()
            pending = pending.filter { (address, request) ->
                val (wantConnected, since) = request
                now - since < PENDING_TIMEOUT_MS && list.firstOrNull { it.address == address }?.connected != wantConnected
            }
            delay(2000)
        }
    }
    val list = devices
    Section(tr(R.string.devices))
    when {
        list == null -> T(tr(R.string.loading), 14.sp, color = PanelDim)
        list.none { !it.remote } -> T(tr(R.string.bt_no_paired), 14.sp, color = PanelDim)
        else -> list.filter { !it.remote }.forEach { device ->
            val waiting = device.address in pending
            val state = when {
                waiting && pending.getValue(device.address).first -> tr(R.string.bt_connecting)
                waiting -> tr(R.string.bt_disconnecting)
                device.connecting -> tr(R.string.bt_connecting)
                device.connected -> tr(R.string.bt_connected)
                else -> tr(R.string.bt_not_connected)
            }
            Chip(
                device.name.ifBlank { device.address },
                device.connected,
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                note = if (device.audio) "$state · ${tr(R.string.bt_kind_audio)}" else state,
            ) {
                if (waiting) return@Chip
                val connect = !device.connected
                pending = pending + (device.address to (connect to SystemClock.elapsedRealtime()))
                scope.launch(Dispatchers.IO) {
                    if (connect) XgimiBluetooth.connect(context, device) else XgimiBluetooth.disconnect(context, device)
                }
            }
        }
    }
    NewDevicesSection(list)
    val visible = rememberFirmwareState { BluetoothOptions.discoverable(context) }
    val absolute = rememberFirmwareState { BluetoothOptions.absoluteVolume() }
    Section(tr(R.string.settings))
    visible.value?.let { on ->
        Toggle(tr(R.string.bt_discoverable), on, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            visible.change(!on) { BluetoothOptions.setDiscoverable(context, !on) }
        }
    }
    absolute.value?.let { on ->
        Toggle(tr(R.string.bt_abs_volume), on, Modifier.fillMaxWidth()) {
            absolute.change(!on) { BluetoothOptions.setAbsoluteVolume(context, !on) }
        }
    }
    Section(tr(R.string.more))
    ListRow(tr(R.string.bt_xgimi_settings), onXgimiPage)
}
