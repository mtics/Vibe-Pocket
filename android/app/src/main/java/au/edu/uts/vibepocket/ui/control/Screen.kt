package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import au.edu.uts.vibepocket.ui.control.actions.Actions
import au.edu.uts.vibepocket.ui.control.stage.Stage
import au.edu.uts.vibepocket.ui.control.state.State
import au.edu.uts.vibepocket.ui.control.state.state
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun Screen(
    snapshot: Snapshot,
    hidNavigationAvailable: Boolean,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onNavigationRepeat: (String, Boolean) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    onAgent: (String) -> Unit,
    onModel: (String) -> Boolean,
    onLayer: (String) -> Boolean,
) {
    val desktop = snapshot.desktop
    val inputs = desktop?.profile?.inputs.orEmpty()
    val keys = keyInputs(snapshot)
    val workflows = inputs.filter { it.kind == Input.Kind.JOYSTICK }.take(4)
    val reasoning = inputs.filter { it.kind == Input.Kind.DIAL }
    val mode = (inputs + keys).distinctBy(Input::id)
        .firstOrNull { snapshot.actionFor(it.id)?.type == "mode_cycle" }
    val surface = snapshot.state()

    val scroll = rememberScrollState()
    val stageMinHeight = when (surface.kind) {
        State.Kind.QUESTION -> 132.dp
        State.Kind.ERROR -> 112.dp
        State.Kind.DECISION, State.Kind.READY, State.Kind.RUNNING -> 88.dp
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Agents(snapshot, inFlightIds, onAgent)
        Stage(surface, Modifier.heightIn(min = stageMinHeight))
        Layers(
            layers = desktop?.profile?.layers.orEmpty().take(6),
            active = desktop?.activeLayerId,
            inFlightIds = inFlightIds,
            enabled = snapshot.status.state == "ready",
            onLayer = onLayer,
        )
        Actions(
            state = surface,
            inputs = keys,
            modeInput = mode,
            snapshot = snapshot,
            hidNavigationAvailable = hidNavigationAvailable,
            inFlightIds = inFlightIds,
            onInput = onInput,
            onNavigationRepeat = onNavigationRepeat,
            onVoiceStart = onVoiceStart,
            onVoiceStop = onVoiceStop,
        )
        Workflows(
            inputs = workflows,
            snapshot = snapshot,
            inFlightIds = inFlightIds,
            onInput = onInput,
            onVoiceStart = onVoiceStart,
            onVoiceStop = onVoiceStop,
            blocked = false,
        )
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Model(
                state = desktop?.model ?: au.edu.uts.vibepocket.control.Model.Unavailable,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onModel = onModel,
                blocked = false,
                modifier = Modifier.weight(1f),
            )
            Reasoning(
                inputs = reasoning,
                state = desktop?.reasoning ?: au.edu.uts.vibepocket.control.Reasoning.Unavailable,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onInput = onInput,
                blocked = false,
                modifier = Modifier.weight(1.25f),
            )
        }
    }
}
