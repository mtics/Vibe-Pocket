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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val layout = Layout.of(maxHeight)
        Column(
            Modifier.widthIn(max = layout.maxWidth).fillMaxWidth().fillMaxHeight()
                .padding(horizontal = layout.horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(layout.gap),
        ) {
            Column(Modifier.fillMaxWidth().height(layout.context)) {
                Row(
                    Modifier.fillMaxWidth().height(layout.agents),
                    horizontalArrangement = Arrangement.spacedBy(layout.gap),
                ) {
                    Agents(snapshot, inFlightIds, blocked, onAgent, Modifier.weight(1f).fillMaxHeight())
                    RailAction(
                        control = catalog.find("focus_next"),
                        snapshot = snapshot,
                        inFlightIds = inFlightIds,
                        onInput = onInput,
                        onVoiceStart = onVoiceStart,
                        onVoiceStop = onVoiceStop,
                        blocked = blocked,
                        modifier = Modifier.width(layout.agentAction).fillMaxHeight(),
                    )
                }
                Spacer(Modifier.height(layout.contextGap))
                Row(
                    Modifier.fillMaxWidth().height(layout.status),
                    horizontalArrangement = Arrangement.spacedBy(layout.gap),
                ) {
                    Stage(snapshot.state(), Modifier.weight(1f).fillMaxHeight())
                    RailAction(
                        control = catalog.find("attach"),
                        snapshot = snapshot,
                        inFlightIds = inFlightIds,
                        onInput = onInput,
                        onVoiceStart = onVoiceStart,
                        onVoiceStop = onVoiceStop,
                        blocked = blocked,
                        modifier = Modifier.width(layout.focusAction).fillMaxHeight(),
                    )
                }
            }
            Layers(
                layers = desktop?.profile?.layers.orEmpty().take(6),
                active = desktop?.activeLayerId,
                inFlightIds = inFlightIds,
                enabled = snapshot.status.state == "ready" && snapshot.transportFresh && !blocked,
                onLayer = onLayer,
                modifier = Modifier.height(layout.layers),
            )
            Workflows(
                catalog = catalog,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onInput = onInput,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                blocked = blocked,
                modifier = Modifier.height(layout.workflows),
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
                layout = layout,
            )
            Row(
                Modifier.fillMaxWidth().height(layout.selectors),
                horizontalArrangement = Arrangement.spacedBy(layout.gap),
            ) {
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

@Composable
private fun RailAction(
    control: Control?,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    blocked: Boolean,
    modifier: Modifier,
) {
    if (control == null) {
        Box(
            modifier.clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .alpha(0.52f),
        )
        return
    }
    InputButton(
        input = control.input,
        gesture = control.gesture,
        snapshot = snapshot,
        inFlightIds = inFlightIds,
        onInput = onInput,
        onVoiceStart = onVoiceStart,
        onVoiceStop = onVoiceStop,
        blocked = blocked,
        labelPlacement = LabelPlacement.HIDDEN,
        modifier = modifier,
    )
}
