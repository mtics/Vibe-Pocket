package au.edu.uts.vibepocket.ui.control.actions

import au.edu.uts.vibepocket.ui.preference.Hand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionsTest {
    @Test
    fun handednessMirrorsOnlyTheTwoPrimaryBlocks() {
        assertTrue(padFirst(Hand.LEFT))
        assertFalse(padFirst(Hand.RIGHT))
    }
}
