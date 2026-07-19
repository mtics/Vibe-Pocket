package au.edu.uts.vibepocket.input

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.session.Session

internal interface Hid {
    fun send(action: Action): Boolean
    fun press(action: Action): Boolean
    fun release(action: Action): Boolean
    fun releaseAny(): Boolean
    fun repeat(action: Action): Boolean
    fun stopRepeat()
}

internal interface Bridge {
    fun activate(inputId: String, gesture: Gesture.Kind): Boolean
    fun openModel(): Boolean
    fun startVoice(inputId: String): Boolean
    fun stopVoice(inputId: String): Boolean
}

internal fun remote(session: Session): Bridge = object : Bridge {
    override fun activate(inputId: String, gesture: Gesture.Kind): Boolean =
        session.activateInput(inputId, gesture)

    override fun openModel(): Boolean = session.openModel()

    override fun startVoice(inputId: String): Boolean = session.startVoice(inputId)

    override fun stopVoice(inputId: String): Boolean = session.stopVoice(inputId)
}

internal class Dispatch(
    private val hid: Hid,
    private val bridge: Bridge,
    private val onAction: (Action) -> Unit = {},
) {
    private data class Held(
        val inputId: String,
        val action: Action,
    )

    private var held: Held? = null

    fun activate(
        snapshot: Snapshot?,
        inputId: String,
        gesture: Gesture.Kind,
    ): Boolean = when (val plan = activation(snapshot, inputId, gesture)) {
        Plan.Disabled -> false
        is Plan.Bridge -> deliver(plan, snapshot?.actionFor(inputId, gesture))
        is Plan.HidTap -> deliver(plan.action) || deliver(plan.fallback, plan.action)
        is Plan.HidHold -> false
    }

    fun openModel(snapshot: Snapshot?): Boolean {
        val desktop = snapshot?.desktop ?: return false
        if (!desktop.foreground || desktop.question != null || !snapshot.capabilities.modelPicker) return false
        return deliver(Action("model_picker")) || bridge.openModel()
    }

    fun startRepeat(snapshot: Snapshot?, inputId: String): Boolean {
        return when (val plan = activation(snapshot, inputId, Gesture.Kind.TAP)) {
            Plan.Disabled -> false
            is Plan.Bridge -> bridge.activate(plan.inputId, plan.gesture)
            is Plan.HidTap -> {
                if (plan.action.type != "navigate") return false
                hid.repeat(plan.action) || bridge.activate(plan.fallback.inputId, plan.fallback.gesture)
            }
            is Plan.HidHold -> false
        }
    }

    fun stopRepeat() = hid.stopRepeat()

    fun startVoice(snapshot: Snapshot?, inputId: String): Boolean {
        if (held != null) return false
        return when (val plan = voicePress(snapshot, inputId)) {
            Plan.Disabled -> false
            is Plan.Bridge -> bridge.startVoice(plan.inputId)
            is Plan.HidHold -> {
                if (hid.press(plan.action)) {
                    held = Held(inputId, plan.action)
                    true
                } else {
                    bridge.startVoice(plan.fallback.inputId)
                }
            }
            is Plan.HidTap -> false
        }
    }

    fun stopVoice(inputId: String): Boolean {
        val owner = held
        if (owner == null) return bridge.stopVoice(inputId)
        if (owner.inputId != inputId) return false
        held = null
        return hid.release(owner.action)
    }

    fun release() {
        held = null
        hid.releaseAny()
    }

    private fun deliver(action: Action): Boolean {
        if (!hid.send(action)) return false
        onAction(action)
        return true
    }

    private fun deliver(plan: Plan.Bridge, action: Action?): Boolean {
        if (!bridge.activate(plan.inputId, plan.gesture)) return false
        return true
    }
}
