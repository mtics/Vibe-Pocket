package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Capabilities
import au.edu.uts.vibepocket.control.Desktop
import au.edu.uts.vibepocket.control.Reasoning
import au.edu.uts.vibepocket.control.Selector
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.Status
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Binding
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import au.edu.uts.vibepocket.profile.Layer
import au.edu.uts.vibepocket.profile.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CatalogTest {
    @Test
    fun separatesGesturesAndKeepsOnlyTheFirstPhysicalCopyOfAnAction() {
        val clear = Input("key_clear", Input.Kind.KEY, "Delete", "clear")
        val duplicate = Input("touch", Input.Kind.TOUCH, "Review", "review")
        val first = Input("joystick_up", Input.Kind.JOYSTICK, "Review", "review")
        val review = Action("workflow", workflowId = "review-pr")
        val layer = Layer(
            "layer-1",
            "Default",
            null,
            mapOf(
                clear.id to Binding(
                    mapOf(
                        Gesture.Kind.TAP to Action("delete_backward"),
                        Gesture.Kind.HOLD to Action("clear_input"),
                    ),
                ),
                first.id to Binding(mapOf(Gesture.Kind.TAP to review)),
                duplicate.id to Binding(mapOf(Gesture.Kind.TAP to review)),
            ),
        )
        val catalog = Catalog.from(snapshot(listOf(clear, first, duplicate), layer))

        assertEquals(Gesture.Kind.TAP, catalog.find("delete_backward")?.gesture)
        assertEquals(Gesture.Kind.HOLD, catalog.find("clear_input")?.gesture)
        assertSame(first, catalog.find("workflow", workflowId = "review-pr")?.input)
    }

    private fun snapshot(inputs: List<Input>, layer: Layer) = Snapshot(
        revision = "r1",
        status = Status("ready", null),
        capabilities = Capabilities(),
        desktop = Desktop(
            profile = Profile(4, inputs, emptyList(), listOf(layer)),
            gestures = emptyList(),
            choices = emptyList(),
            activeLayerId = layer.id,
            foreground = true,
            activity = Activity.IDLE,
            agents = emptyList(),
            focusedAgentIndex = -1,
            focusedAgentId = null,
            voice = null,
            mode = Selector(false, ""),
            reasoning = Reasoning.Unavailable,
        ),
    )
}
