package au.edu.uts.vibepocket.input

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Gesture
import java.util.concurrent.atomic.AtomicLong

enum class HidResult {
    DELIVERED,
    NOT_DISPATCHED,
    INDETERMINATE,
    CANCELLED,
    TIMED_OUT,
    ;

    val fallbackSafe: Boolean
        get() = this == NOT_DISPATCHED || this == CANCELLED

    val consumed: Boolean
        get() = this == DELIVERED || this == INDETERMINATE || this == TIMED_OUT
}

internal interface Hid {
    fun send(action: Action, completion: (HidResult) -> Unit): Boolean
    fun press(action: Action): HidResult
    fun release(action: Action): HidResult
    fun releaseAny(): HidResult
    fun repeat(action: Action, completion: (HidResult) -> Unit): Boolean
    fun stopRepeat()
}

internal interface Bridge {
    fun activate(inputId: String, gesture: Gesture.Kind): Boolean
    fun openModel(): Boolean
    fun startVoice(inputId: String): Boolean
    fun stopVoice(inputId: String): Boolean
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
    private val generation = AtomicLong(0)

    fun activate(
        snapshot: Snapshot?,
        inputId: String,
        gesture: Gesture.Kind,
    ): Boolean = when (val plan = activation(snapshot, inputId, gesture)) {
        Plan.Disabled -> false
        is Plan.Bridge -> deliver(plan)
        is Plan.HidTap -> deliver(plan.action) { deliver(plan.fallback) }
        is Plan.HidHold -> false
    }

    fun openModel(snapshot: Snapshot?): Boolean {
        val desktop = snapshot?.desktop ?: return false
        if (!desktop.foreground || desktop.question != null || !snapshot.capabilities.modelPicker) return false
        if (!snapshot.transportFresh) return bridge.openModel()
        if (held != null) return bridge.openModel()
        return deliver(Action("model_picker"), bridge::openModel)
    }

    fun startRepeat(snapshot: Snapshot?, inputId: String): Boolean {
        return when (val plan = activation(snapshot, inputId, Gesture.Kind.TAP)) {
            Plan.Disabled -> false
            is Plan.Bridge -> bridge.activate(plan.inputId, plan.gesture)
            is Plan.HidTap -> {
                if (plan.action.type != "navigate") return false
                if (held != null) return deliver(plan.fallback)
                val requestGeneration = generation.get()
                hid.repeat(plan.action) { result ->
                    if (result.fallbackSafe && generation.get() == requestGeneration) deliver(plan.fallback)
                } || deliver(plan.fallback)
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
                when (hid.press(plan.action)) {
                    HidResult.DELIVERED,
                    HidResult.INDETERMINATE,
                    HidResult.TIMED_OUT -> {
                        held = Held(inputId, plan.action)
                        true
                    }
                    HidResult.NOT_DISPATCHED,
                    HidResult.CANCELLED -> bridge.startVoice(plan.fallback.inputId)
                }
            }
            is Plan.HidTap -> false
        }
    }

    fun stopVoice(inputId: String): Boolean {
        val owner = held
        if (owner == null) return bridge.stopVoice(inputId)
        held = null
        val released = hid.release(owner.action)
        val stopped = bridge.stopVoice(owner.inputId)
        return released.consumed || stopped
    }

    fun release() {
        generation.incrementAndGet()
        hid.stopRepeat()
        held?.also { owner ->
            held = null
            hid.release(owner.action)
            bridge.stopVoice(owner.inputId)
        }
        hid.releaseAny()
    }

    private fun deliver(action: Action, fallback: () -> Boolean): Boolean {
        if (held != null) return fallback()
        val requestGeneration = generation.get()
        return hid.send(action) { result ->
            if (generation.get() != requestGeneration) return@send
            when {
                result == HidResult.DELIVERED -> onAction(action)
                result.fallbackSafe -> fallback()
            }
        } || fallback()
    }

    private fun deliver(plan: Plan.Bridge): Boolean = bridge.activate(plan.inputId, plan.gesture)
}
