package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import au.edu.uts.vibepocket.ui.control.actions.Actions
import au.edu.uts.vibepocket.ui.control.stage.Stage
import au.edu.uts.vibepocket.ui.control.state.State
import au.edu.uts.vibepocket.ui.control.state.state
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
    val mode = (inputs + keys).distinctBy(Input::id)
        .firstOrNull { snapshot.actionFor(it.id)?.type == "mode_cycle" }
    val surface = snapshot.state()
    val blocked = contextTransitionPending(inFlightIds)
    val actionInputCandidates = when (surface.kind) {
        State.Kind.ERROR -> setOf("key_attach", "key_new_task")
        State.Kind.READY, State.Kind.QUESTION, State.Kind.DECISION, State.Kind.RUNNING -> setOf(
            "key_up",
            "key_down",
            "key_left",
            "key_right",
            "key_clear",
            "key_new_task",
            "key_accept",
            "key_reject",
            "key_stop",
        )
    }
    val actionInputIds = actionInputCandidates.intersect(keys.mapTo(mutableSetOf(), Input::id)) +
        listOfNotNull(mode?.id)
    val workflows = inputs.filter { input ->
        input.id !in actionInputIds && snapshot.actionFor(input.id)?.type == "workflow"
    }
    val reasoning = inputs.filter { input ->
        input.id !in actionInputIds && snapshot.actionFor(input.id)?.type == "reasoning_depth"
    }
    val representedIds = actionInputIds + workflows.map(Input::id) + reasoning.map(Input::id) +
        listOfNotNull(dedicatedVoiceInput(snapshot)?.id)
    val additionalInputs = unrepresentedInputs(inputs, representedIds)

    val scroll = rememberScrollState()
    val stageMinHeight = when (surface.kind) {
        State.Kind.QUESTION -> 132.dp
        State.Kind.ERROR -> 112.dp
        State.Kind.DECISION, State.Kind.READY, State.Kind.RUNNING -> 88.dp
    }
    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 12.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Agents(snapshot, inFlightIds, blocked, onAgent)
            Stage(surface, Modifier.heightIn(min = stageMinHeight))
            Layers(
                layers = desktop?.profile?.layers.orEmpty().take(6),
                active = desktop?.activeLayerId,
                inFlightIds = inFlightIds,
                enabled = snapshot.status.state == "ready" && !blocked,
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
                blocked = blocked,
            )
            Workflows(
                inputs = workflows,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onInput = onInput,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                blocked = blocked,
            )
            Workflows(
                inputs = additionalInputs,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onInput = onInput,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                blocked = blocked,
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
                    blocked = blocked,
                    modifier = Modifier.weight(1f),
                )
                Reasoning(
                    inputs = reasoning,
                    state = desktop?.reasoning ?: au.edu.uts.vibepocket.control.Reasoning.Unavailable,
                    snapshot = snapshot,
                    inFlightIds = inFlightIds,
                    onInput = onInput,
                    blocked = blocked,
                    modifier = Modifier.weight(1.25f),
                )
            }
        }
    }
}
