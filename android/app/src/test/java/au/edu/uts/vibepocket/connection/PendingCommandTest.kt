package au.edu.uts.vibepocket.connection

import au.edu.uts.vibepocket.control.ContextTransition
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PendingCommandTest {
    @Test
    fun durableOutboxRoundTripsEveryTransitionTarget() {
        val targets = listOf(
            ContextTransition.NewDesktop(AgentId),
            ContextTransition.NewDesktop(null),
            ContextTransition.Attached,
            ContextTransition.Agent(AgentId),
            ContextTransition.Model("model-2"),
            ContextTransition.Layer("layer-2"),
        )

        targets.forEach { target ->
            val command = pending(target)
            assertEquals(command, decodePendingCommand(encodePendingCommand(command)))
        }
    }

    @Test
    fun legacyOutboxWithoutTransitionRemainsReadable() {
        val command = pending(null)

        val decoded = decodePendingCommand(encodePendingCommand(command))

        assertNull(decoded.transition)
        assertEquals(command, decoded)
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

    private fun pending(transition: ContextTransition?) = PendingCommand(
        config = Config("https://bridge.example.test", "0123456789abcdefghijklmn"),
        operationId = UUID.randomUUID().toString(),
        uiId = "input:key_test:tap",
        transition = transition,
    )

    private companion object {
        const val AgentId = "agent-aaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
