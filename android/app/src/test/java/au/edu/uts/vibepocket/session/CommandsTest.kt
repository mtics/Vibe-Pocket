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
import au.edu.uts.vibepocket.control.Sources
import au.edu.uts.vibepocket.control.Status
import au.edu.uts.vibepocket.control.TargetRef
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Binding
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import au.edu.uts.vibepocket.profile.Layer
import au.edu.uts.vibepocket.profile.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {
    @Test
    fun settingsSelectionRequiresFreshAppServerAndBoundTargetOnly() {
        var current = snapshot()
        val delivered = mutableListOf<Command>()
        val commands = Commands(
            snapshot = { current },
            deliver = { command, _ -> delivered.add(command) },
        )

        assertTrue(commands.selectModel("model-2"))
        current = snapshot(foreground = false)
        assertTrue(commands.selectModel("model-2"))
        current = snapshot(question = question())
        assertTrue(commands.selectModel("model-2"))
        current = snapshot(transportFresh = false)
        assertFalse(commands.selectModel("model-2"))
        current = snapshot(appServerFresh = false)
        assertFalse(commands.selectModel("model-2"))
        current = snapshot(target = null)
        assertFalse(commands.selectModel("model-2"))

        assertEquals(List(3) { Command.SelectModel(Target, "model-2") }, delivered)
    }

    @Test
    fun modeIsDisabledWhileReasoningUsesTheBoundTarget() {
        val delivered = mutableListOf<Command>()
        val commands = Commands(
            snapshot = { snapshot(foreground = false) },
            deliver = { command, _ -> delivered.add(command) },
        )

        assertFalse(commands.selectMode("plan"))
        assertTrue(commands.selectReasoning(Reasoning.Level.HIGH))

        assertEquals(
            listOf(Command.SelectReasoning(Target, Reasoning.Level.HIGH)),
            delivered,
        )
    }

    @Test
    fun settingsUiIdsAreIsolatedAcrossTargets() {
        var current = snapshot(target = Target)
        val ids = mutableListOf<String>()
        val commands = Commands(
            snapshot = { current },
            deliver = { _, uiId -> ids.add(uiId) },
        )

        assertTrue(commands.selectModel("model-2"))
        current = snapshot(target = Target.copy(bindingEpoch = Target.bindingEpoch + 1))
        assertTrue(commands.selectModel("model-2"))

        assertEquals(2, ids.distinct().size)
    }

    @Test
    fun reasoningBindingCapturesItsTargetBeforeDelivery() {
        val delivered = mutableListOf<Command>()
        val profile = Profile(
            version = 3,
            inputs = listOf(Input("dial_cw", Input.Kind.DIAL, "Reasoning", "dial")),
            workflows = emptyList(),
            layers = listOf(
                Layer(
                    id = "default",
                    name = "Default",
                    color = null,
                    bindings = mapOf(
                        "dial_cw" to Binding(
                            mapOf(Gesture.Kind.TAP to Action("reasoning_depth", delta = 1)),
                        ),
                    ),
                ),
            ),
        )
        val current = snapshot().let { state ->
            state.copy(
                desktop = requireNotNull(state.desktop).copy(
                    profile = profile,
                    activeLayerId = "default",
                ),
            )
        }
        val commands = Commands(
            snapshot = { current },
            deliver = { command, _ -> delivered.add(command) },
        )

        assertTrue(commands.activate("dial_cw", Gesture.Kind.TAP))
        assertEquals(listOf(Command.SelectReasoning(Target, Reasoning.Level.HIGH)), delivered)
    }

    private fun snapshot(
        foreground: Boolean = true,
        question: Question? = null,
        transportFresh: Boolean = true,
        appServerFresh: Boolean = true,
        target: TargetRef? = Target,
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
                increaseTo = Reasoning.Level.HIGH,
                decreaseTo = Reasoning.Level.LOW,
                options = listOf(Reasoning.Level.LOW, Reasoning.Level.MEDIUM, Reasoning.Level.HIGH),
            ),
            question = question,
            binding = Desktop.Binding(
                Desktop.Binding.State.CONFIRMED,
                "agent-111111111111111111111111",
                target?.let(Desktop.Binding.Target::bound) ?: Desktop.Binding.Target.Unbound,
            ),
        ),
        transportFresh = transportFresh,
        sources = Sources(
            appServer = Sources.Source(appServerFresh),
            desktopUI = Sources.Source(true),
        ),
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

    private companion object {
        val Target = TargetRef(
            threadId = "thread-1",
            agentId = "agent-111111111111111111111111",
            bindingEpoch = 4,
            bridgeInstanceId = "bridge-1",
            appServerGeneration = 7,
            canonicalWorkspaceId = "workspace-1",
        )
    }
}
