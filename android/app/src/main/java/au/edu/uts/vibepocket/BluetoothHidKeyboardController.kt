package au.edu.uts.vibepocket

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class HidHost(
    val address: String,
    val name: String,
)

data class HidKeyboardState(
    val supported: Boolean = true,
    val enabled: Boolean = false,
    val registered: Boolean = false,
    val pairedHosts: List<HidHost> = emptyList(),
    val connectedHostAddress: String? = null,
    val connectingHostAddress: String? = null,
    val message: String = "Enable Bluetooth keyboard to pair this phone with your Mac.",
) {
    val connected: Boolean get() = connectedHostAddress != null
}

class BluetoothHidKeyboardController(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val reportExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedHost: BluetoothDevice? = null

    private val _state = MutableStateFlow(
        HidKeyboardState(
            supported = adapter != null,
            enabled = runCatching { adapter?.isEnabled == true }.getOrDefault(false),
            message = if (adapter == null) {
                "Bluetooth HID is not supported on this device."
            } else {
                "Enable Bluetooth keyboard to pair this phone with your Mac."
            },
        ),
    )
    val state: StateFlow<HidKeyboardState> = _state.asStateFlow()

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE || closed.get()) return
            hidDevice = proxy as BluetoothHidDevice
            registerApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = null
            connectedHost = null
            _state.value = _state.value.copy(
                registered = false,
                connectedHostAddress = null,
                connectingHostAddress = null,
                message = "Bluetooth HID service disconnected.",
            )
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            connectedHost = pluggedDevice.takeIf { registered }
            _state.value = _state.value.copy(
                registered = registered,
                connectedHostAddress = pluggedDevice?.address.takeIf { registered },
                connectingHostAddress = null,
                message = if (registered) {
                    "Bluetooth keyboard ready. Pair or connect a Mac."
                } else {
                    "Bluetooth keyboard registration failed. Toggle Bluetooth and try again."
                },
            )
            refreshHosts()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedHost = device
                    _state.value = _state.value.copy(
                        connectedHostAddress = device.address,
                        connectingHostAddress = null,
                        message = "Connected to ${safeName(device)}. Standard controls now use Bluetooth HID.",
                    )
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    _state.value = _state.value.copy(
                        connectingHostAddress = device.address,
                        message = "Connecting Bluetooth keyboard to ${safeName(device)}...",
                    )
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedHost?.address == device.address) connectedHost = null
                    _state.value = _state.value.copy(
                        connectedHostAddress = null,
                        connectingHostAddress = null,
                        message = "Disconnected from ${safeName(device)}. Bridge fallback remains available.",
                    )
                }
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> refreshHosts()
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val enabled = adapter?.isEnabled == true
                    _state.value = _state.value.copy(
                        enabled = enabled,
                        message = if (enabled) _state.value.message else "Bluetooth is off. Enable it to use virtual hardware.",
                    )
                    if (enabled) start()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (closed.get() || adapter == null) return false
        if (!hasPermissions()) {
            _state.value = _state.value.copy(message = "Nearby devices permission is required for Bluetooth keyboard.")
            return false
        }
        if (!adapter.isEnabled) {
            _state.value = _state.value.copy(enabled = false, message = "Bluetooth is off. Enable it to use virtual hardware.")
            return false
        }
        registerReceiver()
        refreshHosts()
        if (hidDevice != null || _state.value.registered) return true
        _state.value = _state.value.copy(enabled = true, message = "Starting Bluetooth keyboard...")
        return adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String): Boolean {
        if (!hasPermissions() || !_state.value.registered) return false
        val device = adapter?.bondedDevices?.firstOrNull { it.address == address } ?: return false
        _state.value = _state.value.copy(
            connectingHostAddress = address,
            message = "Connecting Bluetooth keyboard to ${safeName(device)}...",
        )
        return hidDevice?.connect(device) == true
    }

    @SuppressLint("MissingPermission")
    fun send(action: ControllerAction?): Boolean {
        val chords = CodexHidMapping.chords(action) ?: return false
        if (!hasPermissions()) return false
        val device = connectedHost ?: return false
        val proxy = hidDevice ?: return false
        reportExecutor.execute {
            try {
                chords.forEach { chord ->
                    if (!proxy.sendReport(device, REPORT_ID, HidKeyboardReport.encode(chord))) return@execute
                    Thread.sleep(KEY_HOLD_MILLIS)
                    if (!proxy.sendReport(device, REPORT_ID, HidKeyboardReport.release)) return@execute
                    Thread.sleep(KEY_GAP_MILLIS)
                }
            } catch (_: SecurityException) {
                connectedHost = null
                _state.value = _state.value.copy(
                    connectedHostAddress = null,
                    message = "Nearby devices permission was removed. Bridge fallback remains available.",
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    fun refreshHosts() {
        if (!hasPermissions()) return
        val hosts = adapter?.bondedDevices.orEmpty()
            .map { HidHost(it.address, safeName(it)) }
            .sortedWith(compareBy<HidHost> { it.name.lowercase() }.thenBy { it.address })
        _state.value = _state.value.copy(
            enabled = adapter?.isEnabled == true,
            pairedHosts = hosts,
        )
    }

    fun hasPermissions(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || (
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        )

    @SuppressLint("MissingPermission")
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { hidDevice?.unregisterApp() }
        runCatching { hidDevice?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } }
        if (receiverRegistered.compareAndSet(true, false)) runCatching { appContext.unregisterReceiver(bluetoothReceiver) }
        hidDevice = null
        connectedHost = null
        callbackExecutor.shutdownNow()
        reportExecutor.shutdownNow()
    }

    @SuppressLint("MissingPermission")
    private fun registerApp() {
        val proxy = hidDevice ?: return
        val settings = BluetoothHidDeviceAppSdpSettings(
            "Vibe Pocket",
            "Codex virtual keyboard",
            "Vibe Pocket",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            KEYBOARD_DESCRIPTOR,
        )
        val registered = proxy.registerApp(
            settings,
            null as BluetoothHidDeviceAppQosSettings?,
            null as BluetoothHidDeviceAppQosSettings?,
            callbackExecutor,
            hidCallback,
        )
        if (!registered) {
            _state.value = _state.value.copy(message = "Android rejected Bluetooth keyboard registration.")
        }
    }

    private fun registerReceiver() {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(bluetoothReceiver, filter)
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String = runCatching { device.name }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: device.address

    companion object {
        private const val REPORT_ID = 0
        private const val KEY_HOLD_MILLIS = 24L
        private const val KEY_GAP_MILLIS = 12L

        private val KEYBOARD_DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01,
            0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
            0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
            0xC0.toByte(),
        )
    }
}
