package au.edu.uts.vibepocket.ui

import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Binding
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import au.edu.uts.vibepocket.profile.Layer
import au.edu.uts.vibepocket.profile.Profile
import au.edu.uts.vibepocket.ui.control.modelSelectionAllowed
import au.edu.uts.vibepocket.ui.control.inputInteractive
import au.edu.uts.vibepocket.ui.control.voiceControlAvailable
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiRobustnessTest {
    private val input = Input("key_accept", Input.Kind.KEY, "Accept", "check")
    private val approve = Action("approve")
    private val reject = Action("reject")

    @Test
    fun mappingTargetResolvesItsCapturedLayer() {
        val profile = profile(
            listOf(
                layer("layer-a", approve),
                layer("layer-b", reject),
            ),
        )
        val target = MappingTarget("layer-a", input.id, Gesture.Kind.TAP)

        val resolved = resolveMappingTarget(profile, target)

        assertEquals("layer-a", resolved?.layer?.id)
        assertEquals(approve, resolved?.action)
    }

    @Test
    fun mappingTargetInvalidatesWhenLayerOrInputIsRemoved() {
        val target = MappingTarget("layer-a", input.id, Gesture.Kind.TAP)

        assertNull(resolveMappingTarget(profile(listOf(layer("layer-b", reject))), target))
        assertNull(resolveMappingTarget(Profile(1, emptyList(), emptyList(), listOf(layer("layer-a", approve))), target))
    }

    @Test
    fun voiceMappingRequiresTapOnlyInput() {
        assertTrue(Binding(mapOf(Gesture.Kind.TAP to approve)).allowsVoiceMapping())
        assertFalse(
            Binding(
                mapOf(
                    Gesture.Kind.TAP to approve,
                    Gesture.Kind.HOLD to reject,
                ),
            ).allowsVoiceMapping(),
        )
    }

    @Test
    fun voiceStopKeepsTheInputThatStartedListening() {
        assertEquals("input-a", voiceStopTarget("input-a", "input-b"))
        assertEquals("input-b", voiceStopTarget(null, "input-b"))
    }

    @Test
    fun activeVoiceKeepsTheDedicatedControlAvailableAfterRemapping() {
        assertTrue(voiceControlAvailable("key_voice", mapped = false, active = true))
        assertTrue(voiceControlAvailable("key_delete", mapped = true, active = true))
        assertFalse(voiceControlAvailable("key_delete", mapped = false, active = true))
        assertTrue(voiceControlAvailable("dial_voice", mapped = false, active = true, dedicated = true))
    }

    @Test
    fun scannedInvitationRejectsEmptyPayloadAndTrimsQrWhitespace() {
        assertNull(normalizedScannedInvitation(null))
        assertNull(normalizedScannedInvitation("  \n"))
        assertEquals(
            "vibepocket://pair?code=test",
            normalizedScannedInvitation("  vibepocket://pair?code=test\n"),
        )
    }

    @Test
    fun pendingInputIsNotReportedAsInteractiveUnlessItCanStopVoice() {
        assertFalse(inputInteractive(false, false, true, false, true))
        assertFalse(inputInteractive(false, false, true, true, false))
        assertTrue(inputInteractive(true, false, true, false, false))
    }

    @Test
    fun modelSheetGuardRejectsEveryForbiddenParentState() {
        assertTrue(modelSelectionAllowed(false, false, true, true, false, true, true))
        assertFalse(modelSelectionAllowed(true, false, true, true, false, true, true))
        assertFalse(modelSelectionAllowed(false, true, true, true, false, true, true))
        assertFalse(modelSelectionAllowed(false, false, false, true, false, true, true))
        assertFalse(modelSelectionAllowed(false, false, true, false, false, true, true))
        assertFalse(modelSelectionAllowed(false, false, true, true, true, true, true))
        assertFalse(modelSelectionAllowed(false, false, true, true, false, false, true))
        assertFalse(modelSelectionAllowed(false, false, true, true, false, true, false))
    }

    @Test
    fun disconnectWarningMakesPendingOutcomeLossExplicit() {
        assertTrue(disconnectMessage(hasPendingActions = true).contains("may already have completed"))
        assertTrue(disconnectMessage(hasPendingActions = true).contains("recovery records"))
        assertFalse(disconnectMessage(hasPendingActions = false).contains("may already have completed"))
    }

    @Test
    fun connectedBluetoothHostCannotBeSelectedAgain() {
        assertFalse(bluetoothHostSelectable(registered = true, connected = true, connecting = false))
        assertFalse(bluetoothHostSelectable(registered = true, connected = false, connecting = true))
        assertFalse(bluetoothHostSelectable(registered = false, connected = false, connecting = false))
        assertTrue(bluetoothHostSelectable(registered = true, connected = false, connecting = false))
    }

    @Test
    fun paleLayerColorFallsBackToContrastingContentColor() {
        val fallback = Color.Black
        val result = contrastingColor(
            preferred = Color(0xFFF4F4F2),
            background = Color.White,
            fallback = fallback,
        )

        assertEquals(fallback, result)
        assertTrue(contrastRatio(result, Color.White) >= 4.5f)
    }

    @Test
    fun selectedLayerContrastUsesItsCompositedBackground() {
        val layerColor = Color(0xFF4A90E2)
        val background = compositedBackground(layerColor, alpha = 0.22f, background = Color.White)
        val content = contrastingColor(
            preferred = layerColor,
            background = background,
            fallback = Color.Black,
            minimumRatio = 4.5f,
        )

        assertTrue(contrastRatio(content, background) >= 4.5f)
    }

    @Test
    fun layerSemanticsSaysEachVisibleNameOnce() {
        assertEquals("Layer 1", layerSemanticsLabel(0, "Layer 1"))
        assertEquals("Layer 1: Default", layerSemanticsLabel(0, "Default"))
        assertEquals("Layer 2", layerSemanticsLabel(1, ""))
    }

    private fun profile(layers: List<Layer>) = Profile(1, listOf(input), emptyList(), layers)

    private fun layer(id: String, action: Action) = Layer(
        id = id,
        name = id,
        color = null,
        bindings = mapOf(input.id to Binding(mapOf(Gesture.Kind.TAP to action))),
    )
}
