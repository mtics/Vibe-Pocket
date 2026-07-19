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

    override fun focusAgent(agentId: String): Boolean = session.focusAgent(agentId)

    override fun selectModel(modelId: String): Boolean = session.selectModel(modelId)

    override fun selectLayer(layerId: String): Boolean = session.selectLayer(layerId)
}
