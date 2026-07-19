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
    private val profileLock = Any()
    private val repeatLock = Any()
    private val heldKeyLock = Any()
    private val reconnectLock = Any()
    private val profileLifecycle = ProfileLifecycle()
    private val reportQueue = ReportQueue(reportExecutor)
    @Volatile private var hidDevice: BluetoothHidDevice? = null
    @Volatile private var connectedHost: BluetoothDevice? = null
    private var navigationRepeat: ScheduledFuture<*>? = null
    private var repeatGeneration = 0L
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

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    if (!hasConnectPermission()) return
                    refreshHosts()
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val deviceAddress = runCatching { device?.address }.getOrNull()
                    val isComputer = device?.let(::isComputer) == true
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
                    val enabled = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
                    val canReconnect = reconnectAfterAdapterChange(enabled, hasConnectPermission())
                    _state.value = _state.value.copy(
                        enabled = enabled,
                        message = when {
                            !enabled -> "Bluetooth is off. Enable it to use virtual hardware."
                            !canReconnect -> "Nearby devices permission is required for Bluetooth keyboard."
                            else -> _state.value.message
                        },
                    )
                    if (!enabled) {
                        autoReconnectAttempted = false
                        resetPreferredReconnect()
                    }
                    if (canReconnect) {
                        start()
                        reconnectPreferredHost()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (closed.get()) return false
        val bluetoothAdapter = adapter ?: return false
        registerReceiver()
        if (!hasConnectPermission()) {
            _state.value = _state.value.copy(message = "Nearby devices permission is required for Bluetooth keyboard.")
            return false
        }
        if (!bluetoothAdapter.isEnabled) {
            _state.value = _state.value.copy(enabled = false, message = "Bluetooth is off. Enable it to use virtual hardware.")
            return false
        }
        refreshHosts()
        val proxy = synchronized(profileLock) { hidDevice }
        if (proxy != null) {
            registerApp(proxy)
            reconnectPreferredHost()
            return true
        }
        val generation = synchronized(profileLock) { profileLifecycle.requestProxy() } ?: return true
        _state.value = _state.value.copy(enabled = true, message = "Starting Bluetooth keyboard...")
        val requested = runCatching {
            bluetoothAdapter.getProfileProxy(appContext, profileListener(generation), BluetoothProfile.HID_DEVICE)
        }.getOrDefault(false)
        if (!requested) {
            synchronized(profileLock) { profileLifecycle.rejectProxy(generation) }
            _state.value = _state.value.copy(message = "Could not start the Bluetooth HID service.")
        }
        return requested
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String): Boolean {
        return connect(address, rememberHost = true)
    }

    @SuppressLint("MissingPermission")
    private fun connect(address: String, rememberHost: Boolean): Boolean {
        if (!hasConnectPermission() || !_state.value.registered) return false
        val device = runCatching {
            adapter?.bondedDevices?.firstOrNull { safeAddress(it) == address && isComputer(it) }
        }.getOrElse {
            permissionUnavailable()
            null
        } ?: return false
        if (rememberHost) {
            rememberPreferredHost(address)
            resetPreferredReconnect()
        }
        stopRepeat()
        _state.value = _state.value.copy(
            connectingHostAddress = address,
            message = "Connecting Bluetooth keyboard to ${safeName(device)}...",
        )
        val proxy = synchronized(profileLock) {
            hidDevice?.takeIf { profileLifecycle.isRegistered() }
        }
        val requested = proxy?.connect(device) == true
        if (!requested) {
            _state.value = _state.value.copy(
                connectingHostAddress = null,
                message = "Could not connect Bluetooth keyboard to ${safeName(device)}. Bridge fallback remains available.",
            )
        }
        return requested
    }

    @SuppressLint("MissingPermission")
    override fun send(action: Action, completion: (Boolean) -> Unit): Boolean {
        val chords = Mapping.chords(action) ?: return false
        if (!hasConnectPermission()) return false
        val device = connectedHost ?: return false
        val transport = currentTransport() ?: return false
        return enqueueReport {
            val result = if (!isCurrentTransport(transport, device)) {
                TransactionResult.KEY_DOWN_FAILED
            } else {
                try {
                    sendTransaction(
                        chords = chords,
                        send = { report -> transport.proxy.sendReport(device, REPORT_ID, report) },
                        pause = Thread::sleep,
                        holdMillis = KEY_HOLD_MILLIS,
                        gapMillis = KEY_GAP_MILLIS,
                    )
                } catch (_: SecurityException) {
                    permissionRemoved(device)
                    TransactionResult.KEY_DOWN_FAILED
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    TransactionResult.KEY_DOWN_FAILED
                }
            }
            if (result == TransactionResult.RELEASE_FAILED) recoverReleaseFailure(transport, device)
            runCatching { completion(result == TransactionResult.COMPLETE) }
        }
    }

    @SuppressLint("MissingPermission")
    override fun press(action: Action): Boolean {
        val chord = Mapping.chords(action)?.singleOrNull() ?: return false
        if (!hasConnectPermission()) return false
        val device = connectedHost ?: return false
        val transport = currentTransport() ?: return false
        synchronized(heldKeyLock) {
            if (heldChord != null) return false
            heldChord = chord
        }
        val delivered = reportQueue.submitAndWait {
            try {
                val sent = isCurrentTransport(transport, device) &&
                    transport.proxy.sendReport(device, REPORT_ID, Report.encode(chord))
                if (!sent) {
                    synchronized(heldKeyLock) { if (heldChord == chord) heldChord = null }
                }
                sent
            } catch (_: SecurityException) {
                synchronized(heldKeyLock) { heldChord = null }
                permissionRemoved(device)
                false
            }
        }
        if (!delivered) synchronized(heldKeyLock) { if (heldChord == chord) heldChord = null }
        return delivered
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
        val device = connectedHost ?: return false
        val transport = currentTransport() ?: return false
        return reportQueue.submitAndWait {
            if (!isCurrentTransport(transport, device)) return@submitAndWait false
            val result = runCatching {
                transport.proxy.sendReport(device, REPORT_ID, Report.release)
            }
            if (result.exceptionOrNull() is SecurityException) {
                permissionRemoved(device)
                return@submitAndWait false
            }
            val delivered = result.getOrDefault(false)
            if (!delivered) recoverReleaseFailure(transport, device)
            delivered
        }
    }

    @SuppressLint("MissingPermission")
    override fun releaseAny(): Boolean {
        val shouldRelease = synchronized(heldKeyLock) {
            (heldChord != null).also { heldChord = null }
        }
        if (!shouldRelease) return false
        val device = connectedHost ?: return false
        val transport = currentTransport() ?: return false
        return reportQueue.submitAndWait {
            if (!isCurrentTransport(transport, device)) return@submitAndWait false
            val result = runCatching {
                transport.proxy.sendReport(device, REPORT_ID, Report.release)
            }
            if (result.exceptionOrNull() is SecurityException) {
                permissionRemoved(device)
                return@submitAndWait false
            }
            val delivered = result.getOrDefault(false)
            if (!delivered) recoverReleaseFailure(transport, device)
            delivered
        }
    }

    override fun repeat(action: Action, completion: (Boolean) -> Unit): Boolean {
        if (action.type != "navigate") return false
        val generation = synchronized(repeatLock) {
            repeatGeneration += 1
            navigationRepeat?.cancel(false)
            navigationRepeat = null
            repeatGeneration
        }
        return send(action) { delivered ->
            if (delivered) {
                synchronized(repeatLock) {
                    if (generation == repeatGeneration && !closed.get()) {
                        navigationRepeat = repeatExecutor.scheduleWithFixedDelay(
                            {
                                if (!send(action) { repeated -> if (!repeated) stopRepeat() }) stopRepeat()
                            },
                            NAVIGATION_REPEAT_INITIAL_DELAY_MILLIS,
                            NAVIGATION_REPEAT_INTERVAL_MILLIS,
                            TimeUnit.MILLISECONDS,
                        )
                    }
                }
            }
            completion(delivered)
        }
    }

    override fun stopRepeat() {
        synchronized(repeatLock) {
            repeatGeneration += 1
            navigationRepeat?.cancel(false)
            navigationRepeat = null
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshHosts() {
        if (!hasConnectPermission()) return
        reconcileConnectedHost()
        val hosts = runCatching {
            adapter?.bondedDevices.orEmpty()
                .filter(::isComputer)
                .mapNotNull { device -> safeAddress(device)?.let { Host(it, safeName(device)) } }
                .sortedWith(compareBy<Host> { it.name.lowercase() }.thenBy { it.address })
        }.getOrElse {
            permissionUnavailable()
            emptyList()
        }
        _state.value = _state.value.copy(
            enabled = adapter?.isEnabled == true,
            pairedHosts = hosts,
        )
    }

    fun hasConnectPermission(): Boolean = hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    fun hasAdvertisePermission(): Boolean = hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)

    fun hasPermissions(): Boolean = hasConnectPermission() && hasAdvertisePermission()

    @SuppressLint("MissingPermission")
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val closing = reportQueue.close {
            val device = connectedHost
            val proxy = hidDevice
            if (device != null && proxy != null && hasConnectPermission()) {
                runCatching { proxy.sendReport(device, REPORT_ID, Report.release) }
            }
        }
        check(closing) { "HID report queue closed before keyboard lifecycle" }
        stopRepeat()
        synchronized(heldKeyLock) { heldChord = null }
        resetPreferredReconnect()
        val proxy = synchronized(profileLock) {
            profileLifecycle.close()
            hidDevice.also { hidDevice = null }
        }
        runCatching { proxy?.unregisterApp() }
        runCatching { proxy?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } }
        if (receiverRegistered.compareAndSet(true, false)) runCatching { appContext.unregisterReceiver(bluetoothReceiver) }
        connectedHost = null
        callbackExecutor.shutdownNow()
        repeatExecutor.shutdownNow()
    }

    @SuppressLint("MissingPermission")
    private fun registerApp(proxy: BluetoothHidDevice) {
        val settings = BluetoothHidDeviceAppSdpSettings(
            "Vibe Pocket",
            "Codex virtual keyboard",
            "Vibe Pocket",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            KEYBOARD_DESCRIPTOR,
        )
        var token: Registration? = null
        val registered = synchronized(profileLock) {
            if (closed.get() || hidDevice !== proxy) return
            token = profileLifecycle.requestRegistration()
            val currentToken = token ?: return
            runCatching {
                proxy.registerApp(
                    settings,
                    null as BluetoothHidDeviceAppQosSettings?,
                    null as BluetoothHidDeviceAppQosSettings?,
                    callbackExecutor,
                    hidCallback(currentToken),
                )
            }.getOrDefault(false)
        }
        if (!registered) {
            synchronized(profileLock) { token?.let(profileLifecycle::rejectRegistration) }
            _state.value = _state.value.copy(message = "Android rejected Bluetooth keyboard registration.")
        }
    }

    private fun profileListener(generation: Long) = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, profileProxy: BluetoothProfile) {
            val proxy = profileProxy as? BluetoothHidDevice
            val accepted = synchronized(profileLock) {
                if (
                    profile == BluetoothProfile.HID_DEVICE &&
                    proxy != null &&
                    !closed.get() &&
                    profileLifecycle.acceptProxy(generation)
                ) {
                    hidDevice = proxy
                    true
                } else {
                    false
                }
            }
            if (!accepted) {
                if (profile == BluetoothProfile.HID_DEVICE) closeProxy(profileProxy)
                return
            }
            registerApp(requireNotNull(proxy))
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val lost = synchronized(profileLock) {
                if (!profileLifecycle.loseProxy(generation)) return@synchronized false
                hidDevice = null
                true
            }
            if (!lost) return
            stopRepeat()
            synchronized(heldKeyLock) { heldChord = null }
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

    private fun hidCallback(token: Registration) = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            val accepted = synchronized(profileLock) {
                profileLifecycle.registrationChanged(token, registered)
            }
            if (!accepted) return
            if (registered) {
                autoReconnectAttempted = false
                resetPreferredReconnect()
            } else {
                stopRepeat()
                synchronized(heldKeyLock) { heldChord = null }
            }
            connectedHost = pluggedDevice?.takeIf { registered && isComputer(it) }
            _state.value = _state.value.copy(
                registered = registered,
                connectedHostAddress = safeAddress(connectedHost),
                connectingHostAddress = null,
                message = if (registered) {
                    "Bluetooth keyboard ready. Pair or connect a Mac."
                } else {
                    "Bluetooth keyboard registration ended. Return to the app to restart it."
                },
            )
            if (registered) {
                reconcileConnectedHost()
                refreshHosts()
                reconnectPreferredHost()
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            val proxy = synchronized(profileLock) {
                hidDevice?.takeIf { profileLifecycle.isCurrent(token) }
            } ?: return
            if (!isComputer(device)) {
                val canDisconnect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ContextCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    ) == PackageManager.PERMISSION_GRANTED
                if (canDisconnect) {
                    try {
                        proxy.disconnect(device)
                    } catch (_: SecurityException) {
                        Unit
                    }
                }
                return
            }
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val address = safeAddress(device) ?: run {
                        permissionUnavailable()
                        return
                    }
                    connectedHost = device
                    rememberPreferredHost(address)
                    resetPreferredReconnect()
                    _state.value = _state.value.copy(
                        connectedHostAddress = address,
                        connectingHostAddress = null,
                        message = "Connected to ${safeName(device)}. Standard controls now use Bluetooth HID.",
                    )
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    val address = safeAddress(device) ?: run {
                        permissionUnavailable()
                        return
                    }
                    _state.value = _state.value.copy(
                        connectingHostAddress = address,
                        message = "Connecting Bluetooth keyboard to ${safeName(device)}...",
                    )
                }
                BluetoothProfile.STATE_DISCONNECTED -> reconcileDisconnect(proxy, device)
            }
        }
    }

    private fun registerReceiver() {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(bluetoothReceiver, filter)
            }
        }.onFailure {
            receiverRegistered.set(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun reconcileDisconnect(proxy: BluetoothHidDevice, device: BluetoothDevice) {
        val disconnectedAddress = safeAddress(device) ?: run {
            permissionUnavailable()
            return
        }
        val actual = runCatching { proxy.connectedDevices.firstOrNull(::isComputer) }.getOrNull()
        val tracked = connectedHost
        val before = _state.value
        val resolved = afterDisconnect(
            disconnectedAddress = disconnectedAddress,
            trackedConnectedAddress = safeAddress(tracked) ?: before.connectedHostAddress,
            trackedConnectingAddress = before.connectingHostAddress,
            actualConnectedAddress = safeAddress(actual),
        )
        val resolvedDevice = actual ?: tracked?.takeIf { safeAddress(it) == resolved.connectedAddress }
        if (resolved.connectedAddress != null) {
            connectedHost = resolvedDevice
            _state.value = before.copy(
                connectedHostAddress = resolved.connectedAddress,
                connectingHostAddress = resolved.connectingAddress,
                message = resolvedDevice?.let {
                    "Connected to ${safeName(it)}. Standard controls now use Bluetooth HID."
                } ?: before.message,
            )
            return
        }
        if (resolved.connectingAddress != null) {
            _state.value = before.copy(connectingHostAddress = resolved.connectingAddress)
            return
        }
        val lostTrackedHost = before.connectedHostAddress == disconnectedAddress ||
            safeAddress(tracked) == disconnectedAddress
        val failedAttempt = before.connectingHostAddress == disconnectedAddress
        if (!lostTrackedHost && !failedAttempt) return
        stopRepeat()
        synchronized(heldKeyLock) { heldChord = null }
        connectedHost = null
        _state.value = before.copy(
            connectedHostAddress = null,
            connectingHostAddress = null,
            message = "Disconnected from ${safeName(device)}. Bridge fallback remains available.",
        )
        schedulePreferredReconnect()
    }

    @SuppressLint("MissingPermission")
    private fun reconcileConnectedHost() {
        val proxy = hidDevice ?: return
        val actual = runCatching { proxy.connectedDevices.firstOrNull(::isComputer) }.getOrNull()
        if (actual != null) {
            val address = safeAddress(actual) ?: run {
                permissionUnavailable()
                return
            }
            connectedHost = actual
            _state.value = _state.value.copy(
                connectedHostAddress = address,
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
        if (!hasConnectPermission()) {
            permissionUnavailable()
            return
        }
        if (autoReconnectAttempted) return
        val address = preferredReconnectAddress() ?: return
        autoReconnectAttempted = true
        if (!connect(address, rememberHost = false)) schedulePreferredReconnect()
    }

    @SuppressLint("MissingPermission")
    private fun preferredReconnectAddress(): String? {
        if (!hasConnectPermission()) return null
        val bondedDevices = runCatching { adapter?.bondedDevices.orEmpty().toList() }.getOrElse {
            permissionUnavailable()
            return null
        }
        val computerAddresses = bondedDevices
            .filter(::isComputer)
            .mapNotNullTo(mutableSetOf(), ::safeAddress)
        val address = infer(
            registered = _state.value.registered,
            connectedAddress = _state.value.connectedHostAddress,
            connectingAddress = _state.value.connectingHostAddress,
            preferredAddress = preferences.getString(PREFERRED_HOST_ADDRESS_KEY, null),
            bondedAddresses = bondedDevices.mapNotNullTo(mutableSetOf(), ::safeAddress),
            computerAddresses = computerAddresses,
        )
        if (address != null && preferences.getString(PREFERRED_HOST_ADDRESS_KEY, null) == null) {
            rememberPreferredHost(address)
        }
        return address
    }

    private fun schedulePreferredReconnect() {
        if (closed.get() || !hasConnectPermission() || preferredReconnectAddress() == null) return
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

    private data class Transport(
        val generation: Long,
        val proxy: BluetoothHidDevice,
    )

    private fun currentTransport(): Transport? = synchronized(profileLock) {
        val proxy = hidDevice ?: return@synchronized null
        val generation = profileLifecycle.currentGeneration() ?: return@synchronized null
        Transport(generation, proxy)
    }

    private fun isCurrentTransport(transport: Transport, device: BluetoothDevice): Boolean =
        safeAddress(connectedHost) == safeAddress(device) &&
            safeAddress(device) != null &&
            synchronized(profileLock) {
                hidDevice === transport.proxy && profileLifecycle.isCurrent(transport.generation)
            }

    private fun enqueueReport(block: () -> Unit): Boolean = reportQueue.submit(block)

    @SuppressLint("MissingPermission")
    private fun recoverReleaseFailure(transport: Transport, device: BluetoothDevice) {
        runCatching { transport.proxy.sendReport(device, REPORT_ID, Report.release) }
        runCatching { transport.proxy.disconnect(device) }
        if (safeAddress(connectedHost) != safeAddress(device)) return
        stopRepeat()
        synchronized(heldKeyLock) { heldChord = null }
        connectedHost = null
        _state.value = _state.value.copy(
            connectedHostAddress = null,
            connectingHostAddress = null,
            message = "Bluetooth key release failed. Reconnect the keyboard before continuing.",
        )
    }

    private fun permissionRemoved(device: BluetoothDevice) {
        if (safeAddress(connectedHost) != safeAddress(device)) return
        permissionUnavailable()
    }

    private fun permissionUnavailable() {
        stopRepeat()
        synchronized(heldKeyLock) { heldChord = null }
        connectedHost = null
        _state.value = _state.value.copy(
            connectedHostAddress = null,
            connectingHostAddress = null,
            message = "Nearby devices permission was removed. Bridge fallback remains available.",
        )
    }

    @SuppressLint("MissingPermission")
    private fun closeProxy(proxy: BluetoothProfile) {
        runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy) }
    }

    @SuppressLint("MissingPermission")
    private fun isComputer(device: BluetoothDevice): Boolean = runCatching {
        eligibleComputer(
            majorDeviceClass = device.bluetoothClass?.majorDeviceClass,
            computerDeviceClass = BluetoothClass.Device.Major.COMPUTER,
        )
    }.getOrDefault(false)

    private fun hasPermission(permission: String): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun safeAddress(device: BluetoothDevice?): String? = runCatching { device?.address }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String = runCatching { device.name }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: safeAddress(device)
        ?: "computer"

    companion object {
        private const val PREFERENCES_NAME = "vibe-pocket-hid"
        private const val PREFERRED_HOST_ADDRESS_KEY = "preferred-host-address"
        private const val REPORT_ID = 0
        private const val KEY_HOLD_MILLIS = 24L
        private const val KEY_GAP_MILLIS = 12L
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
