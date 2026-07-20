package au.edu.uts.vibepocket.input

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.ConflictGroup
import au.edu.uts.vibepocket.control.conflictGroups
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.allowsQueuedRepeat

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
    fun quiesce(): Boolean = true
}

internal interface Bridge {
    fun activate(inputId: String, gesture: Gesture.Kind): Boolean
    fun openModel(): Boolean
    fun startVoice(inputId: String): Boolean
    fun stopVoice(inputId: String): Boolean
    fun contextTransitionPending(): Boolean = false
    fun focusAgent(agentId: String): Boolean = false
    fun selectModel(modelId: String): Boolean = false
    fun selectMode(modeId: String): Boolean = false
    fun selectReasoning(level: au.edu.uts.vibepocket.control.Reasoning.Level): Boolean = false
    fun selectLayer(layerId: String): Boolean = false
    fun reportLocalDeliveryFailure(message: String)
    fun refresh()
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

    private data class HidIdentity(
        val inputId: String,
        val gesture: Gesture.Kind?,
        val action: Action,
    )

    private data class PendingHid(
        val identity: HidIdentity,
        val requestId: Long,
        val generation: Long,
    )

    private var held: Held? = null
    private val deliveryLock = Any()
    private val pendingHid = mutableMapOf<HidIdentity, Long>()
    private var requestSequence = 0L
    private var generation = 0L

    fun activate(
        snapshot: Snapshot?,
        inputId: String,
        gesture: Gesture.Kind,
    ): Boolean {
        val plan = activation(snapshot, inputId, gesture)
        val contextBlocked = bridge.contextTransitionPending() && (
            ConflictGroup.CONTEXT in snapshot?.actionFor(inputId, gesture)?.conflictGroups().orEmpty() ||
                (plan as? Plan.Bridge)?.transition != null ||
                (plan as? Plan.HidTap)?.fallback?.transition != null
            )
        if (contextBlocked) return false
        if (plan is Plan.Bridge && plan.transition != null && snapshot?.desktop?.voice?.active == true) return false
        return when (plan) {
        Plan.Disabled -> false
        is Plan.Bridge -> deliver(plan)
        is Plan.HidTap -> deliver(
            identity = HidIdentity(inputId, gesture, plan.action),
            action = plan.action,
        ) { deliver(plan.fallback) }
        is Plan.HidHold -> false
        }
    }

    fun openModel(snapshot: Snapshot?): Boolean {
        if (bridge.contextTransitionPending()) return false
        val desktop = snapshot?.desktop ?: return false
        if (!desktop.foreground || desktop.question != null || !snapshot.capabilities.modelPicker) return false
        if (!snapshot.transportFresh) return bridge.openModel()
        if (held != null) return bridge.openModel()
        val action = Action("model_picker")
        return deliver(
            identity = HidIdentity("model-picker", null, action),
            action = action,
            fallback = bridge::openModel,
        )
    }

    fun startRepeat(snapshot: Snapshot?, inputId: String): Boolean {
        return when (val plan = activation(snapshot, inputId, Gesture.Kind.TAP)) {
            Plan.Disabled -> false
            is Plan.Bridge -> deliver(plan)
            is Plan.HidTap -> {
                if (plan.action.type != "navigate") return false
                if (held != null) return deliver(plan.fallback)
                val requestGeneration = currentGeneration()
                hid.repeat(plan.action) { result ->
                    if (result.fallbackSafe && currentGeneration() == requestGeneration) deliver(plan.fallback)
                } || deliver(plan.fallback)
            }
            is Plan.HidHold -> false
        }
    }

    fun stopRepeat() = hid.stopRepeat()

    fun startVoice(snapshot: Snapshot?, inputId: String): Boolean {
        if (bridge.contextTransitionPending()) return false
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
        invalidateHidDeliveries()
        hid.stopRepeat()
        held?.also { owner ->
            held = null
            hid.release(owner.action)
            bridge.stopVoice(owner.inputId)
        }
        hid.releaseAny()
    }

    fun focusAgent(snapshot: Snapshot?, agentId: String): Boolean {
        if (snapshot?.agentFocusEnabled(agentId) != true || snapshot.desktop?.voice?.active == true) return false
        return deliverTransition { bridge.focusAgent(agentId) }
    }

    fun selectModel(snapshot: Snapshot?, modelId: String): Boolean {
        val desktop = snapshot?.desktop ?: return false
        val model = desktop.model
        if (!snapshot.transportFresh || !desktop.foreground || desktop.question != null) return false
        if (!snapshot.capabilities.model || !model.available || model.id == modelId) return false
        if (model.options.none { it.id == modelId } || desktop.voice?.active == true) return false
        return deliverTransition { bridge.selectModel(modelId) }
    }

    fun selectMode(snapshot: Snapshot?, modeId: String): Boolean {
        val desktop = snapshot?.desktop ?: return false
        val mode = desktop.mode
        if (!snapshot.transportFresh || !desktop.foreground || desktop.question != null) return false
        if (!snapshot.capabilities.modeCycle || !mode.available || mode.id == modeId) return false
        if (mode.options.none { it.id == modeId } || desktop.voice?.active == true) return false
        return deliverTransition { bridge.selectMode(modeId) }
    }

    fun selectReasoning(
        snapshot: Snapshot?,
        level: au.edu.uts.vibepocket.control.Reasoning.Level,
    ): Boolean {
        val desktop = snapshot?.desktop ?: return false
        val reasoning = desktop.reasoning
        if (!snapshot.transportFresh || !desktop.foreground || desktop.question != null) return false
        if (!snapshot.capabilities.reasoning || !reasoning.available || reasoning.level == level) return false
        if (reasoning.options.none { it == level } || desktop.voice?.active == true) return false
        return deliverTransition { bridge.selectReasoning(level) }
    }

    fun selectLayer(snapshot: Snapshot?, layerId: String): Boolean {
        val desktop = snapshot?.desktop ?: return false
        if (desktop.profile?.layers?.none { it.id == layerId } != false) return false
        if (desktop.activeLayerId == layerId || desktop.voice?.active == true) return false
        return deliverTransition { bridge.selectLayer(layerId) }
    }

    private fun deliver(
        identity: HidIdentity,
        action: Action,
        fallback: () -> Boolean,
    ): Boolean {
        if (held != null) return fallback()
        if (action.allowsQueuedRepeat()) return deliverQueued(action, fallback)
        val pending = acquireHid(identity) ?: return false
        val accepted = hid.send(action) { result ->
            if (!ownsHid(pending)) return@send
            try {
                completeHid(result, action, fallback)
            } finally {
                releaseHid(pending)
            }
        }
        if (accepted) return true
        if (!releaseHid(pending)) return true
        return fallbackOrRecover(HidResult.NOT_DISPATCHED, fallback)
    }

    private fun deliverQueued(action: Action, fallback: () -> Boolean): Boolean {
        val requestGeneration = currentGeneration()
        return hid.send(action) { result ->
            if (currentGeneration() != requestGeneration) return@send
            completeHid(result, action, fallback)
        } || fallbackOrRecover(HidResult.NOT_DISPATCHED, fallback)
    }

    private fun deliver(plan: Plan.Bridge): Boolean {
        return if (plan.transition == null) {
            bridge.activate(plan.inputId, plan.gesture)
        } else {
            deliverTransition { bridge.activate(plan.inputId, plan.gesture) }
        }
    }

    private fun deliverTransition(deliver: () -> Boolean): Boolean {
        if (held != null || bridge.contextTransitionPending()) return false
        invalidateHidDeliveries()
        hid.stopRepeat()
        if (!hid.quiesce()) return false
        if (held != null || bridge.contextTransitionPending()) return false
        return deliver()
    }

    private fun acquireHid(identity: HidIdentity): PendingHid? = synchronized(deliveryLock) {
        if (pendingHid.containsKey(identity)) return@synchronized null
        PendingHid(identity, ++requestSequence, generation).also {
            pendingHid[identity] = it.requestId
        }
    }

    private fun ownsHid(pending: PendingHid): Boolean = synchronized(deliveryLock) {
        pending.generation == generation && pendingHid[pending.identity] == pending.requestId
    }

    private fun releaseHid(pending: PendingHid): Boolean = synchronized(deliveryLock) {
        if (pendingHid[pending.identity] != pending.requestId) return@synchronized false
        pendingHid.remove(pending.identity)
        true
    }

    private fun currentGeneration(): Long = synchronized(deliveryLock) { generation }

    private fun invalidateHidDeliveries() = synchronized(deliveryLock) {
        generation += 1
        pendingHid.clear()
    }

    private fun fallbackOrRecover(result: HidResult, fallback: () -> Boolean): Boolean {
        if (fallback()) return true
        recover(result)
        return false
    }

    private fun completeHid(result: HidResult, action: Action, fallback: () -> Boolean) {
        when {
            result == HidResult.DELIVERED -> onAction(action)
            result.fallbackSafe -> fallbackOrRecover(result, fallback)
            else -> recover(result)
        }
    }

    private fun recover(result: HidResult) {
        val message = when (result) {
            HidResult.NOT_DISPATCHED,
            HidResult.CANCELLED -> HID_DELIVERY_FAILED
            HidResult.INDETERMINATE,
            HidResult.TIMED_OUT -> HID_DELIVERY_INDETERMINATE
            HidResult.DELIVERED -> return
        }
        bridge.reportLocalDeliveryFailure(message)
        bridge.refresh()
    }

    private companion object {
        const val HID_DELIVERY_FAILED = "Bluetooth delivery failed."
        const val HID_DELIVERY_INDETERMINATE =
            "The Bluetooth action may have completed, but its outcome could not be confirmed."
    }
}
