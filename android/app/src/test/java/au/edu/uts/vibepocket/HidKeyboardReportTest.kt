package au.edu.uts.vibepocket

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HidKeyboardReportTest {
    @Test
    fun encodesBootKeyboardReportAndRelease() {
        assertArrayEquals(
            byteArrayOf(0x02, 0, 0x2B, 0, 0, 0, 0, 0),
            HidKeyboardReport.encode(
                HidChord(HidKeyboardReport.MODIFIER_LEFT_SHIFT, HidKeyboardReport.USAGE_TAB),
            ),
        )
        assertArrayEquals(ByteArray(8), HidKeyboardReport.release)
    }

    @Test
    fun mapsVisibleCodexControlsToStableKeyboardChords() {
        assertEquals(
            listOf(HidChord(usage = HidKeyboardReport.USAGE_ENTER)),
            CodexHidMapping.chords(ControllerAction("approve")),
        )
        assertEquals(
            listOf(
                HidChord(HidKeyboardReport.MODIFIER_LEFT_GUI, HidKeyboardReport.USAGE_A),
                HidChord(usage = HidKeyboardReport.USAGE_BACKSPACE),
            ),
            CodexHidMapping.chords(ControllerAction("clear_input")),
        )
        assertEquals(
            listOf(HidChord(usage = HidKeyboardReport.USAGE_LEFT)),
            CodexHidMapping.chords(ControllerAction("navigate", direction = "left")),
        )
    }

    @Test
    fun leavesCodexSemanticActionsForBridge() {
        assertNull(CodexHidMapping.chords(ControllerAction("reasoning_depth", delta = 1)))
        assertNull(CodexHidMapping.chords(ControllerAction("workflow", workflowId = "review")))
    }
}
