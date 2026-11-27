package com.bydmate.app.data.automation

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/** A bonded Bluetooth device offered by the automation trigger editor (#486). */
data class PairedBluetoothDevice(
    val name: String,
    val address: String,
)

/**
 * Returns paired Bluetooth devices from the default adapter, sorted by name.
 * Returns an empty list if there is no adapter, Bluetooth is unavailable, or no device is bonded.
 * Uses only the normal BLUETOOTH permission (targetSdk 29, no runtime prompt).
 */
@SuppressLint("MissingPermission")
fun pairedBluetoothDevices(): List<PairedBluetoothDevice> = runCatching {
    val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
    adapter.bondedDevices.orEmpty()
        .mapNotNull { device ->
            val address = device.address?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = device.name?.takeIf { it.isNotBlank() } ?: address
            PairedBluetoothDevice(name = name, address = address)
        }
        .sortedBy { it.name.lowercase() }
}.getOrDefault(emptyList())

/**
 * Tracks currently connected Bluetooth device addresses for automation triggers (#486).
 * Seeded at start from bonded devices via hidden BluetoothDevice.isConnected() reflection
 * (starts empty on any failure), then updated by ACTION_ACL_CONNECTED / ACTION_ACL_DISCONNECTED.
 */
class BluetoothConnectionTracker {

    private val connected: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val address = device.address?.takeIf { it.isNotBlank() } ?: return
            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> connected.add(address)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    connected.removeIf { it.equals(address, ignoreCase = true) }
                }
            }
        }
    }

    fun addresses(): Set<String> = connected.toSet()

    fun start(context: Context) {
        if (registered) return
        connected.clear()
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        runCatching {
            context.registerReceiver(receiver, filter)
            registered = true
        }.onFailure { e ->
            Log.w(TAG, "bt receiver register failed: ${e.javaClass.simpleName}")
        }
        seedConnected()
    }

    fun stop(context: Context) {
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
                .onFailure { e -> Log.w(TAG, "bt receiver unregister failed: ${e.javaClass.simpleName}") }
            registered = false
        }
        connected.clear()
    }

    @SuppressLint("MissingPermission")
    private fun seedConnected() {
        runCatching {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            val isConnectedMethod = BluetoothDevice::class.java.getMethod("isConnected")
            adapter.bondedDevices.orEmpty().forEach { device ->
                val isConnected = runCatching { isConnectedMethod.invoke(device) as? Boolean }.getOrNull() == true
                if (isConnected) {
                    device.address?.takeIf { it.isNotBlank() }?.let { connected.add(it) }
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "bt seed failed, starting empty: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "BtConnTracker"
    }
}
