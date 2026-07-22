package au.edu.uts.vibepocket.hardware.micro

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import au.edu.uts.vibepocket.hardware.micro.protocol.Frame
import java.nio.charset.StandardCharsets
import java.util.UUID

data class Schema(
    val services: List<BluetoothGattService>,
    val information: BluetoothGattCharacteristic,
    val map: BluetoothGattCharacteristic,
    val control: BluetoothGattCharacteristic,
    val input: BluetoothGattCharacteristic,
    val output: BluetoothGattCharacteristic,
    val mode: BluetoothGattCharacteristic,
    val manufacturer: BluetoothGattCharacteristic,
    val pnp: BluetoothGattCharacteristic,
    val battery: BluetoothGattCharacteristic,
)

object Gatt {
    val hid: UUID = uuid(0x1812)

    val reportMap = byteArrayOf(
        0x06, 0x00, 0xFF.toByte(),
        0x09, 0x01,
        0xA1.toByte(), 0x01,
        0x85.toByte(), Frame.reportId.toByte(),
        0x15, 0x00,
        0x26, 0xFF.toByte(), 0x00,
        0x75, 0x08,
        0x95.toByte(), Frame.bodySize.toByte(),
        0x09, 0x01,
        0x81.toByte(), 0x02,
        0x95.toByte(), Frame.bodySize.toByte(),
        0x09, 0x02,
        0x91.toByte(), 0x02,
        0xC0.toByte(),
    )

    fun create(initialBattery: ByteArray = BatteryState.unknown.gattValue()): Schema {
        val informationService = BluetoothGattService(uuid(0x180A), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val manufacturer = characteristic(
            0x2A29,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            "Work Louder".toByteArray(StandardCharsets.UTF_8),
        )
        val pnp = characteristic(
            0x2A50,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            byteArrayOf(0x02, 0x3A, 0x30, 0x60, 0x83.toByte(), 0x01, 0x01),
        )
        informationService.addCharacteristic(manufacturer)
        informationService.addCharacteristic(pnp)

        val batteryService = BluetoothGattService(uuid(0x180F), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val battery = characteristic(
            0x2A19,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            initialBattery,
        ).withCccd()
        batteryService.addCharacteristic(battery)

        val hidService = BluetoothGattService(hid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val information = characteristic(
            0x2A4A,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            byteArrayOf(0x11, 0x01, 0x00, 0x01),
        )
        val map = characteristic(
            0x2A4B,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            reportMap,
        )
        val control = characteristic(
            0x2A4C,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
            byteArrayOf(0),
        )
        val input = characteristic(
            0x2A4D,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            ByteArray(Frame.bodySize),
        ).withReportReference(1).withCccd()
        val output = characteristic(
            0x2A4D,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
            ByteArray(Frame.bodySize),
        ).withReportReference(2)
        val mode = characteristic(
            0x2A4E,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
            byteArrayOf(1),
        )
        listOf(information, map, control, input, output, mode).forEach(hidService::addCharacteristic)
        // Keep Battery independently discoverable until a physical trace establishes
        // whether the target host requires the optional included-service relationship.

        return Schema(
            services = listOf(informationService, batteryService, hidService),
            information = information,
            map = map,
            control = control,
            input = input,
            output = output,
            mode = mode,
            manufacturer = manufacturer,
            pnp = pnp,
            battery = battery,
        )
    }

    private fun characteristic(
        id: Int,
        properties: Int,
        permissions: Int,
        initial: ByteArray,
    ): BluetoothGattCharacteristic = BluetoothGattCharacteristic(uuid(id), properties, permissions).also {
        @Suppress("DEPRECATION")
        it.value = initial
    }

    private fun BluetoothGattCharacteristic.withCccd(): BluetoothGattCharacteristic = apply {
        val descriptor = BluetoothGattDescriptor(
            uuid(0x2902),
            BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or
                BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,
        )
        @Suppress("DEPRECATION")
        descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        addDescriptor(descriptor)
    }

    private fun BluetoothGattCharacteristic.withReportReference(type: Int): BluetoothGattCharacteristic = apply {
        BluetoothGattDescriptor(
            uuid(0x2908),
            BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED,
        ).also { descriptor ->
            @Suppress("DEPRECATION")
            descriptor.value = byteArrayOf(Frame.reportId.toByte(), type.toByte())
            addDescriptor(descriptor)
        }
    }

    private fun uuid(short: Int): UUID = UUID.fromString(
        "0000${short.toString(16).padStart(4, '0')}-0000-1000-8000-00805f9b34fb",
    )
}
