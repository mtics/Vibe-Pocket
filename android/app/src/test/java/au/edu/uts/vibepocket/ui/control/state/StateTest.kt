package au.edu.uts.vibepocket.ui.control.state

import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Capabilities
import au.edu.uts.vibepocket.control.Desktop
import au.edu.uts.vibepocket.control.Model
import au.edu.uts.vibepocket.control.Question
import au.edu.uts.vibepocket.control.Reasoning
import au.edu.uts.vibepocket.control.Selector
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.Status
import au.edu.uts.vibepocket.control.Voice
import org.junit.Assert.assertEquals
import org.junit.Test

class StateTest {
    @Test
    fun questionTakesPriorityOverTaskActivity() {
        val snapshot = snapshot(
            activity = Activity.EXECUTING,
            question = Question(
                index = 0,
                count = 2,
                header = "Choose scope",
                text = "Which module should change?",
                options = listOf(Question.Option("Android", "Only the phone app")),
                selectedOptionIndex = 0,
                hasSpokenAnswer = false,
                isSecret = false,
            ),
        )

        val state = snapshot.state()

        assertEquals(State.Kind.QUESTION, state.kind)
        assertEquals("Question 1 of 2", state.meta)
        assertEquals("Android  Only the phone app", state.selection)
    }

    @Test
    fun activeAndWaitingTasksMapToDistinctControls() {
        assertEquals(State.Kind.RUNNING, snapshot(Activity.THINKING).state().kind)
        assertEquals(State.Kind.RUNNING, snapshot(Activity.EXECUTING).state().kind)
        assertEquals(State.Kind.DECISION, snapshot(Activity.WAITING).state().kind)
    }

    @Test
    fun degradedBridgeOverridesDesktopState() {
        val snapshot = snapshot(Activity.IDLE).copy(
            status = Status("degraded", "Desktop Codex is unavailable."),
        )

        val state = snapshot.state()

        assertEquals(State.Kind.ERROR, state.kind)
        assertEquals("Desktop Codex is unavailable.", state.detail)
    }

    private fun snapshot(
        activity: Activity,
        question: Question? = null,
    ) = Snapshot(
        revision = "r1",
        status = Status("ready", null),
        capabilities = Capabilities(),
        desktop = Desktop(
            profile = null,
            gestures = emptyList(),
            choices = emptyList(),
            activeLayerId = null,
            foreground = true,
            activity = activity,
            agents = emptyList(),
            focusedAgentIndex = -1,
            focusedAgentId = null,
            voice = Voice(false, false),
            mode = Selector(false, ""),
            access = Selector(false, ""),
            model = Model.Unavailable,
            reasoning = Reasoning.Unavailable,
            question = question,
        ),
    )
}
