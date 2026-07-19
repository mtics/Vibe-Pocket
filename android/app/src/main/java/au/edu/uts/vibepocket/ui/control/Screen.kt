package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.gesture.layer.GuardMillis
import au.edu.uts.vibepocket.gesture.layer.Route
import au.edu.uts.vibepocket.gesture.layer.route
import au.edu.uts.vibepocket.profile.FallbackInputs
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    onModel: () -> Unit,
    onLayer: (String) -> Boolean,
) {
    val desktop = snapshot.desktop
    val inputs = desktop?.profile?.inputs.orEmpty()
    val profileKeys = inputs.filter { it.kind == Input.Kind.KEY }
    val keys = (profileKeys + FallbackInputs.filter { fallback -> profileKeys.none { it.id == fallback.id } })
        .distinctBy(Input::id)
        .take(13)
    val touch = inputs.firstOrNull { it.kind == Input.Kind.TOUCH }
    val workflows = inputs.filter { it.kind == Input.Kind.JOYSTICK }.take(4)
    val reasoning = inputs.filter { it.kind == Input.Kind.DIAL }
    val scope = rememberCoroutineScope()
    var shift by remember { mutableStateOf(false) }
    var guarded by remember { mutableStateOf(false) }

    fun routeInput(inputId: String, gesture: Gesture.Kind): Boolean = when (
        val result = route(inputId, gesture, shift, guarded)
    ) {
        Route.Pass -> false
        Route.Suppress -> true
        is Route.Select -> {
            if (onLayer(result.layerId)) {
                guarded = true
                scope.launch {
                    delay(GuardMillis)
                    guarded = false
                }
            }
            true
        }
    }

    val dispatch: (String, Gesture.Kind) -> Unit = { inputId, gesture ->
        if (!routeInput(inputId, gesture)) onInput(inputId, gesture)
    }
    val voiceStart: (String) -> Boolean = { inputId ->
        !routeInput(inputId, Gesture.Kind.TAP) && onVoiceStart(inputId)
    }
    val repeat: (String, Boolean) -> Unit = { inputId, initial ->
        if (!initial || !routeInput(inputId, Gesture.Kind.TAP)) onNavigationRepeat(inputId, initial)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (maxHeight < 720.dp) Modifier.verticalScroll(scroll) else Modifier)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Agents(snapshot, inFlightIds, onAgent)
            Context(snapshot)
            desktop?.profile?.layers?.take(6)?.let { layers ->
                Layers(
                    layers = layers,
                    active = desktop.activeLayerId,
                    inFlightIds = inFlightIds,
                    enabled = snapshot.status.state == "ready",
                    onLayer = onLayer,
                    shift = shift,
                    onShift = { shift = it },
                )
            }
            Deck(
                inputs = keys + listOfNotNull(touch),
                snapshot = snapshot,
                hidNavigationAvailable = hidNavigationAvailable,
                inFlightIds = inFlightIds,
                onInput = dispatch,
                onNavigationRepeat = repeat,
                onVoiceStart = voiceStart,
                onVoiceStop = onVoiceStop,
                shift = shift,
                blocked = guarded,
                onLayerChord = { inputId -> routeInput(inputId, Gesture.Kind.TAP) },
            )
            Reasoning(
                inputs = reasoning,
                state = desktop?.reasoning ?: au.edu.uts.vibepocket.control.Reasoning.Unavailable,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onInput = dispatch,
                onModel = onModel,
                blocked = guarded,
            )
            Workflows(
                inputs = workflows,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onInput = dispatch,
                onVoiceStart = voiceStart,
                onVoiceStop = onVoiceStop,
                blocked = guarded,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}
