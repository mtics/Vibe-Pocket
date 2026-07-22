package au.edu.uts.vibepocket.input

import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.session.Session

internal fun remote(session: Session): Bridge = object : Bridge {
    override fun activate(inputId: String, gesture: Gesture.Kind): Boolean =
        session.activateInput(inputId, gesture)

    override fun openModel(): Boolean = session.openModel()

    override fun startVoice(inputId: String): Boolean = session.startVoice(inputId)

    override fun stopVoice(inputId: String): Boolean = session.stopVoice(inputId)

    override fun contextTransitionPending(): Boolean = session.contextTransitionPending()

    override fun selectAgent(agentId: String): Boolean = session.selectAgent(agentId)

    override fun selectModel(modelId: String): Boolean = session.selectModel(modelId)

    override fun selectMode(modeId: String): Boolean = session.selectMode(modeId)

    override fun selectReasoning(level: au.edu.uts.vibepocket.control.Reasoning.Level): Boolean =
        session.selectReasoning(level)

    override fun selectLayer(layerId: String): Boolean = session.selectLayer(layerId)

    override fun reportLocalDeliveryFailure(message: String) = session.reportLocalDeliveryFailure(message)

    override fun reportLocalDeliveryIndeterminate(message: String) =
        session.reportLocalDeliveryIndeterminate(message)

    override fun observeSetting() = session.observeSetting()

    override fun refresh() {
        session.refresh()
    }
}
