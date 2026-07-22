package au.edu.uts.vibepocket.session

import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Capabilities
import au.edu.uts.vibepocket.control.Desktop
import au.edu.uts.vibepocket.control.Model
import au.edu.uts.vibepocket.control.Reasoning
import au.edu.uts.vibepocket.control.Selector
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.Sources
import au.edu.uts.vibepocket.control.Status
import au.edu.uts.vibepocket.control.TargetRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionTest {
    @Test
    fun bridgePredictionStartsDeadlineAndObservationAtAcknowledgement() {
        var now = 1_000L
        val prediction = Prediction { now }
        val initial = State(snapshot = snapshot())
        prediction.beginModel(initial, "terra", requiresAcknowledgement = true)
        prediction.observeAfter(1L)

        assertNull(prediction.deadline())
        assertEquals(
            Prediction.Observation.Pending,
            prediction.observe(modelSnapshot("terra"), 1L),
        )

        now = 80_000L
        val deadline = requireNotNull(prediction.acknowledge())
        prediction.observeAfter(2L)

        assertEquals(83_000L, deadline.expiresAtMillis)
        assertEquals(
            Prediction.Observation.Pending,
            prediction.observe(modelSnapshot("terra"), 1L),
        )
        assertTrue(prediction.observe(modelSnapshot("terra"), 2L) is Prediction.Observation.Confirmed)
    }

    @Test
    fun temporarySelectorLossRemainsPendingUntilTargetsAreObserved() {
        val model = Prediction { 1_000L }
        model.beginModel(State(snapshot = snapshot()), "terra")
        model.observeAfter(1L)
        assertEquals(
            Prediction.Observation.Pending,
            model.observe(modelSnapshot("", available = false, modelCapability = false), 1L),
        )
        assertTrue(model.observe(modelSnapshot("terra"), 2L) is Prediction.Observation.Confirmed)

        val reasoning = Prediction { 1_000L }
        reasoning.beginReasoning(State(snapshot = snapshot()), Reasoning.Level.HIGH)
        reasoning.observeAfter(1L)
        assertEquals(
            Prediction.Observation.Pending,
            reasoning.observe(reasoningUnavailableSnapshot(), 1L),
        )
        assertTrue(
            reasoning.observe(snapshot(Reasoning.Level.HIGH), 2L) is Prediction.Observation.Confirmed,
        )
    }

    @Test
    fun observationPreparedBeforeNewPredictionCannotClearIt() {
        val prediction = Prediction { 1_000L }
        var state = prediction.beginReasoning(State(snapshot = snapshot()), Reasoning.Level.HIGH)
        val first = requireNotNull(prediction.deadline())
        prediction.observeAfter(10L)

        assertEquals(
            Prediction.Observation.Pending,
            prediction.observe(snapshot(Reasoning.Level.HIGH), 9L),
        )
        assertEquals(first.predictionId, prediction.timeout(first.predictionId)?.predictionId)

        state = prediction.beginReasoning(prediction.present(state), Reasoning.Level.LOW)
        val second = requireNotNull(prediction.deadline())
        assertNotEquals(first.predictionId, second.predictionId)

        assertEquals(
            Prediction.Observation.Pending,
            prediction.observe(snapshot(Reasoning.Level.LOW), 10L),
        )
        assertEquals(Reasoning.Level.LOW, prediction.target())

        prediction.observeAfter(11L)
        assertTrue(prediction.observe(snapshot(Reasoning.Level.LOW), 11L) is Prediction.Observation.Confirmed)
        assertNull(prediction.target())
        assertEquals(null, prediction.present(state).reasoningTarget)
    }

    @Test
    fun olderTimeoutCannotClearNewerPredictionWithSameDeadline() {
        val prediction = Prediction { 1_000L }
        var state = prediction.beginReasoning(State(snapshot = snapshot()), Reasoning.Level.HIGH)
        val first = requireNotNull(prediction.deadline())
        assertEquals(first.predictionId, prediction.timeout(first.predictionId)?.predictionId)

        state = prediction.beginReasoning(prediction.present(state), Reasoning.Level.LOW)
        val second = requireNotNull(prediction.deadline())
        assertEquals(first.expiresAtMillis, second.expiresAtMillis)
        assertNotEquals(first.predictionId, second.predictionId)

        assertNull(prediction.timeout(first.predictionId))
        assertEquals(Reasoning.Level.LOW, prediction.target())
        assertEquals(Reasoning.Level.LOW, state.reasoningTarget)
    }

    private fun snapshot(level: Reasoning.Level = Reasoning.Level.MEDIUM): Snapshot = Snapshot(
        revision = "r-$level",
        status = Status("ready", null),
        capabilities = Capabilities(model = true, reasoning = true),
        desktop = Desktop(
            profile = null,
            gestures = emptyList(),
            choices = emptyList(),
            activeLayerId = null,
            foreground = true,
            activity = Activity.IDLE,
            agents = emptyList(),
            focusedAgentIndex = -1,
            focusedAgentId = AgentId,
            voice = null,
            mode = Selector(false, ""),
            model = Model(
                available = true,
                id = "sol",
                label = "Sol",
                options = listOf(Model.Option("sol", "Sol", true)),
            ),
            reasoning = Reasoning(
                available = true,
                label = level.displayLabel,
                level = level,
                canIncrease = level != Reasoning.Level.HIGH,
                canDecrease = level != Reasoning.Level.LOW,
                options = listOf(Reasoning.Level.LOW, Reasoning.Level.MEDIUM, Reasoning.Level.HIGH),
            ),
            binding = Desktop.Binding(
                Desktop.Binding.State.CONFIRMED,
                AgentId,
                Desktop.Binding.Target.bound(Target),
            ),
        ),
        sources = Sources(Sources.Source(true), Sources.Source(true)),
    )

    private fun modelSnapshot(
        id: String,
        available: Boolean = true,
        modelCapability: Boolean = true,
    ): Snapshot = snapshot().copy(
        capabilities = snapshot().capabilities.copy(model = modelCapability),
        desktop = snapshot().desktop?.copy(
            model = snapshot().desktop!!.model.copy(available = available, id = id),
        ),
    )

    private fun reasoningUnavailableSnapshot(): Snapshot = snapshot().copy(
        capabilities = snapshot().capabilities.copy(reasoning = false),
        desktop = snapshot().desktop?.copy(reasoning = Reasoning.Unavailable),
    )

    private companion object {
        const val AgentId = "agent-111111111111111111111111"
        val Target = TargetRef(
            threadId = "thread-1",
            agentId = AgentId,
            bindingEpoch = 1L,
            bridgeInstanceId = "bridge-1",
            appServerGeneration = 1L,
            canonicalWorkspaceId = "workspace-1",
        )
    }
}
