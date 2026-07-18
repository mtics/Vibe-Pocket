package au.edu.uts.vibepocket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningStatusTest {
    @Test
    fun minimumDisablesOnlyTheDecreaseDirection() {
        val status = ReasoningStatus(
            available = true,
            label = "5.6 Sol 最小",
            level = ReasoningLevel.MINIMAL,
            canIncrease = true,
            canDecrease = false,
        )

        assertTrue(status.allows(1))
        assertFalse(status.allows(-1))
    }

    @Test
    fun unknownLabelsRemainAdjustableWhenTheStructuralControlExists() {
        val status = ReasoningStatus(
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
