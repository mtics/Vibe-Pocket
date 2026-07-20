package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Capabilities
import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.Desktop
import au.edu.uts.vibepocket.control.Model
import au.edu.uts.vibepocket.control.Question
import au.edu.uts.vibepocket.control.Reasoning
import au.edu.uts.vibepocket.control.Selector
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {
    @Test
    fun modelSelectionRequiresFreshForegroundStateWithoutAQuestion() {
        var current = snapshot()
        val delivered = mutableListOf<Command>()
        val commands = Commands(
            snapshot = { current },
            deliver = { command, _, _ -> delivered.add(command) },
        )

        assertTrue(commands.selectModel("model-2"))
        current = snapshot(foreground = false)
        assertFalse(commands.selectModel("model-2"))
        current = snapshot(question = question())
        assertFalse(commands.selectModel("model-2"))
        current = snapshot(fresh = false)
        assertFalse(commands.selectModel("model-2"))

        assertEquals(listOf(Command.SelectModel("model-2")), delivered)
    }

    @Test
    fun modeAndReasoningSelectionUseExactTargets() {
        val delivered = mutableListOf<Command>()
        val commands = Commands(
            snapshot = { snapshot() },
            deliver = { command, _, _ -> delivered.add(command) },
        )

        assertTrue(commands.selectMode("plan"))
        assertTrue(commands.selectReasoning(Reasoning.Level.HIGH))

        assertEquals(
            listOf(Command.SelectMode("plan"), Command.SelectReasoning(Reasoning.Level.HIGH)),
            delivered,
        )
    }

    private fun snapshot(
        foreground: Boolean = true,
        question: Question? = null,
        fresh: Boolean = true,
    ) = Snapshot(
        revision = "r_test",
        status = Status("ready", null),
        capabilities = Capabilities(model = true, modeCycle = true, reasoning = true),
        desktop = Desktop(
            profile = null,
            gestures = emptyList(),
            choices = emptyList(),
            activeLayerId = null,
            foreground = foreground,
            activity = Activity.IDLE,
            agents = emptyList(),
            focusedAgentIndex = -1,
            focusedAgentId = null,
            voice = null,
            mode = Selector(
                true,
                "Default",
                "default",
                listOf(
                    Selector.Option("default", "Default", true),
                    Selector.Option("plan", "Plan", false),
                ),
            ),
            model = Model(
                available = true,
                id = "model-1",
                label = "Model 1",
                options = listOf(
                    Model.Option("model-1", "Model 1", true),
                    Model.Option("model-2", "Model 2", false),
                ),
            ),
            reasoning = Reasoning(
                available = true,
                label = "Medium",
                level = Reasoning.Level.MEDIUM,
                canIncrease = true,
                canDecrease = true,
                options = listOf(Reasoning.Level.LOW, Reasoning.Level.MEDIUM, Reasoning.Level.HIGH),
            ),
            question = question,
        ),
        transportFresh = fresh,
    )

    private fun question() = Question(
        index = 0,
        count = 1,
        header = "Choose",
        text = "Pick one",
        options = emptyList(),
        selectedOptionIndex = -1,
        hasSpokenAnswer = false,
        isSecret = false,
    )
}
