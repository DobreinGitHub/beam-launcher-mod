package com.home.tiles

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf

/**
 * Finding and pairing new Bluetooth devices with Android's own discovery (XGIMI's scan reports
 * through an AIDL callback). Discovery needs the location permission, granted over adb.
 * Found devices and their pairing state are Compose state for the Bluetooth page.
 */
@SuppressLint("MissingPermission")
object BluetoothScan {
    class Found(val name: String, val address: String, val kind: String)

    val found = mutableStateListOf<Found>()
    val scanning = mutableStateOf(false)
    /** Address -> [PAIRING], [PAIRED] or [FAILED]. */
    val pairing = mutableStateMapOf<String, String>()

    const val PAIRING = "Сопряжение…"
    const val PAIRED = "Сопряжено"
    const val FAILED = "Не удалось"

    private var receiver: BroadcastReceiver? = null
    private val adapter get() = BluetoothAdapter.getDefaultAdapter()

    fun start(context: Context) {
        val app = context.applicationContext
        runCatching { adapter?.cancelDiscovery() }
        found.clear()
        // Results of earlier attempts; a pairing still in progress keeps its entry.
        pairing.filterValues { it != PAIRING }.keys.forEach { pairing.remove(it) }
        listen(app)
        scanning.value = runCatching { adapter?.startDiscovery() == true }
            .onFailure { Log.w("BluetoothScan", "discovery failed", it) }.getOrDefault(false)
        if (!scanning.value) releaseIfIdle(app)
    }

    /** Ends discovery; keeps listening for a pairing still in progress. */
    fun stop(context: Context) {
        runCatching { adapter?.cancelDiscovery() }
        scanning.value = false
        releaseIfIdle(context.applicationContext)
    }

    fun pair(context: Context, address: String) {
        val app = context.applicationContext
        runCatching { adapter?.cancelDiscovery() }
        scanning.value = false
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        listen(app)
        pairing[address] = if (device.createBond()) PAIRING else FAILED
        releaseIfIdle(app)
    }

    /** Registers the receiver unless it already is; there is only ever one. */
    private fun listen(app: Context) {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        if (device.bondState == BluetoothDevice.BOND_BONDED) return
                        // Nameless devices are mostly beacons and phones' random addresses.
                        val name = device.name?.takeIf { it.isNotBlank() } ?: return
                        if (found.none { it.address == device.address }) found += Found(name, device.address, kind(device))
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        scanning.value = false
                        releaseIfIdle(app)
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        if (pairing[device.address] != PAIRING) return
                        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                            BluetoothDevice.BOND_BONDED -> {
                                pairing[device.address] = PAIRED
                                found.removeAll { it.address == device.address }
                                connectWhenListed(app, device.address)
                            }
                            BluetoothDevice.BOND_NONE -> pairing[device.address] = FAILED
                            else -> return
                        }
                        releaseIfIdle(app)
                    }
                }
            }
        }
        app.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            },
        )
    }

    /** Unregisters once neither a search nor a pairing needs the receiver. */
    private fun releaseIfIdle(app: Context) {
        if (scanning.value || pairing.values.any { it == PAIRING }) return
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
    }

    /** Speakers and headphones connect right after pairing, through XGIMI's service. */
    private fun connectWhenListed(context: Context, address: String) {
        Thread {
            repeat(10) {
                val device = XgimiBluetooth.devices(context).firstOrNull { it.address == address }
                if (device != null) {
                    if (device.audio && !device.connected) XgimiBluetooth.connect(context, device)
                    return@Thread
                }
                Thread.sleep(1000)
            }
        }.start()
    }

    private fun kind(device: BluetoothDevice): String = when (device.bluetoothClass?.majorDeviceClass) {
        BluetoothClass.Device.Major.AUDIO_VIDEO -> "колонка/наушники"
        BluetoothClass.Device.Major.PHONE -> "телефон"
        BluetoothClass.Device.Major.COMPUTER -> "компьютер"
        BluetoothClass.Device.Major.PERIPHERAL -> "геймпад/клавиатура"
        else -> "устройство"
    }
}
