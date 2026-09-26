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
    /** Address -> "Сопряжение…", "Сопряжено" or "Не удалось". */
    val pairing = mutableStateMapOf<String, String>()

    private var receiver: BroadcastReceiver? = null
    private val adapter get() = BluetoothAdapter.getDefaultAdapter()

    fun start(context: Context) {
        val app = context.applicationContext
        stop(app)
        found.clear()
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
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning.value = false
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        if (device.address !in pairing) return
                        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                            BluetoothDevice.BOND_BONDED -> {
                                pairing[device.address] = "Сопряжено"
                                found.removeAll { it.address == device.address }
                                connectWhenListed(app, device.address)
                            }
                            BluetoothDevice.BOND_NONE -> pairing[device.address] = "Не удалось"
                        }
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
        scanning.value = runCatching { adapter?.startDiscovery() == true }
            .onFailure { Log.w("BluetoothScan", "discovery failed", it) }.getOrDefault(false)
    }

    /** Ends discovery; keeps listening for a pairing still in progress. */
    fun stop(context: Context) {
        runCatching { adapter?.cancelDiscovery() }
        scanning.value = false
        if (pairing.values.none { it == "Сопряжение…" }) {
            receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
            receiver = null
        }
    }

    fun pair(address: String) {
        runCatching { adapter?.cancelDiscovery() }
        scanning.value = false
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        pairing[address] = if (device.createBond()) "Сопряжение…" else "Не удалось"
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
