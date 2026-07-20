package au.edu.uts.vibepocket.ui.control.stage

import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.ui.control.state.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageTest {
    @Test
    fun compactStatusKeepsEveryHiddenDetailInSemanticsAndCanExpand() {
        val state = State(
            kind = State.Kind.QUESTION,
            activity = Activity.WAITING,
            title = "Choose scope",
            task = "Vibe Pocket",
            detail = "Which module should change?",
            selection = "Android only",
            meta = "Question 1 of 2",
        )

        assertTrue(stageCanExpand(state))
        assertEquals(
            "Choose scope. Vibe Pocket. Which module should change?. Android only. Question 1 of 2",
            stageDescription(state),
        )
    }

    @Test
    fun plainReadyStatusDoesNotPretendToHaveMoreContent() {
        val state = State(State.Kind.READY, Activity.IDLE, "Ready", "Vibe Pocket", null)

        assertFalse(stageCanExpand(state))
        assertEquals(null, stageSupportingText(state))
        assertEquals("Ready. Vibe Pocket", stageDescription(state))
    }
}
