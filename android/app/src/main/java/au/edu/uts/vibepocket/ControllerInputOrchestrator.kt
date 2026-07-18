package au.edu.uts.vibepocket

internal sealed interface ControllerInputPlan {
    data object Disabled : ControllerInputPlan

    data class Bridge(
        val inputId: String,
        val gesture: ControllerGesture,
    ) : ControllerInputPlan

    data class PreferHidTap(
        val action: ControllerAction,
        val fallback: Bridge,
    ) : ControllerInputPlan

    data class PreferHidHold(
        val action: ControllerAction,
        val fallback: Bridge,
    ) : ControllerInputPlan
}

internal object ControllerInputPlanner {
    private val hidTapActions = setOf(
        "approve",
        "reject",
        "stop",
        "mode_cycle",
        "navigate",
        "reasoning_depth",
    )

    fun activation(
        snapshot: PocketSnapshot?,
        inputId: String,
        gesture: ControllerGesture,
    ): ControllerInputPlan {
        if (snapshot == null || !snapshot.inputEnabled(inputId, gesture)) {
            return ControllerInputPlan.Disabled
        }
        val fallback = ControllerInputPlan.Bridge(inputId, gesture)
        val action = resolveAction(snapshot, inputId, gesture) ?: return fallback
        return if (prefersHidTap(snapshot, action)) {
            ControllerInputPlan.PreferHidTap(action, fallback)
        } else {
            fallback
        }
    }

    fun voicePress(snapshot: PocketSnapshot?, inputId: String): ControllerInputPlan {
        if (snapshot == null || !snapshot.voiceTapEnabled(inputId)) {
            return ControllerInputPlan.Disabled
        }
        val fallback = ControllerInputPlan.Bridge(inputId, ControllerGesture.TAP)
        val action = resolveAction(snapshot, inputId, ControllerGesture.TAP) ?: return fallback
        val controller = snapshot.controller
        return if (
            controller?.desktopFocused == true &&
            controller.userInput == null &&
            action.type == "voice" &&
            CodexHidMapping.chords(action)?.size == 1
        ) {
            ControllerInputPlan.PreferHidHold(action, fallback)
        } else {
            fallback
        }
    }

    private fun prefersHidTap(snapshot: PocketSnapshot, action: ControllerAction): Boolean {
        val controller = snapshot.controller ?: return false
        return controller.desktopFocused &&
            controller.userInput == null &&
            action.type in hidTapActions &&
            CodexHidMapping.chords(action) != null
    }

    private fun resolveAction(
        snapshot: PocketSnapshot,
        inputId: String,
        gesture: ControllerGesture,
    ): ControllerAction? = snapshot.actionFor(inputId, gesture)
        ?: if (gesture == ControllerGesture.TAP && snapshot.controller?.profile == null) {
            when (inputId) {
                "key_accept" -> ControllerAction("approve")
                "key_reject" -> ControllerAction("reject")
                "key_voice" -> ControllerAction("voice")
                "key_stop" -> ControllerAction("stop")
                "key_mode" -> ControllerAction("mode_cycle")
                "key_clear" -> ControllerAction("clear_input")
                "key_up" -> ControllerAction("navigate", direction = "up")
                "key_down" -> ControllerAction("navigate", direction = "down")
                "key_left" -> ControllerAction("navigate", direction = "left")
                "key_right" -> ControllerAction("navigate", direction = "right")
                else -> null
            }
        } else {
            null
        }
}

internal interface ControllerHidTransport {
    fun send(action: ControllerAction): Boolean
    fun pressAndHold(action: ControllerAction): Boolean
    fun releaseHeld(action: ControllerAction): Boolean
    fun releaseAnyHeld(): Boolean
    fun startNavigationRepeat(action: ControllerAction): Boolean
    fun stopNavigationRepeat()
}

internal interface ControllerBridgeTransport {
    fun activate(inputId: String, gesture: ControllerGesture): Boolean
    fun startVoice(inputId: String): Boolean
    fun stopVoice(inputId: String): Boolean
}

internal class PocketViewModelBridgeTransport(
    private val viewModel: PocketViewModel,
) : ControllerBridgeTransport {
    override fun activate(inputId: String, gesture: ControllerGesture): Boolean =
        viewModel.activateInput(inputId, gesture)

    override fun startVoice(inputId: String): Boolean = viewModel.startVoice(inputId)

    override fun stopVoice(inputId: String): Boolean = viewModel.stopVoice(inputId)
}

internal class ControllerInputOrchestrator(
    private val hid: ControllerHidTransport,
    private val bridge: ControllerBridgeTransport,
    private val onHidAction: (ControllerAction) -> Unit = {},
) {
    private data class HeldVoice(
        val inputId: String,
        val action: ControllerAction,
    )

    private var heldVoice: HeldVoice? = null

    fun activate(
        snapshot: PocketSnapshot?,
        inputId: String,
        gesture: ControllerGesture,
    ): Boolean = when (val plan = ControllerInputPlanner.activation(snapshot, inputId, gesture)) {
        ControllerInputPlan.Disabled -> false
        is ControllerInputPlan.Bridge -> bridge.activate(plan.inputId, plan.gesture)
        is ControllerInputPlan.PreferHidTap ->
            deliverHid(plan.action) || bridge.activate(plan.fallback.inputId, plan.fallback.gesture)
        is ControllerInputPlan.PreferHidHold -> false
    }

    fun openModelPicker(snapshot: PocketSnapshot?): Boolean {
        val controller = snapshot?.controller ?: return false
        if (!controller.desktopFocused || controller.userInput != null || !controller.reasoning.available) return false
        return deliverHid(ControllerAction("model_picker"))
    }

    fun startNavigationRepeat(snapshot: PocketSnapshot?, inputId: String): Boolean {
        return when (val plan = ControllerInputPlanner.activation(snapshot, inputId, ControllerGesture.TAP)) {
            ControllerInputPlan.Disabled -> false
            is ControllerInputPlan.Bridge -> bridge.activate(plan.inputId, plan.gesture)
            is ControllerInputPlan.PreferHidTap -> {
                if (plan.action.type != "navigate") return false
                hid.startNavigationRepeat(plan.action) ||
                    bridge.activate(plan.fallback.inputId, plan.fallback.gesture)
            }
            is ControllerInputPlan.PreferHidHold -> false
        }
    }

    fun stopNavigationRepeat() {
        hid.stopNavigationRepeat()
    }

    fun startVoice(snapshot: PocketSnapshot?, inputId: String): Boolean {
        if (heldVoice != null) return false
        return when (val plan = ControllerInputPlanner.voicePress(snapshot, inputId)) {
            ControllerInputPlan.Disabled -> false
            is ControllerInputPlan.Bridge -> bridge.startVoice(plan.inputId)
            is ControllerInputPlan.PreferHidHold -> {
                if (hid.pressAndHold(plan.action)) {
                    heldVoice = HeldVoice(inputId, plan.action)
                    true
                } else {
                    bridge.startVoice(plan.fallback.inputId)
                }
            }
            is ControllerInputPlan.PreferHidTap -> false
        }
    }

    fun stopVoice(inputId: String): Boolean {
        val owner = heldVoice
        if (owner == null) return bridge.stopVoice(inputId)
        if (owner.inputId != inputId) return false
        heldVoice = null
        return hid.releaseHeld(owner.action)
    }

    fun releaseHeldInput() {
        heldVoice = null
        hid.releaseAnyHeld()
    }

    private fun deliverHid(action: ControllerAction): Boolean {
        if (!hid.send(action)) return false
        onHidAction(action)
        return true
    }
}
