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
import au.edu.uts.vibepocket.control.Tasks
import au.edu.uts.vibepocket.control.Voice
import au.edu.uts.vibepocket.session.Operation
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

    @Test
    fun staleTransportNeverLooksReady() {
        val state = snapshot(Activity.IDLE).copy(transportFresh = false).state()

        assertEquals(State.Kind.STALE, state.kind)
        assertEquals("Connection stale", state.title)
    }

    @Test
    fun staleTaskCatalogNeverLooksReady() {
        val snapshot = snapshot(Activity.IDLE)
        val desktop = requireNotNull(snapshot.desktop)

        val state = snapshot.copy(
            desktop = desktop.copy(
                tasks = Tasks(Tasks.Availability.STALE, "Catalog refresh failed."),
            ),
        ).state()

        assertEquals(State.Kind.STALE, state.kind)
        assertEquals("Task list stale", state.title)
        assertEquals("Catalog refresh failed.", state.detail)
    }

    @Test
    fun unknownOperationOverridesAReadyTaskWithoutDiscardingItsContext() {
        val state = snapshot(Activity.IDLE).state(
            Operation(
                id = "operation-1",
                uiId = "input:key_accept:tap",
                phase = Operation.Phase.UNKNOWN,
                message = "The result could not be confirmed.",
            ),
        )

        assertEquals(State.Kind.UNKNOWN, state.kind)
        assertEquals("Outcome unknown", state.title)
        assertEquals("The result could not be confirmed.", state.detail)
    }

    @Test
    fun failedOperationNeverLooksReady() {
        val state = snapshot(Activity.IDLE).state(
            Operation(
                id = "operation-2",
                uiId = "input:key_stop:tap",
                phase = Operation.Phase.FAILED,
                message = "The Mac rejected the command.",
            ),
        )

        assertEquals(State.Kind.ERROR, state.kind)
        assertEquals("Command failed", state.title)
        assertEquals("The Mac rejected the command.", state.detail)
    }

    @Test
    fun unknownOperationKeepsTheConfirmedTaskButReportsUncertainty() {
        val state = snapshot(Activity.IDLE).state(
            Operation("operation-1", "approve", Operation.Phase.UNKNOWN, "Check the Mac before retrying."),
        )

        assertEquals(State.Kind.UNKNOWN, state.kind)
        assertEquals("Outcome unknown", state.title)
        assertEquals("Check the Mac before retrying.", state.detail)
    }

    @Test
    fun failedOperationIsDistinctFromDesktopTaskFailure() {
        val state = snapshot(Activity.IDLE).state(
            Operation("operation-2", "stop", Operation.Phase.FAILED, "The Bridge rejected Stop."),
        )

        assertEquals(State.Kind.ERROR, state.kind)
        assertEquals("Command failed", state.title)
        assertEquals("The Bridge rejected Stop.", state.detail)
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
