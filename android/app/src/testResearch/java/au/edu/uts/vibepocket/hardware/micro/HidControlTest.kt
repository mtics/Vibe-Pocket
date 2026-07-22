package au.edu.uts.vibepocket.hardware.micro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HidControlTest {
    @Test
    fun acceptsOnlyHidSuspendAndExitSuspend() {
        assertEquals(HidControl.SUSPEND, hidControl(byteArrayOf(0x00)))
        assertEquals(HidControl.EXIT_SUSPEND, hidControl(byteArrayOf(0x01)))
        assertNull(hidControl(byteArrayOf(0x02)))
        assertNull(hidControl(byteArrayOf()))
        assertNull(hidControl(byteArrayOf(0x00, 0x01)))
    }
}
