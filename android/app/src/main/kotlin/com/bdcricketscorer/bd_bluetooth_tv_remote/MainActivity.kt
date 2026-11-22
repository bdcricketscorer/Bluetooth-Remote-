package com.bdcricketscorer.bd_bluetooth_tv_remote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    private val executor: Executor by lazy { ContextCompat.getMainExecutor(this) }

    private val descriptor = byteArrayOf(
        0x05,0x01,0x09,0x06,0xA1.toByte(),0x01,
        0x85.toByte(),0x01,
        0x05,0x07,0x19,0xE0.toByte(),0x29,0xE7.toByte(),0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x08.toByte(),0x81.toByte(),0x02,
        0x95,0x01,0x75,0x08,0x81.toByte(),0x01,
        0x95,0x06,0x75,0x08,0x15,0x00,0x25,0x76,0x05,0x07,0x19,0x00,0x29,0x76,0x81.toByte(),0x00,0xC0.toByte(),
        0x05,0x0C,0x09,0x01,0xA1.toByte(),0x01,0x85.toByte(),0x02,
        0x15,0x00,0x26,0xFF.toByte(),0x03,0x19,0x00,0x2A,0xFF.toByte(),0x03,0x75,0x10,0x95,0x01,0x81.toByte(),0x00,0xC0.toByte()
    )

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "startHid" -> startHid(result)
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
        pendingStart = result
        val opened = adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (profile != BluetoothProfile.HID_DEVICE || proxy !is BluetoothHidDevice) return
                hid = proxy
                val callback = object : BluetoothHidDevice.Callback() {
                    override fun onAppStatusChanged(device: android.bluetooth.BluetoothDevice?, registered: Boolean) {
                        appRegistered = registered
                        if (registered) {
                            try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)) } catch (_: Exception) {}
                            pendingStart?.success("ready"); pendingStart = null
                        } else {
                            pendingStart?.error("REGISTER_FAILED", "Bluetooth HID registration failed.", null); pendingStart = null
                        }
                    }
                    override fun onConnectionStateChanged(device: android.bluetooth.BluetoothDevice?, state: Int) { }
                }
                try {
                    val sdp = BluetoothHidDeviceAppSdpSettings("BD TV Remote", "Bluetooth HID TV Remote", "BD Remote", BluetoothHidDevice.SUBCLASS1_COMBO, descriptor)
                    val accepted = proxy.registerApp(sdp, null, null, executor, callback)
                    if (!accepted) { pendingStart?.error("REGISTER_FAILED", "Phone rejected Bluetooth HID registration.", null); pendingStart = null }
                } catch (e: SecurityException) {
                    pendingStart?.error("PERMISSION", e.message, null); pendingStart = null
                }
            }
            override fun onServiceDisconnected(profile: Int) { hid = null; appRegistered = false }
        }, BluetoothProfile.HID_DEVICE)
        if (!opened) { result.error("PROFILE_UNAVAILABLE", "This phone does not expose the Bluetooth HID Device profile.", null); pendingStart = null }
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

    private fun connectedDevice(): android.bluetooth.BluetoothDevice? {
        if (!appRegistered || hid == null) return null
        return try { hid?.connectedDevices?.firstOrNull() } catch (_: SecurityException) { null }
    }

    private fun sendKeyboardKey(key: Int, result: MethodChannel.Result) {
        val device = connectedDevice()
        if (device == null) { result.error("NOT_PAIRED", "Pair this phone from the TV Bluetooth accessories menu first.", null); return }
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
        if (device == null) { result.error("NOT_PAIRED", "Pair this phone from the TV Bluetooth accessories menu first.", null); return }
        try {
            val press = byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte())
            val release = byteArrayOf(0, 0)
            val sent = hid?.sendReport(device, 2, press) == true
            hid?.sendReport(device, 2, release)
            if (sent) result.success(true) else result.error("SEND_FAILED", "TV did not accept this media key.", null)
        } catch (e: SecurityException) { result.error("PERMISSION", e.message, null) }
    }
}
