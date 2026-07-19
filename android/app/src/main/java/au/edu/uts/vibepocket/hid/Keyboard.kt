package au.edu.uts.vibepocket.hid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
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
import au.edu.uts.vibepocket.input.Hid
import au.edu.uts.vibepocket.profile.Action
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class Host(
    val address: String,
    val name: String,
)

data class Status(
    val supported: Boolean = true,
    val enabled: Boolean = false,
    val registered: Boolean = false,
    val pairedHosts: List<Host> = emptyList(),
    val connectedHostAddress: String? = null,
    val connectingHostAddress: String? = null,
    val message: String = "Enable Bluetooth keyboard to pair this phone with your Mac.",
) {
    val connected: Boolean get() = connectedHostAddress != null
}

internal fun preferred(
    registered: Boolean,
    connectedAddress: String?,
    connectingAddress: String?,
    preferredAddress: String?,
    bondedAddresses: Set<String>,
): String? = preferredAddress?.takeIf {
    registered && connectedAddress == null && connectingAddress == null && it in bondedAddresses
}

internal fun delay(attempt: Int): Long? = when (attempt) {
    0 -> 500L
    1 -> 1_200L
    2 -> 2_500L
    else -> null
}

internal fun infer(
    registered: Boolean,
    connectedAddress: String?,
    connectingAddress: String?,
    preferredAddress: String?,
    bondedAddresses: Set<String>,
    computerAddresses: Set<String>,
): String? = preferred(
    registered = registered,
    connectedAddress = connectedAddress,
    connectingAddress = connectingAddress,
    preferredAddress = preferredAddress,
    bondedAddresses = bondedAddresses,
) ?: computerAddresses.singleOrNull()?.takeIf {
    registered && connectedAddress == null && connectingAddress == null && it in bondedAddresses
}

internal fun newlyBondedComputer(address: String?, bonded: Boolean, computer: Boolean): String? =
    address?.takeIf { bonded && computer }

class Keyboard(context: Context) : AutoCloseable, Hid {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val reportExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val repeatExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val closed = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)
    private val repeatLock = Any()
    private val heldKeyLock = Any()
    private val reconnectLock = Any()
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedHost: BluetoothDevice? = null
    private var navigationRepeat: ScheduledFuture<*>? = null
    private var heldChord: Chord? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var autoReconnectAttempted = false
    private var reconnectAttempts = 0

    private val _state = MutableStateFlow(
        Status(
            supported = adapter != null,
            enabled = runCatching { adapter?.isEnabled == true }.getOrDefault(false),
            message = if (adapter == null) {
                "Bluetooth HID is not supported on this device."
            } else {
                "Enable Bluetooth keyboard to pair this phone with your Mac."
            },
        ),
    )
    val state: StateFlow<Status> = _state.asStateFlow()

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE || closed.get()) return
            hidDevice = proxy as BluetoothHidDevice
            registerApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            stopRepeat()
            synchronized(heldKeyLock) { heldChord = null }
            hidDevice = null
            connectedHost = null
            autoReconnectAttempted = false
            resetPreferredReconnect()
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
            if (registered) {
                autoReconnectAttempted = false
                resetPreferredReconnect()
            }
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
            reconcileConnectedHost()
            refreshHosts()
            reconnectPreferredHost()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedHost = device
                    rememberPreferredHost(device.address)
                    resetPreferredReconnect()
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
                    stopRepeat()
                    synchronized(heldKeyLock) { heldChord = null }
                    if (connectedHost?.address == device.address) connectedHost = null
                    _state.value = _state.value.copy(
                        connectedHostAddress = null,
                        connectingHostAddress = null,
                        message = "Disconnected from ${safeName(device)}. Bridge fallback remains available.",
                    )
                    schedulePreferredReconnect()
                }
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    if (!hasPermissions()) return
                    refreshHosts()
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val deviceAddress = runCatching { device?.address }.getOrNull()
                    val isComputer = runCatching {
                        device?.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
                    }.getOrDefault(false)
                    val address = newlyBondedComputer(
                        address = deviceAddress,
                        bonded = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE) ==
                            BluetoothDevice.BOND_BONDED,
                        computer = isComputer,
                    )
                    if (address != null) {
                        rememberPreferredHost(address)
                        autoReconnectAttempted = false
                        resetPreferredReconnect()
                        reconnectPreferredHost()
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val enabled = adapter?.isEnabled == true
                    _state.value = _state.value.copy(
                        enabled = enabled,
                        message = if (enabled) _state.value.message else "Bluetooth is off. Enable it to use virtual hardware.",
                    )
                    if (!enabled) {
                        autoReconnectAttempted = false
                        resetPreferredReconnect()
                    }
                    if (enabled) {
                        start()
                        reconnectPreferredHost()
                    }
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
        if (hidDevice != null || _state.value.registered) {
            reconnectPreferredHost()
            return true
        }
        _state.value = _state.value.copy(enabled = true, message = "Starting Bluetooth keyboard...")
        return adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String): Boolean {
        return connect(address, rememberHost = true)
    }

    @SuppressLint("MissingPermission")
    private fun connect(address: String, rememberHost: Boolean): Boolean {
        if (!hasPermissions() || !_state.value.registered) return false
        val device = adapter?.bondedDevices?.firstOrNull { it.address == address } ?: return false
        if (rememberHost) {
            rememberPreferredHost(address)
            resetPreferredReconnect()
        }
        stopRepeat()
        _state.value = _state.value.copy(
            connectingHostAddress = address,
            message = "Connecting Bluetooth keyboard to ${safeName(device)}...",
        )
        val requested = hidDevice?.connect(device) == true
        if (!requested) {
            _state.value = _state.value.copy(
                connectingHostAddress = null,
                message = "Could not connect Bluetooth keyboard to ${safeName(device)}. Bridge fallback remains available.",
            )
        }
        return requested
    }

    @SuppressLint("MissingPermission")
    override fun send(action: Action): Boolean {
        val chords = Mapping.chords(action) ?: return false
        if (!hasPermissions()) return false
        val device = connectedHost ?: return false
        val proxy = hidDevice ?: return false
        reportExecutor.execute {
            try {
                chords.forEach { chord ->
                    if (!proxy.sendReport(device, REPORT_ID, Report.encode(chord))) return@execute
                    Thread.sleep(KEY_HOLD_MILLIS)
                    if (!proxy.sendReport(device, REPORT_ID, Report.release)) return@execute
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
    override fun press(action: Action): Boolean {
        val chord = Mapping.chords(action)?.singleOrNull() ?: return false
        if (!hasPermissions()) return false
        val device = connectedHost ?: return false
        val proxy = hidDevice ?: return false
        synchronized(heldKeyLock) {
            if (heldChord != null) return false
            heldChord = chord
        }
        reportExecutor.execute {
            try {
                if (!proxy.sendReport(device, REPORT_ID, Report.encode(chord))) {
                    synchronized(heldKeyLock) { if (heldChord == chord) heldChord = null }
                }
            } catch (_: SecurityException) {
                synchronized(heldKeyLock) { heldChord = null }
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    override fun release(action: Action): Boolean {
        val chord = Mapping.chords(action)?.singleOrNull() ?: return false
        val shouldRelease = synchronized(heldKeyLock) {
            if (heldChord != chord) false else {
                heldChord = null
                true
            }
        }
        if (!shouldRelease) return false
        val device = connectedHost ?: return true
        val proxy = hidDevice ?: return true
        reportExecutor.execute {
            runCatching { proxy.sendReport(device, REPORT_ID, Report.release) }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    override fun releaseAny(): Boolean {
        val shouldRelease = synchronized(heldKeyLock) {
            (heldChord != null).also { heldChord = null }
        }
        if (!shouldRelease) return false
        val device = connectedHost ?: return true
        val proxy = hidDevice ?: return true
        reportExecutor.execute {
            runCatching { proxy.sendReport(device, REPORT_ID, Report.release) }
        }
        return true
    }

    override fun repeat(action: Action): Boolean {
        if (action.type != "navigate" || !send(action)) return false
        synchronized(repeatLock) {
            navigationRepeat?.cancel(false)
            navigationRepeat = repeatExecutor.scheduleAtFixedRate(
                { send(action) },
                NAVIGATION_REPEAT_INITIAL_DELAY_MILLIS,
                NAVIGATION_REPEAT_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }
        return true
    }

    override fun stopRepeat() {
        synchronized(repeatLock) {
            navigationRepeat?.cancel(false)
            navigationRepeat = null
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshHosts() {
        if (!hasPermissions()) return
        reconcileConnectedHost()
        val hosts = adapter?.bondedDevices.orEmpty()
            .map { Host(it.address, safeName(it)) }
            .sortedWith(compareBy<Host> { it.name.lowercase() }.thenBy { it.address })
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
        stopRepeat()
        synchronized(heldKeyLock) { heldChord = null }
        val device = connectedHost
        val proxy = hidDevice
        if (device != null && proxy != null && hasPermissions()) {
            runCatching {
                reportExecutor.submit {
                    proxy.sendReport(device, REPORT_ID, Report.release)
                }.get(FINAL_RELEASE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }
        }
        resetPreferredReconnect()
        runCatching { hidDevice?.unregisterApp() }
        runCatching { hidDevice?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } }
        if (receiverRegistered.compareAndSet(true, false)) runCatching { appContext.unregisterReceiver(bluetoothReceiver) }
        hidDevice = null
        connectedHost = null
        callbackExecutor.shutdownNow()
        reportExecutor.shutdownNow()
        repeatExecutor.shutdownNow()
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
    private fun reconcileConnectedHost() {
        val proxy = hidDevice ?: return
        val actual = runCatching { proxy.connectedDevices.firstOrNull() }.getOrNull()
        if (actual != null) {
            connectedHost = actual
            _state.value = _state.value.copy(
                connectedHostAddress = actual.address,
                connectingHostAddress = null,
                message = "Connected to ${safeName(actual)}. Standard controls now use Bluetooth HID.",
            )
            return
        }
        if (connectedHost != null || _state.value.connectedHostAddress != null) {
            stopRepeat()
            connectedHost = null
            _state.value = _state.value.copy(
                connectedHostAddress = null,
                connectingHostAddress = null,
                message = "Bluetooth keyboard ready. Pair or connect a Mac.",
            )
            schedulePreferredReconnect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun reconnectPreferredHost() {
        if (autoReconnectAttempted) return
        val address = preferredReconnectAddress() ?: return
        autoReconnectAttempted = true
        if (!connect(address, rememberHost = false)) schedulePreferredReconnect()
    }

    @SuppressLint("MissingPermission")
    private fun preferredReconnectAddress(): String? {
        val bondedDevices = adapter?.bondedDevices.orEmpty()
        val address = infer(
            registered = _state.value.registered,
            connectedAddress = _state.value.connectedHostAddress,
            connectingAddress = _state.value.connectingHostAddress,
            preferredAddress = preferences.getString(PREFERRED_HOST_ADDRESS_KEY, null),
            bondedAddresses = bondedDevices.mapTo(mutableSetOf()) { it.address },
            computerAddresses = bondedDevices
                .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER }
                .mapTo(mutableSetOf()) { it.address },
        )
        if (address != null && preferences.getString(PREFERRED_HOST_ADDRESS_KEY, null) == null) {
            rememberPreferredHost(address)
        }
        return address
    }

    private fun schedulePreferredReconnect() {
        if (closed.get() || !hasPermissions() || preferredReconnectAddress() == null) return
        synchronized(reconnectLock) {
            if (reconnectFuture != null) return
            val waitMillis = delay(reconnectAttempts) ?: return
            reconnectAttempts += 1
            reconnectFuture = repeatExecutor.schedule({
                synchronized(reconnectLock) { reconnectFuture = null }
                autoReconnectAttempted = false
                reconnectPreferredHost()
            }, waitMillis, TimeUnit.MILLISECONDS)
        }
    }

    private fun resetPreferredReconnect() {
        synchronized(reconnectLock) {
            reconnectFuture?.cancel(false)
            reconnectFuture = null
            reconnectAttempts = 0
        }
    }

    private fun rememberPreferredHost(address: String) {
        preferences.edit().putString(PREFERRED_HOST_ADDRESS_KEY, address).apply()
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String = runCatching { device.name }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: device.address

    companion object {
        private const val PREFERENCES_NAME = "vibe-pocket-hid"
        private const val PREFERRED_HOST_ADDRESS_KEY = "preferred-host-address"
        private const val REPORT_ID = 0
        private const val KEY_HOLD_MILLIS = 24L
        private const val KEY_GAP_MILLIS = 12L
        private const val FINAL_RELEASE_TIMEOUT_MILLIS = 250L
        private const val NAVIGATION_REPEAT_INITIAL_DELAY_MILLIS = 280L
        private const val NAVIGATION_REPEAT_INTERVAL_MILLIS = 90L

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
