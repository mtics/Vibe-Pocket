package au.edu.uts.vibepocket.hardware.micro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalActionTest {
    @Test
    fun exposesEveryObservedMicroActionKey() {
        assertEquals(
            listOf("ACT06", "ACT07", "ACT08", "ACT09", "ACT10", "ACT12"),
            listOf(
                SignalAction.act06,
                SignalAction.act07,
                SignalAction.act08,
                SignalAction.act09,
                SignalAction.act10,
                SignalAction.act12,
            ).map(SignalAction::key),
        )
        assertNull(SignalAction.key("au.edu.uts.vibepocket.micro.ACT11"))
        assertNull(SignalAction.key("au.edu.uts.vibepocket.micro.ACT13"))
    }
}
