package au.edu.uts.vibepocket.control

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

class ContextTransitionTest {
    @Test
    fun everyTargetRequiresItsExactFreshSnapshot() {
        assertFalse(ContextTransition.NewDesktop(AgentA).matches(snapshot(focusedAgentId = AgentA)))
        assertTrue(ContextTransition.NewDesktop(AgentA).matches(snapshot(focusedAgentId = AgentB)))
        assertTrue(ContextTransition.Attached.matches(snapshot(foreground = true)))
        assertFalse(ContextTransition.Attached.matches(snapshot(foreground = false)))
        assertTrue(ContextTransition.Agent(AgentB).matches(snapshot(focusedAgentId = AgentB)))
        assertFalse(ContextTransition.Agent(AgentB).matches(snapshot(focusedAgentId = AgentA)))
        assertTrue(ContextTransition.Model("model-2").matches(snapshot(modelId = "model-2")))
        assertFalse(ContextTransition.Model("model-2").matches(snapshot(modelId = "model-1")))
        assertTrue(ContextTransition.Mode("plan").matches(snapshot(modeId = "plan")))
        assertTrue(ContextTransition.Reasoning(Reasoning.Level.HIGH).matches(snapshot(reasoning = Reasoning.Level.HIGH)))
        assertTrue(ContextTransition.Layer("layer-2").matches(snapshot(activeLayerId = "layer-2")))
        assertFalse(ContextTransition.Layer("layer-2").matches(snapshot(activeLayerId = "layer-1")))
        assertFalse(
            ContextTransition.Agent(AgentB).matches(
                snapshot(focusedAgentId = AgentB).copy(transportFresh = false),
            ),
        )
    }

    @Test
    fun mappedWorkflowNewTaskAttachAndFocusActionsAreClassifiedStructurally() {
        assertEquals(
            ContextTransition.NewDesktop(AgentA),
            binding(Action("workflow", workflowId = "debug")).contextTransition(snapshot()),
        )
        assertEquals(
            ContextTransition.NewDesktop(AgentA),
            binding(Action("new_task")).contextTransition(snapshot()),
        )
        assertEquals(ContextTransition.Attached, binding(Action("attach")).contextTransition(snapshot()))
        assertEquals(
            ContextTransition.Agent(AgentB),
            binding(Action("focus_next")).contextTransition(snapshot()),
        )
        assertEquals(
            ContextTransition.Agent(AgentB),
            binding(Action("focus_agent", index = 1)).contextTransition(snapshot()),
        )
        assertEquals(
            ContextTransition.Layer("layer-2"),
            binding(Action("select_layer", layerId = "layer-2")).contextTransition(snapshot()),
        )
    }

    @Test
    fun directCommandsSeparateSettingsFromContextTransitions() {
        val snapshot = snapshot()

        assertEquals(ContextTransition.Agent(AgentB), Command.SelectAgent(AgentB).contextTransition(snapshot))
        assertEquals(null, Command.SelectModel(Target, "model-2").contextTransition(snapshot))
        assertEquals(ContextTransition.Mode("plan"), Command.SelectMode(Target, "plan").contextTransition(snapshot))
        assertEquals(null, Command.SelectReasoning(Target, Reasoning.Level.HIGH).contextTransition(snapshot))
        assertEquals(null, Command.AdjustReasoning(Target, 1).contextTransition(snapshot))
        assertEquals(ContextTransition.Layer("layer-2"), Command.SelectLayer("layer-2").contextTransition(snapshot))
        assertEquals(ContextTransition.NewDesktop(AgentA), Command.NewTask.contextTransition(snapshot))
        assertEquals(ContextTransition.Attached, Command.Attach.contextTransition(snapshot))
    }

    @Test
    fun focusNextWithoutTrackedAgentsTargetsAttachment() {
        val empty = snapshot().copy(desktop = requireNotNull(snapshot().desktop).copy(agents = emptyList()))

        assertEquals(ContextTransition.Attached, binding(Action("focus_next")).contextTransition(empty))
    }

    private fun binding(action: Action): Command.Binding = Command.Binding(
        inputId = InputId,
        gesture = Gesture.Kind.TAP,
        layerId = "layer-1",
        action = action,
    )

    private fun snapshot(
        focusedAgentId: String? = AgentA,
        modelId: String? = "model-1",
        activeLayerId: String = "layer-1",
        foreground: Boolean = true,
        modeId: String = "default",
        reasoning: Reasoning.Level = Reasoning.Level.MEDIUM,
    ): Snapshot {
        val layers = listOf("layer-1", "layer-2").map { id ->
            Layer(
                id = id,
                name = id,
                color = "#FFFFFF",
                bindings = mapOf(InputId to Binding(mapOf(Gesture.Kind.TAP to Action("approve")))),
            )
        }
        val agents = listOf(AgentA, AgentB).mapIndexed { index, id ->
            Agent(id, "Agent ${index + 1}", Activity.IDLE, id == focusedAgentId)
        }
        return Snapshot(
            revision = "r_test",
            status = Status("ready", null),
            capabilities = Capabilities(),
            desktop = Desktop(
                profile = Profile(
                    version = 3,
                    inputs = listOf(Input(InputId, Input.Kind.KEY, "Test", "test")),
                    workflows = emptyList(),
                    layers = layers,
                ),
                gestures = emptyList(),
                choices = emptyList(),
                activeLayerId = activeLayerId,
                foreground = foreground,
                activity = Activity.IDLE,
                agents = agents,
                focusedAgentIndex = agents.indexOfFirst { it.id == focusedAgentId },
                focusedAgentId = focusedAgentId,
                voice = Voice(available = true, active = false),
                mode = Selector(true, modeId, modeId),
                model = Model(
                    available = true,
                    id = modelId,
                    label = modelId.orEmpty(),
                    options = emptyList(),
                ),
                reasoning = Reasoning(true, reasoning.displayLabel, reasoning, true, true),
            ),
        )
    }

    private companion object {
        const val InputId = "key_test"
        const val AgentA = "agent-aaaaaaaaaaaaaaaaaaaaaaaa"
        const val AgentB = "agent-bbbbbbbbbbbbbbbbbbbbbbbb"
        val Target = TargetRef("thread-1", AgentA, 4, "bridge-1", 7, "workspace-1")
    }
}
