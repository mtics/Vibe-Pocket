package au.edu.uts.vibepocket.hid

import au.edu.uts.vibepocket.profile.Action
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportTest {
    @Test
    fun encodesBootKeyboardReportAndRelease() {
        assertArrayEquals(
            byteArrayOf(0x02, 0, 0x2B, 0, 0, 0, 0, 0),
            Report.encode(
                Chord(Report.MODIFIER_LEFT_SHIFT, Report.USAGE_TAB),
            ),
        )
        assertArrayEquals(ByteArray(8), Report.release)
    }

    @Test
    fun mapsVisibleCodexControlsToStableKeyboardChords() {
        assertEquals(
            listOf(Chord(usage = Report.USAGE_ENTER)),
            Mapping.chords(Action("approve")),
        )
        assertEquals(
            listOf(
                Chord(Report.MODIFIER_LEFT_GUI, Report.USAGE_A),
                Chord(usage = Report.USAGE_BACKSPACE),
            ),
            Mapping.chords(Action("clear_input")),
        )
        assertEquals(
            listOf(Chord(usage = Report.USAGE_LEFT)),
            Mapping.chords(Action("navigate", direction = "left")),
        )
    }

    @Test
    fun mapsCodexSemanticShortcutsForVirtualHardware() {
        assertEquals(
            listOf(
                Chord(
                    modifier = Report.MODIFIER_LEFT_CONTROL or
                        Report.MODIFIER_LEFT_SHIFT,
                    usage = Report.USAGE_M,
                ),
            ),
            Mapping.chords(Action("model_picker")),
        )
        assertEquals(
            listOf(
                Chord(
                    modifier = Report.MODIFIER_LEFT_CONTROL or
                        Report.MODIFIER_LEFT_SHIFT or
                        Report.MODIFIER_LEFT_ALT,
                    usage = Report.USAGE_U,
                ),
            ),
            Mapping.chords(Action("reasoning_depth", delta = 1)),
        )
        assertEquals(
            listOf(
                Chord(
                    modifier = Report.MODIFIER_LEFT_CONTROL or
                        Report.MODIFIER_LEFT_SHIFT or
                        Report.MODIFIER_LEFT_ALT,
                    usage = Report.USAGE_J,
                ),
            ),
            Mapping.chords(Action("reasoning_depth", delta = -1)),
        )
        assertNull(Mapping.chords(Action("workflow", workflowId = "review")))
    }

}
