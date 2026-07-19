package au.edu.uts.vibepocket.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningTest {
    @Test
    fun minimumDisablesOnlyTheDecreaseDirection() {
        val status = Reasoning(
            available = true,
            label = "5.6 Sol 最小",
            level = Reasoning.Level.MINIMAL,
            canIncrease = true,
            canDecrease = false,
        )

        assertTrue(status.allows(1))
        assertFalse(status.allows(-1))
    }

    @Test
    fun unknownLabelsRemainAdjustableWhenTheStructuralControlExists() {
        val status = Reasoning(
            available = true,
            label = "5.7 Preview",
            level = null,
            canIncrease = true,
            canDecrease = true,
        )

        assertTrue(status.allows(1))
        assertTrue(status.allows(-1))
    }
}
