package au.edu.uts.vibepocket.hardware.micro

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import au.edu.uts.vibepocket.VibePocket
import au.edu.uts.vibepocket.hardware.Recovery
import au.edu.uts.vibepocket.hardware.Restore
import au.edu.uts.vibepocket.hardware.micro.protocol.Act
import au.edu.uts.vibepocket.hardware.micro.protocol.Decode
import au.edu.uts.vibepocket.hardware.micro.protocol.DeviceEncoder
import au.edu.uts.vibepocket.hardware.micro.protocol.Frame
import au.edu.uts.vibepocket.hardware.micro.protocol.HostDecoder
import au.edu.uts.vibepocket.hardware.micro.protocol.Rpc
import au.edu.uts.vibepocket.hardware.micro.protocol.Signal
import au.edu.uts.vibepocket.hardware.micro.subscription.Channel
import au.edu.uts.vibepocket.hardware.micro.subscription.Preferences
import au.edu.uts.vibepocket.hardware.micro.subscription.Removal
import au.edu.uts.vibepocket.hardware.micro.subscription.State as SubscriptionState

internal fun acceptOwner(ownsDevice: Boolean, reject: () -> Unit): Boolean {
    if (ownsDevice) return true
    reject()
    return false
}

class Peripheral : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val decoder = HostDecoder()
    private val session = HostSession<String>(resetDecoder = decoder::reset)
    private val battery = BatteryPolicy<String>()
    private val rpc = Rpc(::sampleBattery)
    private val teardown = Teardown<String>()
    private val shutdown = ShutdownPolicy()
    private val prepared = ArrayDeque<android.bluetooth.BluetoothGattService>()
    private val writes = Writes<String, Any>()
    private val inbox = Inbox()
    private val subscriptions by lazy { SubscriptionState(Preferences(this)) }

    private lateinit var manager: BluetoothManager
    private var adapter: BluetoothAdapter? = null
    private var name: Name? = null
    private var server: BluetoothGattServer? = null
    private var schema: Schema? = null
    private val devices = linkedMapOf<String, BluetoothDevice>()
    private var installing: android.bluetooth.BluetoothGattService? = null
    private var running = false
    private var claimed = false
    private var opening = false
    private var gattAttempted = false
    private var receiverRegistered = false
    private val hardware get() = (application as VibePocket).hardware
    private val claimTimeout = Runnable {
        if (running && !opening) fail("radio_timeout", "classic HID did not release")
    }
    private val teardownTimeout = Runnable {
        if (shutdown.stopping && !shutdown.finished) {
            val abandoned = teardown.abandon()
            log("teardown_timeout", "abandoned=$abandoned process_boundary=true")
            finishShutdown()
        }
    }
    private val shutdownSettle = Runnable {
        if (shutdown.stopping && !shutdown.finished && teardown.ready()) finishShutdown() else settleShutdown()
    }
    private val recoveryRetry = Runnable {
        if (shutdown.finished && claimed && !shutdown.recoveryPending) restore()
    }
    private val deferredReconnect = Runnable(::reconnectDeferredHost)
    private val writeTimeout = Runnable {
        if (!writes.expire(SystemClock.elapsedRealtime())) return@Runnable
        quarantine(session.quarantine(), "prepared_timeout", "generation=${session.generation}")
    }
    private val notificationTimeout = Runnable {
        val host = session.pendingNotificationHost ?: return@Runnable
        if (!session.notificationTimedOut(host)) return@Runnable
        log("notification_timeout", "gatt_rebuild=true")
        rebuildGatt()
    }

    private val advertiserCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            post {
                session.link.advertising()
                log("advertising", "name=${Name.advertised}")
                scheduleDeferredReconnect()
            }
        }

        override fun onStartFailure(errorCode: Int) {
            post { fail("advertising_failed", "code=$errorCode") }
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> log(
                    "pairing_request",
                    "variant=${intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)}",
                )
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> bondChanged(intent)
                Intent.ACTION_BATTERY_CHANGED -> sampleBattery(intent)
            }
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: android.bluetooth.BluetoothGattService) = post {
            val expected = installing
            if (expected == null) {
                log("stale_service_callback", "status=$status uuid=${service.uuid}")
                return@post
            }
            if (status != BluetoothGatt.GATT_SUCCESS || expected?.uuid != service.uuid) {
                fail("service_failed", "status=$status uuid=${service.uuid}")
                return@post
            }
            log("service", "uuid=${service.uuid}")
            installing = null
            installNext()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = post {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connect(device, status)
                BluetoothProfile.STATE_DISCONNECTED -> disconnect(device, status)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) = post {
            if (!owns(device)) return@post
            session.link.mtu(mtu)
            log("mtu", "value=$mtu usable=${mtu >= Link.minimumMtu}")
            drain()
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) = post {
            respondRead(device, requestId, offset, value(characteristic))
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) = post {
            respondRead(device, requestId, offset, descriptorValue(device, descriptor))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) = post {
            writeCharacteristic(
                device,
                requestId,
                characteristic,
                preparedWrite,
                responseNeeded,
                offset,
                value,
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) = post {
            writeDescriptor(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) = post {
            executeWrite(device, requestId, execute)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) = post {
            val success = status == BluetoothGatt.GATT_SUCCESS
            val completion = session.completeNotification(key(device), success)
            if (completion.ignored) return@post
            log("notification", "status=$status response_tail=${completion.packet?.completesResponse == true}")
            if (completion.settledPoison) {
                handler.removeCallbacks(notificationTimeout)
                return@post
            }
            if (completion.quarantine != null) {
                quarantine(completion.quarantine, "notification_outcome", "status=$status")
                return@post
            }
            handler.postDelayed(::drain, fragmentDelayMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(BluetoothManager::class.java)
        adapter = manager.adapter
        adapter?.let { bluetoothAdapter ->
            name = Name(this, bluetoothAdapter)
            if (hasBluetoothPermissions()) runCatching { name?.recover() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground()
        when (intent?.action) {
            startAction -> start()
            pulseAction -> pulse()
            encoderClockwiseAction -> signal("ENC_CW", Act.STEP)
            encoderCounterclockwiseAction -> signal("ENC_CC", Act.STEP)
            encoderPressAction -> signal(encoderKey, Act.PRESS)
            encoderReleaseAction -> signal(encoderKey, Act.RELEASE)
            encoderClickAction -> click()
            stopAction -> stop()
            else -> SignalAction.key(intent?.action)?.let(::pulse)
                ?: log("ignored", "action=${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(recoveryRetry)
        shutdown(requestStop = false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun start() {
        if (running || shutdown.stopping) {
            log("start_ignored", "phase=${session.link.phase}")
            return
        }
        if (!hasBluetoothPermissions()) {
            fail("permission", "BLUETOOTH_CONNECT and BLUETOOTH_ADVERTISE are required")
            return
        }
        if (!Recovery.available(this)) {
            fail("recovery_unavailable", "notification permission is required")
            return
        }
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled || bluetoothAdapter.bluetoothLeAdvertiser == null) {
            fail("unsupported", "Bluetooth LE peripheral advertising is unavailable")
            return
        }

        running = true
        claimed = hardware.radio.claim(this) {
            post(::open)
        }
        if (!claimed) {
            fail("radio_busy", "another Micro peripheral owns Bluetooth")
            return
        }
        handler.postDelayed(claimTimeout, claimTimeoutMs)
        log("radio_wait")
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        if (!running || opening) return
        opening = true
        handler.removeCallbacks(claimTimeout)
        if (!hasBluetoothPermissions()) {
            fail("permission", "Bluetooth permission changed during handover")
            return
        }
        registerBondReceiver()
        if (name?.acquire() != true) {
            fail("name", "unable to lease Bluetooth name")
            return
        }
        gattAttempted = true
        val opened = runCatching { manager.openGattServer(this, callback) }.getOrNull()
        if (opened == null) {
            fail("gatt", "unable to open server")
            return
        }
        server = opened
        schema = Gatt.create(sampleBattery().gattValue())
        prepared.addAll(schema!!.services)
        session.link.installing()
        installNext()
    }

    @SuppressLint("MissingPermission")
    private fun installNext() {
        val service = prepared.removeFirstOrNull()
        if (service == null) {
            advertise()
            return
        }
        installing = service
        if (server?.addService(service) != true) fail("service_submit", "uuid=${service.uuid}")
    }

    @SuppressLint("MissingPermission")
    private fun advertise() {
        val bluetoothAdapter = adapter ?: return fail("advertising", "adapter unavailable")
        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser
            ?: return fail("advertising", "peripheral advertiser unavailable")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(Gatt.hid))
            .setIncludeDeviceName(false)
            .build()
        val scan = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        advertiser.startAdvertising(settings, data, scan, advertiserCallback)
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice, status: Int) {
        val id = key(device)
        devices[id] = device
        if (shutdown.stopping) {
            session.observeConnected(id)
            handler.removeCallbacks(shutdownSettle)
            teardown.connected(id)
            log("teardown_connected", "status=$status")
            server?.cancelConnection(device)
            return
        }
        val isBonded = bonded(device)
        subscriptions.connect(id, isBonded)
        when (session.connect(id, subscriptions.enabled(id, Channel.INPUT))) {
            ConnectionDisposition.ACCEPTED -> {
                clearWrites()
                pruneDevices()
                handler.removeCallbacks(deferredReconnect)
                log("connected", "status=$status bond=${bondState(device)}")
                drain()
            }
            ConnectionDisposition.DEFERRED ->
                log("host_deferred", "status=$status phase=${session.link.phase}")
            ConnectionDisposition.DEFERRED_AND_CANCEL -> {
                log("host_deferred", "status=$status phase=${session.link.phase}")
                server?.cancelConnection(device)
            }
            ConnectionDisposition.REJECTED -> {
                subscriptions.disconnect(id)
                log("host_rejected", "status=$status")
                server?.cancelConnection(device)
            }
        }
    }

    private fun disconnect(device: BluetoothDevice, status: Int) {
        val id = key(device)
        if (session.owns(id)) {
            clearWrites()
        } else {
            clearWrites(Connection(session.generation, id))
        }
        subscriptions.disconnect(id)
        if (shutdown.stopping) {
            session.observeDisconnected(id)
            pruneDevice(id)
            log("teardown_disconnected", "status=$status")
            teardown.disconnected(id)
            if (teardown.ready()) settleShutdown()
            return
        }
        when (session.disconnect(id)) {
            DisconnectionDisposition.QUARANTINED -> {
                log("quarantine_complete", "status=$status")
                if (running) advertiseAgain()
            }
            DisconnectionDisposition.DEFERRED -> {
                log("host_deferred_disconnected", "status=$status")
                scheduleDeferredReconnect()
            }
            DisconnectionDisposition.HOST -> {
                log("disconnected", "status=$status")
                if (running) advertiseAgain()
            }
            DisconnectionDisposition.IGNORED -> Unit
        }
        armNotificationTimeout()
        pruneDevice(id)
    }

    @SuppressLint("MissingPermission")
    private fun advertiseAgain() {
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiserCallback)
        advertise()
    }

    private fun scheduleDeferredReconnect() {
        if (session.deferredHost == null || !running || !session.link.canAcceptConnection()) return
        handler.removeCallbacks(deferredReconnect)
        handler.postDelayed(deferredReconnect, deferredReconnectDelayMs)
    }

    @SuppressLint("MissingPermission")
    private fun reconnectDeferredHost() {
        val id = session.deferredHost ?: return
        val device = devices[id] ?: return
        if (!running || !session.link.canAcceptConnection()) return
        if (session.isConnected(id)) {
            log("host_promoted", "services_ready=true")
            resume(id, device)
            return
        }
        val submitted = runCatching { server?.connect(device, false) == true }.getOrDefault(false)
        log("host_reconnect", "submitted=$submitted")
    }

    private fun resume(id: String, device: BluetoothDevice) {
        handler.removeCallbacks(deferredReconnect)
        val isBonded = bonded(device)
        subscriptions.connect(id, isBonded)
        if (!session.resume(id, subscriptions.enabled(id, Channel.INPUT))) return
        clearWrites()
        log("resumed", "bond=${bondState(device)}")
        drain()
    }

    private fun respondRead(device: BluetoothDevice, requestId: Int, offset: Int, bytes: ByteArray) {
        if (!owns(device)) {
            respond(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            return
        }
        if (offset !in 0..bytes.size) {
            respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            return
        }
        respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, bytes.copyOfRange(offset, bytes.size))
    }

    private fun writeCharacteristic(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        bytes: ByteArray,
    ) {
        val current = schema
        if (preparedWrite) {
            stageWrite(device, requestId, characteristic, offset, bytes)
            return
        }
        val control = if (characteristic == current?.control) hidControl(bytes) else null
        val body = if (characteristic == current?.output) Frame.normalize(bytes) else null
        val status = when {
            !owns(device) -> BluetoothGatt.GATT_FAILURE
            offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
            characteristic == current?.output && body != null -> BluetoothGatt.GATT_SUCCESS
            characteristic == current?.control && control != null -> BluetoothGatt.GATT_SUCCESS
            characteristic == current?.mode && bytes.contentEquals(byteArrayOf(1)) -> BluetoothGatt.GATT_SUCCESS
            else -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (responseNeeded) respond(device, requestId, status, offset, null)
            return
        }

        if (body != null) {
            commitOutput(device, requestId, current!!.output, body, responseNeeded, bytes.size)
            return
        }

        val previous = value(characteristic)
        setValue(characteristic, bytes)
        val responded = !responseNeeded || respond(device, requestId, status, offset, null)
        if (!responded) {
            setValue(characteristic, previous)
            quarantine(session.quarantine(), "response_failed", "write_commit=true")
            return
        }
        if (control != null) {
            session.link.suspended(control == HidControl.SUSPEND)
            log("control_point", "value=${bytes[0]} suspended=${control == HidControl.SUSPEND}")
            if (control == HidControl.EXIT_SUSPEND) drain()
        }
    }

    private fun writeDescriptor(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        bytes: ByteArray,
    ) {
        val current = schema
        if (preparedWrite) {
            stageWrite(device, requestId, descriptor, offset, bytes)
            return
        }
        val inputCccd = descriptor.characteristic == current?.input && descriptor.uuid == cccd
        val batteryCccd = descriptor.characteristic == current?.battery && descriptor.uuid == cccd
        val enabled = bytes.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        val disabled = bytes.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        val status = when {
            !owns(device) -> BluetoothGatt.GATT_FAILURE
            offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
            !(inputCccd || batteryCccd) -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            !(enabled || disabled) -> BluetoothGatt.GATT_FAILURE
            else -> BluetoothGatt.GATT_SUCCESS
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (responseNeeded) respond(device, requestId, status, offset, null)
            return
        }
        val channel = if (inputCccd) Channel.INPUT else Channel.BATTERY
        val id = key(device)
        val previousEnabled = subscriptions.enabled(id, channel)
        val previousValue = value(descriptor)
        if (!subscriptions.update(id, channel, enabled, bonded(device))) {
            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            return
        }
        setValue(descriptor, bytes)
        val responded = !responseNeeded || respond(device, requestId, status, offset, null)
        if (!responded) {
            subscriptions.update(id, channel, previousEnabled, bonded(device))
            setValue(descriptor, previousValue)
            quarantine(session.quarantine(), "response_failed", "cccd_commit=true")
            return
        }
        if (inputCccd) {
            if (enabled) session.link.subscribed(true)
            log("cccd", "input=$enabled mtu=${session.link.mtu}")
            if (enabled) {
                drain()
            } else {
                session.disableSubscription(id, input = true)?.let {
                    quarantine(it, "cccd_disabled", "input=true notification_in_flight=true")
                }
            }
        } else {
            log("cccd", "battery=$enabled")
            if (enabled) {
                drain()
            } else {
                session.disableSubscription(id, input = false)?.let {
                    quarantine(it, "cccd_disabled", "battery=true notification_in_flight=true")
                }
            }
        }
    }

    private fun stageWrite(
        device: BluetoothDevice,
        requestId: Int,
        target: Any,
        offset: Int,
        bytes: ByteArray,
    ) {
        val output = schema?.output
        if (!acceptOwner(owns(device)) {
                respond(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        ) return
        if (output == null) {
            writes.poison(WriteFault.TARGET)
            respond(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            return
        }
        val result = writes.stage(
            connection = Connection(session.generation, key(device)),
            target = target,
            output = output,
            offset = offset,
            value = bytes,
            now = SystemClock.elapsedRealtime(),
        )
        handler.removeCallbacks(writeTimeout)
        handler.postDelayed(writeTimeout, preparedWriteTimeoutMs)
        when (result) {
            is Stage.Echo -> {
                if (!respond(device, requestId, BluetoothGatt.GATT_SUCCESS, result.offset, result.value)) {
                    clearWrites()
                    quarantine(session.quarantine(), "response_failed", "prepared=true")
                }
            }
            is Stage.Rejected -> {
                val boundary = session.protocolBoundary()
                val sent = respond(device, requestId, status(result.fault), offset, null)
                when {
                    boundary != null -> quarantine(boundary, "prepared_poison", "fault=${result.fault}")
                    !sent -> quarantine(session.quarantine(), "response_failed", "prepared_rejection=true")
                }
            }
        }
    }

    private fun executeWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
        if (!acceptOwner(owns(device)) {
                respond(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        ) return
        handler.removeCallbacks(writeTimeout)
        when (val result = writes.execute(Connection(session.generation, key(device)), execute)) {
            Execution.Empty -> {
                if (!respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)) {
                    quarantine(session.quarantine(), "response_failed", "empty_execute=true")
                }
            }
            Execution.Cancelled -> {
                val boundary = session.protocolBoundary()
                val sent = respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                when {
                    boundary != null -> quarantine(boundary, "prepared_cancelled", "notification_in_flight=true")
                    !sent -> quarantine(session.quarantine(), "response_failed", "cancel_execute=true")
                }
            }
            is Execution.Rejected -> {
                val boundary = session.protocolBoundary()
                val sent = respond(device, requestId, status(result.fault), 0, null)
                when {
                    boundary != null -> quarantine(boundary, "prepared_rejected", "fault=${result.fault}")
                    !sent -> quarantine(session.quarantine(), "response_failed", "rejected_execute=true")
                }
            }
            is Execution.Ready -> commitOutput(
                device = device,
                requestId = requestId,
                characteristic = schema!!.output,
                body = result.body,
                responseNeeded = true,
                observedSize = null,
            )
        }
    }

    private fun commitOutput(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        body: ByteArray,
        responseNeeded: Boolean,
        observedSize: Int?,
    ) {
        val previous = value(characteristic)
        if (!inbox.stage(body)) {
            quarantine(session.quarantine(), "inbox_busy", "generation=${session.generation}")
            return
        }
        setValue(characteristic, body)
        val responded = !responseNeeded || respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        if (!responded) {
            setValue(characteristic, previous)
            inbox.clear()
            quarantine(session.quarantine(), "response_failed", "output_commit=true")
            return
        }
        val committed = inbox.release() ?: return quarantine(
            session.quarantine(),
            "inbox_empty",
            "output_commit=true",
        )
        observedSize?.let(session.link::payload)
        receive(committed)
    }

    private fun status(fault: WriteFault): Int = when (fault) {
        WriteFault.BOUNDARY, WriteFault.OVERLAP -> BluetoothGatt.GATT_INVALID_OFFSET
        WriteFault.TARGET -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
        WriteFault.LIMIT, WriteFault.INCOMPLETE, WriteFault.POISONED -> BluetoothGatt.GATT_FAILURE
    }

    private fun receive(bytes: ByteArray) {
        when (val result = decoder.acceptBody(bytes)) {
            Decode.Pending -> Unit
            is Decode.Invalid -> log("rpc_invalid", "reason=${result.reason}")
            is Decode.Complete -> {
                val reply = runCatching { rpc.reply(result.json) }.getOrElse {
                    log("rpc_invalid", "reason=${it.message}")
                    return
                }
                log("rpc", "method=${reply.method} recognized=${reply.recognized}")
                if (reply.recognized) session.link.recognized()
                enqueue(
                    json = reply.json,
                    completesResponse = true,
                    recognizedResponse = reply.recognized,
                )
            }
        }
    }

    private fun pulse(key: String = "ACT06") {
        if (!signal(key, Act.PRESS)) return
        signal(key, Act.RELEASE)
        log("pulse_submitted", "key=$key")
    }

    private fun click() {
        if (!signal(encoderKey, Act.PRESS)) return
        handler.postDelayed({ signal(encoderKey, Act.RELEASE) }, encoderClickDurationMs)
    }

    private fun signal(key: String, act: Act): Boolean {
        if (!running || !session.link.canPulse()) {
            log("signal_blocked", "key=$key act=$act phase=${session.link.phase}")
            if (!running) stopSelf()
            return false
        }
        enqueue(Signal.Key(key, act).json())
        log("signal_submitted", "key=$key act=$act")
        return true
    }

    private fun enqueue(
        json: String,
        completesResponse: Boolean = false,
        recognizedResponse: Boolean = false,
    ) {
        val frames = runCatching { DeviceEncoder.encode(json) }.getOrElse {
            log("encode_failed", "reason=${it.message}")
            return
        }
        val quarantine = session.enqueueNotifications(
            packets = frames.mapIndexed { index, bytes ->
                Packet(bytes, completesResponse && index == frames.lastIndex)
            },
            recognizedResponse = recognizedResponse,
        )
        if (quarantine != null) {
            quarantine(quarantine, "notification_overflow", "frames=${frames.size}")
            return
        }
        drain()
    }

    private fun enqueue(notification: BatteryNotification<String>) {
        val device = devices[notification.host] ?: return
        if (session.host != notification.host || notification.state.level != battery.state.level) return
        if (!bonded(device) || !subscriptions.enabled(notification.host, Channel.BATTERY)) return
        val quarantine = session.replaceBatteryNotification(
            Packet(
                bytes = notification.state.gattValue(),
                target = NotificationTarget.BATTERY,
            ),
        )
        if (quarantine != null) {
            quarantine(quarantine, "notification_overflow", "battery=true")
            return
        }
        log("battery_notification", "level=${notification.state.level}")
        drain()
    }

    @SuppressLint("MissingPermission")
    private fun drain() {
        val hostId = session.host ?: return
        val bluetoothDevice = devices[hostId] ?: return
        val current = schema ?: return
        val batteryReady = bonded(bluetoothDevice) && subscriptions.enabled(hostId, Channel.BATTERY)
        val packet = session.nextNotification(batteryReady) ?: return
        val characteristic = when (packet.target) {
            NotificationTarget.INPUT -> current.input
            NotificationTarget.BATTERY -> current.battery
        }
        val submitted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server?.notifyCharacteristicChanged(bluetoothDevice, characteristic, false, packet.bytes) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = packet.bytes
            @Suppress("DEPRECATION")
            server?.notifyCharacteristicChanged(bluetoothDevice, characteristic, false) == true
        }
        if (!submitted) {
            val completion = session.completeNotification(success = false)
            completion.quarantine?.let {
                quarantine(it, "notification_submit", "phase=${session.link.phase}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun quarantine(quarantine: Quarantine<String>, event: String, detail: String) {
        clearWrites()
        log(event, detail)
        quarantine.host?.let(devices::get)?.let { server?.cancelConnection(it) }
        armNotificationTimeout()
    }

    @SuppressLint("MissingPermission")
    private fun respond(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        bytes: ByteArray?,
    ): Boolean {
        val sent = runCatching { server?.sendResponse(device, requestId, status, offset, bytes) == true }
            .getOrDefault(false)
        if (!sent) log("response_failed", "request=$requestId status=$status")
        return sent
    }

    @SuppressLint("MissingPermission")
    private fun stop() {
        if (shutdown.restoreOnStop(claimed)) {
            restore()
            return
        }
        shutdown(requestStop = true)
    }

    @SuppressLint("MissingPermission")
    private fun shutdown(requestStop: Boolean) {
        if (!shutdown.begin(requestStop)) return
        running = false
        opening = false
        handler.removeCallbacks(claimTimeout)
        handler.removeCallbacks(deferredReconnect)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(bondReceiver) }
            receiverRegistered = false
        }
        adapter?.bluetoothLeAdvertiser?.let { advertiser ->
            runCatching { advertiser.stopAdvertising(advertiserCallback) }
        }
        installing = null
        prepared.clear()
        clearWrites()
        handler.removeCallbacks(notificationTimeout)
        session.stop()

        val connectedDevices = session.teardownDevices().mapNotNull(devices::get).distinctBy(::key)
        teardown.begin(connectedDevices.map(::key))
        connectedDevices.forEach { device -> runCatching { server?.cancelConnection(device) } }
        handler.post(::settleShutdown)
    }

    @SuppressLint("MissingPermission")
    private fun settleShutdown() {
        if (!shutdown.stopping || shutdown.finished) return
        if (teardown.ready()) {
            handler.removeCallbacks(teardownTimeout)
            handler.removeCallbacks(shutdownSettle)
            handler.postDelayed(shutdownSettle, teardownSettleMs)
            return
        }
        handler.removeCallbacks(teardownTimeout)
        handler.postDelayed(teardownTimeout, teardownTimeoutMs)
        log("teardown_wait", "pending=${teardown.pending()}")
    }

    @SuppressLint("MissingPermission")
    private fun finishShutdown() {
        val boundary = shutdown.complete(claimed, gattAttempted) ?: return
        handler.removeCallbacks(teardownTimeout)
        handler.removeCallbacks(shutdownSettle)
        runCatching { server?.clearServices() }
        runCatching { server?.close() }
        server = null
        schema = null
        session.finish()
        devices.clear()
        teardown.finish()
        if (hasBluetoothPermissions()) runCatching { name?.release() }
        if (boundary.releaseClaim) {
            claimed = false
            hardware.radio.release(this)
        }
        log("stopped")
        if (boundary.requestRecovery) {
            restore()
            return
        }
        if (boundary.stopService) stopSelf()
    }

    private fun restore() {
        if (shutdown.recoveryPending) return
        when (shutdown.prepareRecovery(Recovery.available(this))) {
            RecoveryDecision.BUSY -> return
            RecoveryDecision.RETRY -> {
                log("restore_unavailable", "radio remains claimed")
                scheduleRecoveryRetry()
                return
            }
            RecoveryDecision.REQUEST -> Unit
            RecoveryDecision.EXIT_PROCESS -> error("Unexpected recovery preparation result")
        }
        handler.removeCallbacks(recoveryRetry)
        val result = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val ready = resultCode == Activity.RESULT_OK && Recovery.available(this@Peripheral)
                when (shutdown.recoveryResult(ready)) {
                    RecoveryDecision.EXIT_PROCESS -> {
                        log("restore_ready")
                        handler.postDelayed({ Process.killProcess(Process.myPid()) }, processExitDelayMs)
                    }
                    RecoveryDecision.RETRY -> {
                        log("restore_failed", "radio remains claimed")
                        scheduleRecoveryRetry()
                    }
                    else -> error("Unexpected recovery result")
                }
            }
        }
        runCatching {
            sendOrderedBroadcast(
                Intent(this, Restore::class.java)
                    .setAction(Recovery.action)
                    .putExtra(Recovery.ownerPid, Process.myPid()),
                null,
                result,
                handler,
                Activity.RESULT_CANCELED,
                null,
                null,
            )
        }.onFailure {
            shutdown.recoverySubmissionFailed()
            log("restore_failed", "reason=${it.javaClass.simpleName}")
            scheduleRecoveryRetry()
        }
    }

    private fun scheduleRecoveryRetry() {
        handler.removeCallbacks(recoveryRetry)
        handler.postDelayed(recoveryRetry, recoveryRetryMs)
    }

    private fun fail(event: String, detail: String) {
        log(event, detail)
        shutdown(requestStop = true)
    }

    private fun registerBondReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        ContextCompat.registerReceiver(this, bondReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun foreground() {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(channelId, "Codex Micro experiment", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(au.edu.uts.vibepocket.R.drawable.ic_vibe_pocket)
            .setContentTitle("Vibe Pocket Micro experiment")
            .setContentText("Research BLE active; host identity is not authenticated")
            .setOngoing(true)
            .build()
        startForeground(notificationId, notification)
    }

    private fun hasBluetoothPermissions(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("MissingPermission")
    private fun bondState(device: BluetoothDevice): String {
        if (!hasBluetoothPermissions()) return "unavailable"
        return runCatching { device.bondState.toString() }.getOrDefault("unavailable")
    }

    @SuppressLint("MissingPermission")
    private fun owns(device: BluetoothDevice): Boolean = runCatching {
        session.owns(key(device))
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun key(device: BluetoothDevice): String = runCatching { device.address }
        .getOrDefault("identity:${System.identityHashCode(device)}")

    @Suppress("DEPRECATION")
    private fun value(characteristic: BluetoothGattCharacteristic): ByteArray =
        if (characteristic == schema?.battery) {
            sampleBattery().gattValue()
        } else {
            characteristic.value ?: byteArrayOf()
        }

    @Suppress("DEPRECATION")
    private fun value(descriptor: BluetoothGattDescriptor): ByteArray = descriptor.value ?: byteArrayOf()

    @Suppress("DEPRECATION")
    private fun setValue(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        characteristic.value = bytes.copyOf()
    }

    @Suppress("DEPRECATION")
    private fun setValue(descriptor: BluetoothGattDescriptor, bytes: ByteArray) {
        descriptor.value = bytes.copyOf()
    }

    private fun descriptorValue(device: BluetoothDevice, descriptor: BluetoothGattDescriptor): ByteArray {
        val current = schema
        val channel = when {
            descriptor.uuid != cccd -> null
            descriptor.characteristic == current?.input -> Channel.INPUT
            descriptor.characteristic == current?.battery -> Channel.BATTERY
            else -> null
        }
        return channel?.let { subscriptions.value(key(device), it) } ?: value(descriptor)
    }

    @SuppressLint("MissingPermission")
    private fun bonded(device: BluetoothDevice): Boolean = hasBluetoothPermissions() &&
        runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)

    private fun sampleBattery(event: Intent? = null): BatteryState {
        val sampled = AndroidBattery.sample(this, event) ?: return battery.state
        val hostId = session.host
        val hostDevice = hostId?.let(devices::get)
        val notification = battery.sample(
            sampled = sampled,
            host = hostId,
            bonded = hostDevice?.let(::bonded) == true,
            subscribed = hostId?.let { subscriptions.enabled(it, Channel.BATTERY) } == true,
        )
        @Suppress("DEPRECATION")
        schema?.battery?.value = battery.state.gattValue()
        notification?.let { handler.post { enqueue(it) } }
        return battery.state
    }

    private fun bondChanged(intent: Intent) {
        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
        val device = bondDevice(intent)
        val id = device?.let(::key)
        val persisted = state == BluetoothDevice.BOND_BONDED &&
            id != null && subscriptions.bonded(id)
        val removal = if (state == BluetoothDevice.BOND_NONE && id != null) {
            val result = subscriptions.unbonded(id)
            session.disableSubscription(id, input = true)?.let {
                quarantine(it, "bond_removed", "notification_in_flight=true")
            }
            result
        } else null
        if (state != BluetoothDevice.BOND_BONDED) session.clearBatteryNotifications()
        log(
            "bond",
            "state=$state subscriptions_persisted=$persisted " +
                "subscriptions_forgotten=${removal == Removal.PERSISTED}",
        )
        drain()
    }

    @Suppress("DEPRECATION")
    private fun bondDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun pruneDevice(id: String) {
        if (!session.referenced(id)) devices.remove(id)
    }

    private fun pruneDevices() {
        devices.keys.removeAll { !session.referenced(it) }
    }

    private fun clearWrites() {
        handler.removeCallbacks(writeTimeout)
        writes.clear()
        inbox.clear()
    }

    private fun clearWrites(connection: Connection<String>) {
        if (!writes.clear(connection)) return
        handler.removeCallbacks(writeTimeout)
    }

    private fun armNotificationTimeout() {
        if (session.pendingNotificationHost == null) return
        handler.removeCallbacks(notificationTimeout)
        handler.postDelayed(notificationTimeout, notificationCallbackTimeoutMs)
    }

    @SuppressLint("MissingPermission")
    private fun rebuildGatt() {
        if (!running || shutdown.stopping) return
        handler.removeCallbacks(deferredReconnect)
        adapter?.bluetoothLeAdvertiser?.let { runCatching { it.stopAdvertising(advertiserCallback) } }
        runCatching { server?.clearServices() }
        runCatching { server?.close() }
        server = null
        schema = null
        installing = null
        prepared.clear()
        clearWrites()
        devices.keys.forEach(subscriptions::disconnect)
        devices.clear()
        session.finish()
        handler.postDelayed(::openRebuiltGatt, gattRebuildSettleMs)
    }

    @SuppressLint("MissingPermission")
    private fun openRebuiltGatt() {
        if (!running || shutdown.stopping) return
        val opened = runCatching { manager.openGattServer(this, callback) }.getOrNull()
        if (opened == null) return fail("gatt_rebuild", "unable to reopen server")
        server = opened
        schema = Gatt.create(sampleBattery().gattValue())
        prepared.addAll(schema!!.services)
        session.link.installing()
        installNext()
    }

    private fun post(block: () -> Unit) {
        handler.post(block)
    }

    private fun log(event: String, detail: String = "") {
        Log.i(tag, "event=$event phase=${session.link.phase}${if (detail.isBlank()) "" else " $detail"}")
    }

    companion object {
        const val startAction = "au.edu.uts.vibepocket.micro.START"
        const val stopAction = "au.edu.uts.vibepocket.micro.STOP"
        const val pulseAction = "au.edu.uts.vibepocket.micro.PULSE"
        const val encoderClockwiseAction = "au.edu.uts.vibepocket.micro.ENCODER_CLOCKWISE"
        const val encoderCounterclockwiseAction = "au.edu.uts.vibepocket.micro.ENCODER_COUNTERCLOCKWISE"
        const val encoderPressAction = "au.edu.uts.vibepocket.micro.ENCODER_PRESS"
        const val encoderReleaseAction = "au.edu.uts.vibepocket.micro.ENCODER_RELEASE"
        const val encoderClickAction = "au.edu.uts.vibepocket.micro.ENCODER_CLICK"

        private const val tag = "VibePocketMicro"
        private const val channelId = "micro-experiment"
        internal const val encoderKey = "ENC"
        private const val notificationId = 61
        private const val fragmentDelayMs = 4L
        private const val encoderClickDurationMs = 80L
        private const val claimTimeoutMs = 5_000L
        private const val teardownTimeoutMs = 5_000L
        private const val teardownSettleMs = 100L
        private const val processExitDelayMs = 100L
        private const val recoveryRetryMs = 1_000L
        private const val deferredReconnectDelayMs = 250L
        private const val preparedWriteTimeoutMs = 750L
        private const val notificationCallbackTimeoutMs = 1_000L
        private const val gattRebuildSettleMs = 100L
        private val cccd = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
