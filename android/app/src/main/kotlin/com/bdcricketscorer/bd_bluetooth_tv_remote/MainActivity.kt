package com.bdcricketscorer.bd_bluetooth_tv_remote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executor

class MainActivity : FlutterActivity() {
    private val channelName = "bd_remote/hid"
    private val permissionRequest = 7021
    private var hid: BluetoothHidDevice? = null
    private var pendingStart: MethodChannel.Result? = null
    private var appRegistered = false
    private var tvConnected = false
    private var lastDeviceName = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: Executor by lazy { ContextCompat.getMainExecutor(this) }

    private val descriptor = intArrayOf(
        // Keyboard report (Report ID 1): modifiers, reserved byte, six key usages.
        0x05,0x01,0x09,0x06,0xA1,0x01,0x85,0x01,
        0x05,0x07,0x19,0xE0,0x29,0xE7,0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x08,0x81,0x02,
        0x95,0x01,0x75,0x08,0x81,0x01,
        0x95,0x06,0x75,0x08,0x15,0x00,0x25,0x76,0x05,0x07,0x19,0x00,0x29,0x76,0x81,0x00,0xC0,
        // Consumer control report (Report ID 2): 16-bit consumer usage.
        0x05,0x0C,0x09,0x01,0xA1,0x01,0x85,0x02,
        0x15,0x00,0x26,0xFF,0x03,0x19,0x00,0x2A,0xFF,0x03,0x75,0x10,0x95,0x01,0x81,0x00,0xC0
    ).let { values -> ByteArray(values.size) { index -> values[index].toByte() } }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "startHid" -> startHid(result)
                "getHidStatus" -> result.success(hidStatus())
                "getPairedDevices" -> result.success(pairedDevices())
                "connectDevice" -> connectDevice(call.argument<String>("address") ?: "", result)
                "disconnectDevice" -> disconnectDevice(call.argument<String>("address") ?: "", result)
                "sendKey" -> sendKeyboardKey(call.argument<Int>("key") ?: 0, result)
                "sendConsumer" -> sendConsumer(call.argument<Int>("usage") ?: 0, result)
                else -> result.notImplemented()
            }
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val needed = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN)
        return needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun startHid(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            result.error("UNSUPPORTED", "Bluetooth HID Device mode requires Android 9 or newer.", null); return
        }
        if (!hasBluetoothPermissions()) {
            pendingStart = result
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN), permissionRequest)
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) { result.error("NO_BLUETOOTH", "This phone has no Bluetooth adapter.", null); return }
        if (!adapter.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            result.error("BLUETOOTH_OFF", "Turn Bluetooth on, then tap Start Bluetooth Remote again.", null); return
        }
        if (appRegistered && hid != null) {
            makeDiscoverable()
            result.success("ready")
            return
        }

        pendingStart = result
        val opened = adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (profile != BluetoothProfile.HID_DEVICE || proxy !is BluetoothHidDevice) return
                hid = proxy
                val callback = object : BluetoothHidDevice.Callback() {
                    override fun onAppStatusChanged(device: BluetoothDevice?, registered: Boolean) {
                        appRegistered = registered
                        if (!registered) tvConnected = false
                        if (registered) {
                            makeDiscoverable()
                            pendingStart?.success("ready")
                            pendingStart = null
                        } else {
                            pendingStart?.error("REGISTER_FAILED", "Bluetooth HID registration failed.", null)
                            pendingStart = null
                        }
                    }

                    override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                        tvConnected = state == BluetoothProfile.STATE_CONNECTED
                        if (device != null && tvConnected) {
                            try { lastDeviceName = device.name ?: device.address } catch (_: SecurityException) { lastDeviceName = "" }
                        } else if (!tvConnected) {
                            lastDeviceName = ""
                        }
                    }
                }
                try {
                    // This app sends keyboard and consumer-control reports, not mouse reports.
                    val sdp = BluetoothHidDeviceAppSdpSettings(
                        "Bluetooth Remote",
                        "Bluetooth HID TV Remote",
                        "Bluetooth Remote",
                        BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                        descriptor
                    )
                    val accepted = proxy.registerApp(sdp, null, null, executor, callback)
                    mainHandler.postDelayed({
                        if (pendingStart != null && !appRegistered) {
                            val reason = if (!accepted) {
                                "Android rejected the HID registration request. Another HID app may be registered."
                            } else {
                                "Android did not confirm HID registration. The phone firmware may not support third-party HID Device mode."
                            }
                            pendingStart?.error("HID_REGISTRATION_TIMEOUT", reason, null)
                            pendingStart = null
                        }
                    }, 8000)
                } catch (e: SecurityException) {
                    pendingStart?.error("PERMISSION", e.message, null); pendingStart = null
                } catch (e: Exception) {
                    pendingStart?.error("HID_ERROR", e.message, null); pendingStart = null
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
                appRegistered = false
                tvConnected = false
                lastDeviceName = ""
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!opened) {
            result.error("PROFILE_UNAVAILABLE", "This phone does not expose the Bluetooth HID Device profile.", null)
            pendingStart = null
        }
    }

    private fun makeDiscoverable() {
        try {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
        } catch (_: Exception) { }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest) {
            val result = pendingStart
            pendingStart = null
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                result?.success("Permissions granted. Tap Start Bluetooth Remote again.")
            } else result?.error("PERMISSION_DENIED", "Bluetooth permissions are required.", null)
        }
    }

    private fun adapter(): BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private fun pairedDevices(): List<Map<String, Any?>> {
        if (!hasBluetoothPermissions()) return emptyList()
        return try {
            adapter()?.bondedDevices
                ?.map { device ->
                    val state = try { hid?.getConnectionState(device) ?: BluetoothProfile.STATE_DISCONNECTED }
                    catch (_: Exception) { BluetoothProfile.STATE_DISCONNECTED }
                    mapOf(
                        "name" to (device.name ?: "Bluetooth device"),
                        "address" to device.address,
                        "bonded" to (device.bondState == BluetoothDevice.BOND_BONDED),
                        "connected" to (state == BluetoothProfile.STATE_CONNECTED),
                        "connecting" to (state == BluetoothProfile.STATE_CONNECTING)
                    )
                } ?: emptyList()
        } catch (_: SecurityException) { emptyList() }
    }

    private fun hidStatus(): Map<String, Any?> {
        val devices = pairedDevices()
        val connected = devices.firstOrNull { it["connected"] == true }
        return mapOf(
            "registered" to appRegistered,
            "connected" to (connected != null || tvConnected),
            "device" to (connected?.get("name") ?: lastDeviceName),
            "pairedDevices" to devices
        )
    }

    private fun findBondedDevice(address: String): BluetoothDevice? {
        if (!hasBluetoothPermissions() || address.isBlank()) return null
        return try { adapter()?.bondedDevices?.firstOrNull { it.address.equals(address, true) } }
        catch (_: SecurityException) { null }
    }

    private fun connectDevice(address: String, result: MethodChannel.Result) {
        val device = findBondedDevice(address)
        if (device == null) {
            result.error("DEVICE_NOT_PAIRED", "এই TV ফোনের Bluetooth paired list-এ নেই। আগে TV থেকে ফোনটি pair করুন।", null)
            return
        }
        val profile = hid
        if (!appRegistered || profile == null) {
            result.error("HID_NOT_READY", "আগে Start Bluetooth Remote চাপুন।", null)
            return
        }
        try {
            val accepted = profile.connect(device)
            if (accepted) result.success(true)
            else result.error("CONNECT_REJECTED", "Android HID connection request গ্রহণ করেনি। TV-তে আগের pairing মুছে আবার pair করুন।", null)
        } catch (e: SecurityException) {
            result.error("PERMISSION", e.message, null)
        } catch (e: Exception) {
            result.error("CONNECT_FAILED", e.message, null)
        }
    }

    private fun disconnectDevice(address: String, result: MethodChannel.Result) {
        val device = findBondedDevice(address)
        if (device == null) { result.error("DEVICE_NOT_FOUND", "Paired TV পাওয়া যায়নি।", null); return }
        try {
            val ok = hid?.disconnect(device) == true
            if (ok) result.success(true) else result.error("DISCONNECT_FAILED", "Disconnect request failed.", null)
        } catch (e: Exception) { result.error("DISCONNECT_FAILED", e.message, null) }
    }

    private fun connectedDevice(): BluetoothDevice? {
        if (!appRegistered || hid == null || !hasBluetoothPermissions()) return null
        return try { hid?.connectedDevices?.firstOrNull() } catch (_: SecurityException) { null }
    }

    private fun sendKeyboardKey(key: Int, result: MethodChannel.Result) {
        val device = connectedDevice()
        if (device == null) { result.error("NOT_PAIRED", "আগে HY320mini-এর সঙ্গে Bluetooth Remote connect করুন।", null); return }
        try {
            val press = byteArrayOf(0, 0, key.toByte(), 0, 0, 0, 0, 0)
            val release = ByteArray(8)
            val sent = hid?.sendReport(device, 1, press) == true
            hid?.sendReport(device, 1, release)
            if (sent) result.success(true) else result.error("SEND_FAILED", "TV did not accept the key.", null)
        } catch (e: SecurityException) { result.error("PERMISSION", e.message, null) }
    }

    private fun sendConsumer(usage: Int, result: MethodChannel.Result) {
        val device = connectedDevice()
        if (device == null) { result.error("NOT_PAIRED", "আগে HY320mini-এর সঙ্গে Bluetooth Remote connect করুন।", null); return }
        try {
            val press = byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte())
            val release = byteArrayOf(0, 0)
            val sent = hid?.sendReport(device, 2, press) == true
            hid?.sendReport(device, 2, release)
            if (sent) result.success(true) else result.error("SEND_FAILED", "TV did not accept this media key.", null)
        } catch (e: SecurityException) { result.error("PERMISSION", e.message, null) }
    }
}
