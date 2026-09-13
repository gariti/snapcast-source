package com.lattice.app

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the phone is playing through — speaker, wired, Bluetooth, USB — and
 * the Bluetooth device's battery when it reports one. Published as
 * `{t:"audio"}` on every change and again on every (re)connect, so the
 * desktop's status bar can show it and the user can decide to listen to the
 * desktop through those headphones.
 *
 * Route detection is `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` filtered
 * to the kinds that mean "on your ears / not the built-in speaker", refreshed
 * by [AudioDeviceCallback]. Battery comes from the (hidden-but-stable)
 * `android.bluetooth.device.action.BATTERY_LEVEL_CHANGED` broadcast, which
 * needs BLUETOOTH_CONNECT on 12+; without the grant the route is still
 * reported, only without a percentage.
 */
object AudioRouteMonitor {
    private const val TAG = "AudioRoute"
    private const val ACTION_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private const val EXTRA_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"

    data class Route(val out: String, val name: String, val battery: Int?) {
        val onEars: Boolean get() = out != "speaker"
    }

    private val _route = MutableStateFlow(Route("speaker", "", null))
    val route: StateFlow<Route> = _route.asStateFlow()

    private var attached = false
    private var client: ControlChannelClient? = null
    private var pushJob: Job? = null
    private var battery: MutableMap<String, Int> = mutableMapOf()

    fun attach(context: Context, link: ControlChannelClient, scope: CoroutineScope) {
        client = link
        val app = context.applicationContext
        if (!attached) {
            attached = true
            val am = app.getSystemService(AudioManager::class.java)
            am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refresh(app)
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refresh(app)
            }, Handler(Looper.getMainLooper()))
            if (hasBtPermission(app)) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        val level = intent.getIntExtra(EXTRA_BATTERY, -1)
                        if (level in 0..100) {
                            battery[dev.address] = level
                            refresh(app)
                        }
                    }
                }
                ContextCompat.registerReceiver(
                    app, receiver, IntentFilter(ACTION_BATTERY), ContextCompat.RECEIVER_EXPORTED,
                )
            }
        }
        refresh(app)
        // Re-announce on every link-up: the desktop's state file resets when
        // the phone unlinks.
        pushJob?.cancel()
        pushJob = scope.launch {
            link.state.collect { st ->
                if (st is ControlChannelClient.LinkState.Connected && st.proto >= 2) push()
            }
        }
    }

    fun hasBtPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Re-read the route (also called after a permission grant). */
    fun refresh(context: Context) {
        val am = context.getSystemService(AudioManager::class.java)
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        // Preference order: the thing most likely to be on your ears.
        val pick = devices.firstOrNull { it.type in BT } ?: devices.firstOrNull { it.type in WIRED }
            ?: devices.firstOrNull { it.type in USB }
        val route = when {
            pick == null -> Route("speaker", "", null)
            pick.type in BT -> Route("bt", pick.productName.toString(), btBattery(context, pick))
            pick.type in WIRED -> Route("wired", pick.productName.toString().ifBlank { "Wired headphones" }, null)
            else -> Route("usb", pick.productName.toString().ifBlank { "USB audio" }, null)
        }
        if (route != _route.value) {
            Log.i(TAG, "route -> $route")
            _route.value = route
            push()
        }
    }

    private fun btBattery(context: Context, dev: AudioDeviceInfo): Int? {
        if (!hasBtPermission(context)) return null
        val addr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) dev.address else return null
        battery[addr]?.let { return it }
        // Ask the device object directly; the getter is hidden API on some
        // releases, so a failure just means "no percentage yet".
        return try {
            val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return null
            val remote = adapter.getRemoteDevice(addr)
            val m = BluetoothDevice::class.java.getMethod("getBatteryLevel")
            (m.invoke(remote) as? Int)?.takeIf { it in 0..100 }
        } catch (_: Throwable) {
            null
        }
    }

    private fun push() {
        val r = _route.value
        client?.let { if (it.sessionReady || it.state.value is ControlChannelClient.LinkState.Connected) Link.audioRoute(r.out, r.name, r.battery) }
    }

    private val BT = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO) +
        (if (Build.VERSION.SDK_INT >= 31) setOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER) else emptySet()) +
        (if (Build.VERSION.SDK_INT >= 33) setOf(AudioDeviceInfo.TYPE_BLE_BROADCAST) else emptySet())
    private val WIRED = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG)
    private val USB = setOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY)
}
