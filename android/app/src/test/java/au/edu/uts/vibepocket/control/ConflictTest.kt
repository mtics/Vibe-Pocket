package au.edu.uts.vibepocket.control

import au.edu.uts.vibepocket.profile.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class ConflictTest {
    @Test
    fun semanticActionsShareTheExpectedExclusiveGroups() {
        assertEquals(setOf(ConflictGroup.DECISION), Action("approve").conflictGroups())
        assertEquals(setOf(ConflictGroup.DECISION), Action("reject").conflictGroups())
        assertEquals(setOf(ConflictGroup.DRAFT), Action("delete_backward").conflictGroups())
        assertEquals(setOf(ConflictGroup.DRAFT), Action("clear_input").conflictGroups())
        assertEquals(setOf(ConflictGroup.RUN), Action("stop").conflictGroups())
        assertEquals(
            setOf(ConflictGroup.CONTEXT, ConflictGroup.RUN),
            Action("new_task").conflictGroups(),
        )
    }

    @Test
    fun navigationAndContextControlsRemainDistinctGroups() {
        assertEquals(setOf(ConflictGroup.NAVIGATION), Action("navigate").conflictGroups())
        assertEquals(setOf(ConflictGroup.CONTEXT), Action("mode_cycle").conflictGroups())
    }
}
