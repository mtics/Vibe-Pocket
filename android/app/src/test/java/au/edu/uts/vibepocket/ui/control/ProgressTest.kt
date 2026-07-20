package au.edu.uts.vibepocket.ui.control

import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTest {
    @Test
    fun fastCommandsDoNotReplaceTheirControlWithProgress() {
        assertTrue(ProgressDelayMillis >= 700L)
    }
}
