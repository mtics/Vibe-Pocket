package au.edu.uts.vibepocket.hardware.micro

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GattContractTest {
    @Test
    fun vendorReportMapKeepsReportSixAndSixtyThreeByteBodies() {
        assertArrayEquals(
            byteArrayOf(
                0x06, 0x00, 0xFF.toByte(),
                0x09, 0x01,
                0xA1.toByte(), 0x01,
                0x85.toByte(), 0x06,
                0x15, 0x00,
                0x26, 0xFF.toByte(), 0x00,
                0x75, 0x08,
                0x95.toByte(), 0x3F,
                0x09, 0x01,
                0x81.toByte(), 0x02,
                0x95.toByte(), 0x3F,
                0x09, 0x02,
                0x91.toByte(), 0x02,
                0xC0.toByte(),
            ),
            Gatt.reportMap,
        )
    }
}
