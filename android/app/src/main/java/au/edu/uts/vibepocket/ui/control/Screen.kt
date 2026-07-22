package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Model
import au.edu.uts.vibepocket.control.Reasoning
import au.edu.uts.vibepocket.control.Selector
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.ConflictGroup
import au.edu.uts.vibepocket.session.Operation
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import au.edu.uts.vibepocket.ui.control.actions.Actions
import au.edu.uts.vibepocket.ui.control.actions.LandscapeActions
import au.edu.uts.vibepocket.ui.control.stage.Stage
import au.edu.uts.vibepocket.ui.control.state.state
import au.edu.uts.vibepocket.ui.preference.Hand
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private data class Events(
    val input: (String, Gesture.Kind) -> Unit,
    val repeat: (String, Boolean) -> Unit,
    val voiceStart: (String) -> Boolean,
    val voiceStop: (String) -> Unit,
    val agent: (String) -> Unit,
    val model: (String) -> Boolean,
    val mode: (String) -> Boolean,
    val reasoning: (Reasoning.Level) -> Boolean,
    val layer: (String) -> Boolean,
)

@Composable
internal fun Screen(
    snapshot: Snapshot,
    landscape: Boolean,
    hidNavigationAvailable: Boolean,
    inFlightIds: Set<String>,
    busyGroups: Set<ConflictGroup>,
    operation: Operation?,
    modelTarget: String?,
    reasoningTarget: Reasoning.Level?,
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
    voiceInput: Input,
    onSettings: () -> Unit,
    hand: Hand,
) {
    val catalog = Catalog.from(snapshot)
    val activeGroups = if (contextTransitionPending) {
        busyGroups + ConflictGroup.CONTEXT
    } else {
        busyGroups
    }
    val blocked = !snapshot.transportFresh
    val events = Events(
        input = onInput,
        repeat = onNavigationRepeat,
        voiceStart = onVoiceStart,
        voiceStop = onVoiceStop,
        agent = onAgent,
        model = onModel,
        mode = onMode,
        reasoning = onReasoning,
        layer = onLayer,
    )
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (landscape) {
            Landscape(
                snapshot, catalog, inFlightIds, activeGroups, operation, modelTarget, reasoningTarget, hidNavigationAvailable, blocked,
                voiceInput, events, Layout.landscape(maxWidth, maxHeight), onSettings, hand,
            )
        } else {
            val layout = Layout.of(maxHeight)
            if (maxHeight < layout.content) {
                Short(snapshot, catalog, inFlightIds, activeGroups, operation, modelTarget, reasoningTarget, hidNavigationAvailable, blocked, events, layout, hand)
            } else {
                Portrait(snapshot, catalog, inFlightIds, activeGroups, operation, modelTarget, reasoningTarget, hidNavigationAvailable, blocked, events, layout, hand)
            }
        }
    }
}

@Composable
private fun Portrait(
    snapshot: Snapshot,
    catalog: Catalog,
    inFlightIds: Set<String>,
    busyGroups: Set<ConflictGroup>,
    operation: Operation?,
    modelTarget: String?,
    reasoningTarget: Reasoning.Level?,
    hidNavigationAvailable: Boolean,
    blocked: Boolean,
    events: Events,
    layout: Layout,
    hand: Hand,
) {
    Column(
        board(layout),
        verticalArrangement = Arrangement.spacedBy(layout.gap),
    ) {
        Context(snapshot, catalog, inFlightIds, operation, blocked || ConflictGroup.CONTEXT in busyGroups, events, layout)
        LayersRow(snapshot, inFlightIds, blocked || ConflictGroup.CONTEXT in busyGroups, events, layout)
        WorkflowsRow(snapshot, catalog, inFlightIds, blocked || ConflictGroup.DRAFT in busyGroups, events, layout)
        ActionsRow(snapshot, catalog, inFlightIds, busyGroups, hidNavigationAvailable, blocked, events, layout, hand)
        Selectors(
            snapshot, inFlightIds, modelTarget, reasoningTarget,
            ConflictGroup.CONTEXT in busyGroups,
            events, layout,
        )
    }
}

@Composable
private fun Short(
    snapshot: Snapshot,
    catalog: Catalog,
    inFlightIds: Set<String>,
    busyGroups: Set<ConflictGroup>,
    operation: Operation?,
    modelTarget: String?,
    reasoningTarget: Reasoning.Level?,
    hidNavigationAvailable: Boolean,
    blocked: Boolean,
    events: Events,
    layout: Layout,
    hand: Hand,
) {
    Column(board(layout)) {
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(layout.gap),
        ) {
            Context(snapshot, catalog, inFlightIds, operation, blocked || ConflictGroup.CONTEXT in busyGroups, events, layout)
            LayersRow(snapshot, inFlightIds, blocked || ConflictGroup.CONTEXT in busyGroups, events, layout)
            WorkflowsRow(snapshot, catalog, inFlightIds, blocked || ConflictGroup.DRAFT in busyGroups, events, layout)
        }
        Spacer(Modifier.height(layout.gap))
        ActionsRow(snapshot, catalog, inFlightIds, busyGroups, hidNavigationAvailable, blocked, events, layout, hand)
        Spacer(Modifier.height(layout.gap))
        Selectors(
            snapshot, inFlightIds, modelTarget, reasoningTarget,
            ConflictGroup.CONTEXT in busyGroups,
            events, layout,
        )
    }
}

@Composable
private fun Landscape(
    snapshot: Snapshot,
    catalog: Catalog,
    inFlightIds: Set<String>,
    busyGroups: Set<ConflictGroup>,
    operation: Operation?,
    modelTarget: String?,
    reasoningTarget: Reasoning.Level?,
    hidNavigationAvailable: Boolean,
    blocked: Boolean,
    voiceInput: Input,
    events: Events,
    layout: Layout,
    onSettings: () -> Unit,
    hand: Hand,
) {
    Row(
        Modifier.widthIn(max = layout.maxWidth).fillMaxWidth().fillMaxHeight()
            .padding(horizontal = layout.horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(layout.gap),
        ) {
            Context(snapshot, catalog, inFlightIds, operation, blocked || ConflictGroup.CONTEXT in busyGroups, events, layout, onSettings)
            LayersRow(snapshot, inFlightIds, blocked || ConflictGroup.CONTEXT in busyGroups, events, layout)
            WorkflowsRow(snapshot, catalog, inFlightIds, blocked || ConflictGroup.DRAFT in busyGroups, events, layout)
            Selectors(
                snapshot, inFlightIds, modelTarget, reasoningTarget,
                ConflictGroup.CONTEXT in busyGroups,
                events, layout,
            )
        }
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(layout.gap),
        ) {
            LandscapeActions(
                catalog = catalog,
                mode = snapshot.desktop?.mode ?: Selector(false, ""),
                snapshot = snapshot,
                hidNavigationAvailable = hidNavigationAvailable,
                inFlightIds = inFlightIds,
                busyGroups = busyGroups,
                onInput = events.input,
                onNavigationRepeat = events.repeat,
                onVoiceStart = events.voiceStart,
                onVoiceStop = events.voiceStop,
                onMode = events.mode,
                blocked = blocked,
                layout = layout,
                hand = hand,
            )
            Voice(
                input = voiceInput,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onInput = events.input,
                onVoiceStart = events.voiceStart,
                onVoiceStop = events.voiceStop,
                blocked = blocked || ConflictGroup.CONTEXT in busyGroups ||
                    ConflictGroup.VOICE in busyGroups,
                height = layout.voice,
            )
        }
    }
}

@Composable
private fun Context(
    snapshot: Snapshot,
    catalog: Catalog,
    inFlightIds: Set<String>,
    operation: Operation?,
    blocked: Boolean,
    events: Events,
    layout: Layout,
    onSettings: (() -> Unit)? = null,
) {
    val nextFocus = remember { FocusRequester() }
    val focusNext = catalog.find("focus_next")
    Column(Modifier.fillMaxWidth().height(layout.context)) {
        Row(
            Modifier.fillMaxWidth().height(layout.agents),
            horizontalArrangement = Arrangement.spacedBy(layout.gap),
        ) {
            Agents(
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                blocked = blocked,
                onAgent = events.agent,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onSkip = { nextFocus.requestFocus() },
            )
            RailAction(
                focusNext, snapshot, inFlightIds, events, blocked,
                Modifier.width(layout.agentAction).fillMaxHeight(),
            )
        }
        Spacer(Modifier.height(layout.contextGap))
        Row(
            Modifier.fillMaxWidth().height(layout.status),
            horizontalArrangement = Arrangement.spacedBy(layout.gap),
        ) {
            Stage(
                snapshot.state(operation),
                Modifier.weight(1f).fillMaxHeight().focusRequester(nextFocus).focusable(),
            )
            RailAction(
                catalog.find("attach"), snapshot, inFlightIds, events, blocked,
                Modifier.width(layout.focusAction).fillMaxHeight(),
            )
            onSettings?.let {
                SettingsAction(
                    onClick = it,
                    modifier = Modifier.width(layout.focusAction).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun SettingsAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    IconButton(
        onClick = onClick,
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.42f), shape)
            .semantics { contentDescription = "Open settings" },
    ) {
        Icon(Icons.Filled.Settings, contentDescription = null)
    }
}

@Composable
private fun LayersRow(
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    blocked: Boolean,
    events: Events,
    layout: Layout,
) {
    val desktop = snapshot.desktop
    Layers(
        layers = desktop?.profile?.layers.orEmpty().take(6),
        active = desktop?.activeLayerId,
        inFlightIds = inFlightIds,
        enabled = snapshot.status.state == "ready" && snapshot.transportFresh && !blocked,
        onLayer = events.layer,
        modifier = Modifier.height(layout.layers),
    )
}

@Composable
private fun WorkflowsRow(
    snapshot: Snapshot,
    catalog: Catalog,
    inFlightIds: Set<String>,
    blocked: Boolean,
    events: Events,
    layout: Layout,
) {
    Workflows(
        catalog = catalog,
        snapshot = snapshot,
        inFlightIds = inFlightIds,
        onInput = events.input,
        onVoiceStart = events.voiceStart,
        onVoiceStop = events.voiceStop,
        blocked = blocked,
        modifier = Modifier.height(layout.workflows),
    )
}

@Composable
private fun ActionsRow(
    snapshot: Snapshot,
    catalog: Catalog,
    inFlightIds: Set<String>,
    busyGroups: Set<ConflictGroup>,
    hidNavigationAvailable: Boolean,
    blocked: Boolean,
    events: Events,
    layout: Layout,
    hand: Hand,
) {
    Actions(
        catalog = catalog,
        mode = snapshot.desktop?.mode ?: Selector(false, ""),
        snapshot = snapshot,
        hidNavigationAvailable = hidNavigationAvailable,
        inFlightIds = inFlightIds,
        busyGroups = busyGroups,
        onInput = events.input,
        onNavigationRepeat = events.repeat,
        onVoiceStart = events.voiceStart,
        onVoiceStop = events.voiceStop,
        onMode = events.mode,
        blocked = blocked,
        layout = layout,
        hand = hand,
    )
}

@Composable
private fun Selectors(
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    modelTarget: String?,
    reasoningTarget: Reasoning.Level?,
    blocked: Boolean,
    events: Events,
    layout: Layout,
) {
    val settingsBlocked = blocked || modelTarget != null || reasoningTarget != null
    Row(
        Modifier.fillMaxWidth().height(layout.selectors),
        horizontalArrangement = Arrangement.spacedBy(layout.gap),
    ) {
        Model(
            state = snapshot.desktop?.model ?: Model.Unavailable,
            snapshot = snapshot,
            inFlightIds = inFlightIds,
            targetId = modelTarget,
            onModel = events.model,
            blocked = settingsBlocked,
            modifier = Modifier.weight(1f),
        )
        Reasoning(
            state = snapshot.desktop?.reasoning ?: Reasoning.Unavailable,
            snapshot = snapshot,
            inFlightIds = inFlightIds,
            target = reasoningTarget,
            onReasoning = events.reasoning,
            blocked = settingsBlocked,
            modifier = Modifier.weight(1.18f),
        )
    }
}

private fun board(layout: Layout): Modifier = Modifier
    .widthIn(max = layout.maxWidth)
    .fillMaxWidth()
    .fillMaxHeight()
    .padding(horizontal = layout.horizontalPadding)

@Composable
private fun RailAction(
    control: Control?,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    events: Events,
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
        onInput = events.input,
        onVoiceStart = events.voiceStart,
        onVoiceStop = events.voiceStop,
        blocked = blocked,
        labelPlacement = LabelPlacement.HIDDEN,
        modifier = modifier,
    )
}
