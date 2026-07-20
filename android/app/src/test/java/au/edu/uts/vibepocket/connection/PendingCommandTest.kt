package au.edu.uts.vibepocket.connection

import au.edu.uts.vibepocket.control.Command
import au.edu.uts.vibepocket.control.ContextTransition
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Gesture
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCommandTest {
    @Test
    fun durableOutboxRoundTripsEveryCommandBodyDeterministically() {
        val action = Action(
            type = "navigate",
            direction = "left",
            delta = -2,
            index = 3,
            workflowId = "debug",
            layerId = "layer-2",
        )
        val commands = listOf(
            Command.Binding("key_test", Gesture.Kind.DOUBLE_TAP, "layer-1", action),
            Command.SelectLayer("layer-2"),
            Command.FocusAgent(AgentId),
            Command.SelectModel("model-2"),
            Command.SelectMode("plan"),
            Command.SelectReasoning(au.edu.uts.vibepocket.control.Reasoning.Level.HIGH),
            Command.UpdateBinding("layer-1", "key_test", Gesture.Kind.HOLD, action),
            Command.ClearBinding("layer-1", "key_test", Gesture.Kind.TAP),
            Command.RenameLayer("layer-1", "Research"),
            Command.UpdateLayerColor("layer-1", "#55D6A4"),
            Command.UpdateWorkflowPrompt("debug", "Investigate from evidence."),
            Command.ResetProfile,
            Command.Attach,
            Command.Voice,
            Command.VoiceStart,
            Command.VoiceStop,
            Command.Stop,
            Command.NewTask,
            Command.ModelPicker,
            Command.Approve,
            Command.Reject,
        )

        commands.forEach { body ->
            val command = pending(command = body)
            val encoded = encodePendingCommand(command)
            assertEquals(encoded, encodePendingCommand(command))
            assertEquals(command, decodePendingCommand(encoded))
        }
    }

    @Test
    fun durableOutboxRoundTripsEveryTransitionTarget() {
        val targets = listOf(
            ContextTransition.NewDesktop(AgentId),
            ContextTransition.NewDesktop(null),
            ContextTransition.Attached,
            ContextTransition.Agent(AgentId),
            ContextTransition.Model("model-2"),
            ContextTransition.Mode("plan"),
            ContextTransition.Reasoning(au.edu.uts.vibepocket.control.Reasoning.Level.HIGH),
            ContextTransition.Layer("layer-2"),
        )

        targets.forEach { target ->
            val command = pending(target)
            assertEquals(command, decodePendingCommand(encodePendingCommand(command)))
        }
    }

    @Test
    fun outboxWithoutTransitionRemainsReadable() {
        val command = pending(null)

        val decoded = decodePendingCommand(encodePendingCommand(command))

        assertNull(decoded.transition)
        assertEquals(command, decoded)
    }

    @Test
    fun legacyOutboxWithoutCommandBodyFailsClosed() {
        val command = pending()
        val payload = JSONObject()
            .put("baseUrl", command.config.normalizedUrl)
            .put("token", command.config.credential)
            .put("operationId", command.operationId)
            .put("uiId", command.uiId)
            .toString()

        val error = assertThrows(IllegalArgumentException::class.java) {
            decodePendingCommand(payload)
        }

        assertEquals("The legacy pending command has no replayable command body.", error.message)
    }

    @Test
    fun malformedTransitionCannotSilentlyDowngradeToALegacyOutbox() {
        val payload = JSONObject(encodePendingCommand(pending(ContextTransition.Agent(AgentId))))
            .put("transition", "corrupt")
            .toString()

        assertThrows(IllegalArgumentException::class.java) {
            decodePendingCommand(payload)
        }
    }

    @Test
    fun durableQueueRoundTripsCommandsInFifoOrder() {
        val commands = listOf(
            pending(command = Command.Approve),
            pending(command = Command.Reject, uiId = "input:key_reject:tap"),
            pending(command = Command.Stop, uiId = "input:key_stop:tap"),
        )

        assertEquals(commands, decodePendingCommands(encodePendingCommands(commands)))
    }

    @Test
    fun singleRecordSchemaMigratesToAOneItemQueue() {
        val command = pending()

        assertEquals(listOf(command), decodePendingCommands(encodePendingCommand(command)))
    }

    @Test
    fun versionTwoRecordMigratesAsAlreadyDispatched() {
        val legacy = JSONObject(encodePendingCommand(pending())).put("version", 2)
        legacy.remove("phase")
        val payload = legacy.toString()

        val migrated = decodePendingCommand(payload)

        assertEquals(PendingCommand.Phase.DISPATCH_ATTEMPTED, migrated.phase)
    }

    @Test
    fun malformedDispatchPhaseFailsClosed() {
        val payload = JSONObject(encodePendingCommand(pending()))
            .put("phase", "maybe")
            .toString()

        assertThrows(IllegalArgumentException::class.java) {
            decodePendingCommand(payload)
        }
    }

    @Test
    fun durableQueueRejectsDuplicateUiIdsAndOversizedPayloads() {
        val duplicate = pending()
        val duplicatePayload = JSONObject(encodePendingCommands(listOf(duplicate)))
            .getJSONArray("commands")
            .put(JSONObject(encodePendingCommand(duplicate.copy(operationId = UUID.randomUUID().toString()))))
        val duplicateEnvelope = JSONObject()
            .put("version", 3)
            .put("commands", duplicatePayload)
            .toString()
        assertThrows(IllegalArgumentException::class.java) {
            decodePendingCommands(duplicateEnvelope)
        }

        val commands = (0..32).map { index ->
            pending(uiId = "input:key_test:tap:$index")
        }
        val oversized = JSONObject()
            .put("version", 3)
            .put("commands", org.json.JSONArray().apply {
                commands.forEach { put(JSONObject(encodePendingCommand(it))) }
            })
            .toString()
        val error = assertThrows(IllegalArgumentException::class.java) {
            decodePendingCommands(oversized)
        }
        assertTrue(error.message.orEmpty().contains("size"))
    }

    private fun pending(
        transition: ContextTransition? = null,
        command: Command = Command.Approve,
        uiId: String = "input:key_test:tap",
    ) = PendingCommand(
        config = Config("https://bridge.example.test", "0123456789abcdefghijklmn"),
        command = command,
        operationId = UUID.randomUUID().toString(),
        uiId = uiId,
        transition = transition,
    )

    private companion object {
        const val AgentId = "agent-aaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
