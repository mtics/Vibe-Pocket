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
import au.edu.uts.vibepocket.input.HidResult
import au.edu.uts.vibepocket.profile.Action
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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

internal fun afterPermissionLoss(status: Status): Status = status.copy(
    pairedHosts = emptyList(),
    connectedHostAddress = null,
    connectingHostAddress = null,
    message = "Nearby devices permission was removed. Bridge fallback remains available.",
)

internal fun afterReportFailure(status: Status): Status = status.copy(
    connectedHostAddress = null,
    connectingHostAddress = null,
    message = "Bluetooth report outcome was uncertain. Releasing keys and disconnecting before reuse.",
)

private fun TransactionResult.toHidResult(): HidResult = when (this) {
    TransactionResult.COMPLETE -> HidResult.DELIVERED
    TransactionResult.NOT_DISPATCHED -> HidResult.NOT_DISPATCHED
    TransactionResult.INDETERMINATE -> HidResult.INDETERMINATE
}

internal fun TransactionResult.afterReportException(reportException: Boolean): TransactionResult =
    if (reportException) TransactionResult.INDETERMINATE else this

internal fun QueueResult<Boolean>.toPressResult(): HidResult = when (this) {
    is QueueResult.Completed -> if (value) HidResult.DELIVERED else HidResult.NOT_DISPATCHED
    QueueResult.Rejected -> HidResult.NOT_DISPATCHED
    QueueResult.Cancelled -> HidResult.CANCELLED
    QueueResult.TimedOut -> HidResult.TIMED_OUT
    QueueResult.Failed -> HidResult.INDETERMINATE
}

internal class RecoveryQuarantine<T>(
    private val executor: Executor,
) {
    private val lock = Any()
    private var owner: T? = null

    fun enter(
        candidate: T,
        onEntered: () -> Unit,
        recover: (T) -> Unit,
    ): Boolean {
        synchronized(lock) {
            if (owner != null) return false
            owner = candidate
            onEntered()
        }
        runCatching { executor.execute { recover(candidate) } }
        return true
    }

    fun isActive(): Boolean = synchronized(lock) { owner != null }

    fun owns(predicate: (T) -> Boolean): Boolean = synchronized(lock) {
        owner?.let(predicate) == true
    }

    fun leave(predicate: (T) -> Boolean): Boolean = synchronized(lock) {
        val current = owner ?: return@synchronized false
        if (!predicate(current)) return@synchronized false
        owner = null
        true
    }

    fun clear() = synchronized(lock) {
        owner = null
    }
}

internal fun awaitReleaseThenDisconnect(
    release: Future<*>?,
    awaitRelease: (Future<*>) -> Unit,
    disconnect: () -> Unit,
) {
    if (release != null) {
        try {
            awaitRelease(release)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: CancellationException) {
            Unit
        } catch (_: ExecutionException) {
            Unit
        } catch (_: TimeoutException) {
            Unit
        } finally {
            if (!release.isDone) release.cancel(true)
        }
    }
    runCatching(disconnect)
}

private fun QueueResult<Boolean>.toReleaseResult(): HidResult = when (this) {
    is QueueResult.Completed -> if (value) HidResult.DELIVERED else HidResult.INDETERMINATE
    QueueResult.TimedOut -> HidResult.TIMED_OUT
    QueueResult.Rejected,
    QueueResult.Cancelled,
    QueueResult.Failed -> HidResult.INDETERMINATE
}

class Keyboard(context: Context) : AutoCloseable, Hid {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val reportExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val recoveryExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val repeatExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val closed = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)
    private val profileLock = Any()
    private val repeatLock = Any()
    private val heldKeyLock = Any()
    private val reconnectLock = Any()
    private val profileLifecycle = ProfileLifecycle()
    private val reportQueue = ReportQueue(reportExecutor)
    private val recoveryQuarantine = RecoveryQuarantine<RecoveryOwner>(callbackExecutor)
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
                    val connectPermission = hasConnectPermission()
                    val canReconnect = reconnectAfterAdapterChange(enabled, connectPermission)
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
                        adapterUnavailable()
                    } else if (!connectPermission) {
                        permissionUnavailable()
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
            permissionUnavailable()
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
        if (!hasConnectPermission() || !_state.value.registered || recoveryQuarantine.isActive()) return false
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
    override fun send(action: Action, completion: (HidResult) -> Unit): Boolean {
        val chords = Mapping.chords(action) ?: return false
        if (!hasConnectPermission()) return false
        val device = connectedHost ?: return false
        val transport = currentTransport() ?: return false
        if (synchronized(heldKeyLock) { heldChord != null }) return false
        return enqueueReport(
            block = {
                val result = if (
                    synchronized(heldKeyLock) { heldChord != null } ||
                    !isCurrentTransport(transport, device)
                ) {
                    TransactionResult.NOT_DISPATCHED
                } else {
                    var permissionLost = false
                    var reportException = false
                    sendTransaction(
                        chords = chords,
                        send = { report ->
                            try {
                                transport.proxy.sendReport(device, REPORT_ID, report)
                            } catch (_: SecurityException) {
                                permissionLost = true
                                false
                            } catch (_: RuntimeException) {
                                reportException = true
                                false
                            }
                        },
                        pause = Thread::sleep,
                        holdMillis = KEY_HOLD_MILLIS,
                        gapMillis = KEY_GAP_MILLIS,
                    ).afterReportException(reportException).also {
                        if (permissionLost) permissionUnavailable()
                    }
                }
                if (result == TransactionResult.INDETERMINATE) quarantineTransport(transport, device)
                runCatching { completion(result.toHidResult()) }
            },
            cancelled = { runCatching { completion(HidResult.CANCELLED) } },
            failed = {
                quarantineTransport(transport, device)
                runCatching { completion(HidResult.INDETERMINATE) }
            },
        )
    }

    @SuppressLint("MissingPermission")
    override fun press(action: Action): HidResult {
        val chord = Mapping.chords(action)?.singleOrNull() ?: return HidResult.NOT_DISPATCHED
        if (!hasConnectPermission()) return HidResult.NOT_DISPATCHED
        val device = connectedHost ?: return HidResult.NOT_DISPATCHED
        val transport = currentTransport() ?: return HidResult.NOT_DISPATCHED
        synchronized(heldKeyLock) {
            if (heldChord != null) return HidResult.NOT_DISPATCHED
            heldChord = chord
        }
        val result = reportQueue.submitAndWait {
            try {
                val sent = isCurrentTransport(transport, device) &&
                    transport.proxy.sendReport(device, REPORT_ID, Report.encode(chord))
                if (!sent) {
                    synchronized(heldKeyLock) { if (heldChord == chord) heldChord = null }
                }
                sent
            } catch (_: SecurityException) {
                synchronized(heldKeyLock) { heldChord = null }
                permissionUnavailable()
                false
            }
        }.toPressResult()
        if (result.fallbackSafe) {
            synchronized(heldKeyLock) { if (heldChord == chord) heldChord = null }
        } else if (result == HidResult.TIMED_OUT || result == HidResult.INDETERMINATE) {
            quarantineTransport(transport, device)
        }
        return result
    }

    @SuppressLint("MissingPermission")
    override fun release(action: Action): HidResult {
        val chord = Mapping.chords(action)?.singleOrNull() ?: return HidResult.NOT_DISPATCHED
        val shouldRelease = synchronized(heldKeyLock) {
            if (heldChord != chord) false else {
                heldChord = null
                true
            }
        }
        if (!shouldRelease) return HidResult.NOT_DISPATCHED
        return releaseHeldChord()
    }

    @SuppressLint("MissingPermission")
    override fun releaseAny(): HidResult {
        val shouldRelease = synchronized(heldKeyLock) {
            (heldChord != null).also { heldChord = null }
        }
        if (!shouldRelease) {
            return if (recoveryQuarantine.isActive()) HidResult.INDETERMINATE else HidResult.NOT_DISPATCHED
        }
        return releaseHeldChord()
    }

    @SuppressLint("MissingPermission")
    private fun releaseHeldChord(): HidResult {
        val device = connectedHost ?: return HidResult.INDETERMINATE
        val transport = currentTransport() ?: return HidResult.INDETERMINATE
        val result = reportQueue.submitAndWait {
            if (!isCurrentTransport(transport, device)) return@submitAndWait false
            val sent = try {
                transport.proxy.sendReport(device, REPORT_ID, Report.release)
            } catch (_: SecurityException) {
                permissionUnavailable()
                false
            }
            sent
        }.toReleaseResult()
        if (result == HidResult.TIMED_OUT || result == HidResult.INDETERMINATE) {
            quarantineTransport(transport, device)
        }
        return result
    }

    override fun repeat(action: Action, completion: (HidResult) -> Unit): Boolean {
        if (action.type != "navigate") return false
        val generation = synchronized(repeatLock) {
            repeatGeneration += 1
            navigationRepeat?.cancel(false)
            navigationRepeat = null
            repeatGeneration
        }
        return send(action) { result ->
            if (result == HidResult.DELIVERED) {
                synchronized(repeatLock) {
                    if (generation == repeatGeneration && !closed.get()) {
                        navigationRepeat = repeatExecutor.scheduleWithFixedDelay(
                            {
                                var active = false
                                val accepted = synchronized(repeatLock) {
                                    if (generation != repeatGeneration || closed.get()) {
                                        false
                                    } else {
                                        active = true
                                        send(action) { repeated ->
                                            if (repeated != HidResult.DELIVERED) stopRepeat()
                                        }
                                    }
                                }
                                if (active && !accepted) stopRepeat()
                            },
                            NAVIGATION_REPEAT_INITIAL_DELAY_MILLIS,
                            NAVIGATION_REPEAT_INTERVAL_MILLIS,
                            TimeUnit.MILLISECONDS,
                        )
                    }
                }
            }
            completion(result)
        }
    }

    override fun stopRepeat() {
        synchronized(repeatLock) {
            repeatGeneration += 1
            navigationRepeat?.cancel(false)
            navigationRepeat = null
        }
        reportQueue.cancelPending()
    }

    override fun quiesce(): Boolean {
        stopRepeat()
        if (!reportQueue.cancelPending()) return false
        return reportQueue.submitAndWait { Unit } is QueueResult.Completed
    }

    @SuppressLint("MissingPermission")
    fun refreshHosts() {
        if (!hasConnectPermission()) {
            permissionUnavailable()
            return
        }
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
        stopRepeat()
        reportQueue.close {
            val device = connectedHost
            val proxy = hidDevice
            if (device != null && proxy != null && hasConnectPermission()) {
                runCatching { proxy.sendReport(device, REPORT_ID, Report.release) }
            }
        }
        synchronized(heldKeyLock) { heldChord = null }
        recoveryQuarantine.clear()
        resetPreferredReconnect()
        val proxy = synchronized(profileLock) {
            profileLifecycle.close()
            hidDevice.also { hidDevice = null }
        }
        runCatching { proxy?.unregisterApp() }
        runCatching { proxy?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } }
        if (receiverRegistered.compareAndSet(true, false)) runCatching { appContext.unregisterReceiver(bluetoothReceiver) }
        connectedHost = null
        recoveryExecutor.shutdownNow()
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
            recoveryQuarantine.clear()
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
                recoveryQuarantine.clear()
            }
            val recovering = recoveryQuarantine.isActive()
            connectedHost = pluggedDevice?.takeIf { registered && !recovering && isComputer(it) }
            _state.value = _state.value.copy(
                registered = registered,
                connectedHostAddress = safeAddress(connectedHost),
                connectingHostAddress = null,
                message = when {
                    recovering -> afterReportFailure(_state.value).message
                    registered -> "Bluetooth keyboard ready. Pair or connect a Mac."
                    else -> "Bluetooth keyboard registration ended. Return to the app to restart it."
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
            if (recoveryQuarantine.isActive()) {
                if (
                    state == BluetoothProfile.STATE_DISCONNECTED &&
                    recoveryQuarantine.owns { sameDevice(it.device, device) }
                ) {
                    completeQuarantine(device)
                } else if (state == BluetoothProfile.STATE_CONNECTED) {
                    runCatching { proxy.disconnect(device) }
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
        if (recoveryQuarantine.isActive()) return
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

    private data class RecoveryOwner(
        val transport: Transport,
        val device: BluetoothDevice,
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

    private fun enqueueReport(
        block: () -> Unit,
        cancelled: () -> Unit,
        failed: () -> Unit,
    ): Boolean = reportQueue.submit(block, cancelled, failed)

    @SuppressLint("MissingPermission")
    private fun quarantineTransport(transport: Transport, device: BluetoothDevice) {
        if (!sameDevice(connectedHost, device)) return
        recoveryQuarantine.enter(
            candidate = RecoveryOwner(transport, device),
            onEntered = {
                stopRepeat()
                connectedHost = null
                _state.value = afterReportFailure(_state.value)
            },
            recover = ::recoverQuarantinedTransport,
        )
    }

    @SuppressLint("MissingPermission")
    private fun recoverQuarantinedTransport(owner: RecoveryOwner) {
        if (!recoveryQuarantine.owns { it === owner }) return
        val release = runCatching {
            recoveryExecutor.submit<Boolean> {
                owner.transport.proxy.sendReport(owner.device, REPORT_ID, Report.release)
            }
        }.getOrNull()
        awaitReleaseThenDisconnect(
            release = release,
            awaitRelease = { it.get(RECOVERY_RELEASE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) },
            disconnect = {
                if (recoveryQuarantine.owns { it === owner }) {
                    owner.transport.proxy.disconnect(owner.device)
                }
            },
        )
    }

    private fun completeQuarantine(device: BluetoothDevice): Boolean {
        if (!recoveryQuarantine.leave { sameDevice(it.device, device) }) return false
        synchronized(heldKeyLock) { heldChord = null }
        if (sameDevice(connectedHost, device)) connectedHost = null
        _state.value = _state.value.copy(
            connectedHostAddress = null,
            connectingHostAddress = null,
            message = "Bluetooth keyboard recovery completed. Reconnect before continuing.",
        )
        schedulePreferredReconnect()
        return true
    }

    private fun adapterUnavailable() {
        stopRepeat()
        synchronized(heldKeyLock) { heldChord = null }
        recoveryQuarantine.clear()
        synchronized(profileLock) { profileLifecycle.rejectPendingProxy() }
        connectedHost = null
        _state.value = _state.value.copy(
            connectedHostAddress = null,
            connectingHostAddress = null,
        )
    }

    private fun permissionUnavailable() {
        stopRepeat()
        synchronized(heldKeyLock) { heldChord = null }
        recoveryQuarantine.clear()
        synchronized(profileLock) { profileLifecycle.rejectPendingProxy() }
        connectedHost = null
        _state.value = afterPermissionLoss(_state.value)
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

    private fun sameDevice(first: BluetoothDevice?, second: BluetoothDevice?): Boolean {
        if (first == null || second == null) return false
        if (first === second) return true
        val firstAddress = safeAddress(first) ?: return false
        return firstAddress == safeAddress(second)
    }

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
        private const val RECOVERY_RELEASE_TIMEOUT_MILLIS = 500L

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
