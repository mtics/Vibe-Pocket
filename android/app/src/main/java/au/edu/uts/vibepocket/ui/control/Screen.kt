package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Model
import au.edu.uts.vibepocket.control.Reasoning
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.ui.control.actions.Actions
import au.edu.uts.vibepocket.ui.control.stage.Stage
import au.edu.uts.vibepocket.ui.control.state.state
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun Screen(
    snapshot: Snapshot,
    hidNavigationAvailable: Boolean,
    inFlightIds: Set<String>,
    contextTransitionPending: Boolean,
    onInput: (String, Gesture.Kind) -> Unit,
    onNavigationRepeat: (String, Boolean) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    onAgent: (String) -> Unit,
    onModel: (String) -> Boolean,
    onMode: (String) -> Boolean,
    onReasoning: (Reasoning.Level) -> Boolean,
    onLayer: (String) -> Boolean,
) {
    val desktop = snapshot.desktop
    val catalog = Catalog.from(snapshot)
    val blocked = contextTransitionPending || !snapshot.transportFresh
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Agents(snapshot, inFlightIds, blocked, onAgent, Modifier.height(52.dp))
            Stage(snapshot.state(), Modifier.height(48.dp))
            Layers(
                layers = desktop?.profile?.layers.orEmpty().take(6),
                active = desktop?.activeLayerId,
                inFlightIds = inFlightIds,
                enabled = snapshot.status.state == "ready" && snapshot.transportFresh && !blocked,
                onLayer = onLayer,
                modifier = Modifier.height(52.dp),
            )
            Workflows(
                catalog = catalog,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onInput = onInput,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                blocked = blocked,
                modifier = Modifier.height(64.dp),
            )
            Actions(
                catalog = catalog,
                mode = desktop?.mode ?: au.edu.uts.vibepocket.control.Selector(false, ""),
                snapshot = snapshot,
                hidNavigationAvailable = hidNavigationAvailable,
                inFlightIds = inFlightIds,
                onInput = onInput,
                onNavigationRepeat = onNavigationRepeat,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                onMode = onMode,
                blocked = blocked,
            )
            Row(Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Model(
                    state = desktop?.model ?: Model.Unavailable,
                    snapshot = snapshot,
                    inFlightIds = inFlightIds,
                    onModel = onModel,
                    blocked = blocked,
                    modifier = Modifier.weight(1f),
                )
                Reasoning(
                    state = desktop?.reasoning ?: Reasoning.Unavailable,
                    snapshot = snapshot,
                    inFlightIds = inFlightIds,
                    onReasoning = onReasoning,
                    blocked = blocked,
                    modifier = Modifier.weight(1.18f),
                )
            }
        }
    }
}
