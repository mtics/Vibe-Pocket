package au.edu.uts.vibepocket.hardware.micro

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryTest {
    @Test
    fun mapsAndroidBatteryScaleToGattPercentage() {
        val state = batteryState(level = 173, scale = 200, charging = true)

        assertEquals(86, state.level)
        assertTrue(state.charging)
        assertArrayEquals(byteArrayOf(86), state.gattValue())
    }

    @Test
    fun invalidAndOutOfRangeSamplesAreBounded() {
        assertEquals(BatteryState.unknown, batteryState(-1, 100, charging = true))
        assertEquals(BatteryState.unknown, batteryState(50, 0, charging = true))
        val overfull = batteryState(120, 100, charging = false)
        assertEquals(100, overfull.level)
        assertFalse(overfull.charging)
    }

    @Test
    fun notifiesOnlySubscribedBondedHostWhenPercentageChanges() {
        val policy = BatteryPolicy<String>(BatteryState(80, charging = false))

        assertNull(policy.sample(BatteryState(81, false), "host", bonded = false, subscribed = true))
        assertNull(policy.sample(BatteryState(82, false), "host", bonded = true, subscribed = false))
        val notification = policy.sample(BatteryState(83, true), "host", bonded = true, subscribed = true)

        assertEquals("host", notification?.host)
        assertEquals(BatteryState(83, true), notification?.state)
        assertEquals(notification?.state, policy.state)
    }

    @Test
    fun chargingOnlyChangesUpdateSharedStateWithoutBatteryLevelNotification() {
        val policy = BatteryPolicy<String>(BatteryState(64, charging = false))

        val notification = policy.sample(
            BatteryState(64, charging = true),
            host = "host",
            bonded = true,
            subscribed = true,
        )

        assertNull(notification)
        assertEquals(BatteryState(64, charging = true), policy.state)
        assertArrayEquals(byteArrayOf(64), policy.state.gattValue())
    }
}
