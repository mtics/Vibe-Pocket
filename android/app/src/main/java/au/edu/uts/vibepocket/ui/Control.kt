package au.edu.uts.vibepocket.ui

import au.edu.uts.vibepocket.control.Reasoning
import au.edu.uts.vibepocket.control.Selector
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.agentSlots
import au.edu.uts.vibepocket.gesture.layer.GuardMillis
import au.edu.uts.vibepocket.gesture.layer.Route as LayerRoute
import au.edu.uts.vibepocket.gesture.layer.route as routeLayer
import au.edu.uts.vibepocket.profile.FallbackInputs
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun Control(
    snapshot: Snapshot,
    statusMessage: String?,
    hidNavigationAvailable: Boolean,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onNavigationRepeat: (String, Boolean) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    onAgent: (String) -> Unit,
    onModelPicker: () -> Unit,
    onLayer: (String) -> Boolean,
) {
    val controller = snapshot.desktop
    val inputs = controller?.profile?.inputs.orEmpty()
    val profileKeys = inputs.filter { it.kind == Input.Kind.KEY }
    val keyInputs = (profileKeys + FallbackInputs.filter { fallback -> profileKeys.none { it.id == fallback.id } })
        .distinctBy(Input::id)
        .take(13)
    val touchInput = inputs.firstOrNull { it.kind == Input.Kind.TOUCH }
    val joystickInputs = inputs.filter { it.kind == Input.Kind.JOYSTICK }
    val dialInputs = inputs.filter { it.kind == Input.Kind.DIAL }
    val layerScope = rememberCoroutineScope()
    var layerModifierPressed by remember { mutableStateOf(false) }
    var layerGuardActive by remember { mutableStateOf(false) }
    var selectedView by rememberSaveable { mutableStateOf("control") }
    var selectedDeck by rememberSaveable { mutableStateOf("keys") }

    LaunchedEffect(selectedDeck, joystickInputs.isNotEmpty(), dialInputs.isNotEmpty()) {
        if (
            (selectedDeck == "workflows" && joystickInputs.isEmpty()) ||
            (selectedDeck == "reasoning" && dialInputs.isEmpty())
        ) {
            selectedDeck = "keys"
        }
    }

    fun armLayerGuard() {
        layerGuardActive = true
        layerScope.launch {
            delay(GuardMillis)
            layerGuardActive = false
        }
    }

    fun routeLayerInput(inputId: String, gesture: Gesture.Kind): Boolean = when (
        val route = routeLayer(inputId, gesture, layerModifierPressed, layerGuardActive)
    ) {
        LayerRoute.Pass -> false
        LayerRoute.Suppress -> true
        is LayerRoute.Select -> {
            if (onLayer(route.layerId)) armLayerGuard()
            true
        }
    }

    val routedInput: (String, Gesture.Kind) -> Unit = { inputId, gesture ->
        if (!routeLayerInput(inputId, gesture)) onInput(inputId, gesture)
    }
    val routedVoiceStart: (String) -> Boolean = { inputId ->
        if (routeLayerInput(inputId, Gesture.Kind.TAP)) false else onVoiceStart(inputId)
    }
    val routedNavigationRepeat: (String, Boolean) -> Unit = { inputId, initial ->
        if (!initial || !routeLayerInput(inputId, Gesture.Kind.TAP)) {
            onNavigationRepeat(inputId, initial)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TaskSummary(snapshot, statusMessage)
        snapshot.desktop?.question?.let { QuestionPrompt(it) }
        Views(
            selected = selectedView,
            agentCount = snapshot.agentSlots().count { it.agent != null },
            onSelected = { selectedView = it },
        )
        if (selectedView == "agents") {
            Section("Active agents")
            Agents(
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onAgentClick = onAgent,
            )
        } else {
            controller?.profile?.layers?.take(6)?.let { layers ->
                Section("Layers")
                Layers(
                    layers = layers,
                    activeLayerId = controller.activeLayerId,
                    inFlightIds = inFlightIds,
                    enabled = snapshot.status.state == "ready",
                    onLayer = onLayer,
                    modifierPressed = layerModifierPressed,
                    onModifierPressed = { layerModifierPressed = it },
                )
            }
            Mode(
                mode = controller?.mode ?: Selector(false, "Unavailable"),
                access = controller?.access ?: Selector(false, "Unavailable"),
                touchInput = touchInput,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onInput = routedInput,
                onVoiceStart = routedVoiceStart,
                onVoiceStop = onVoiceStop,
                layerModifierPressed = layerModifierPressed,
                inputBlocked = layerGuardActive,
            )
            Deck(
                selected = selectedDeck,
                workflowsAvailable = joystickInputs.isNotEmpty(),
                reasoningAvailable = dialInputs.isNotEmpty(),
                onSelected = { selectedDeck = it },
            )
            when (selectedDeck) {
                "workflows" -> Workflows(
                    inputs = joystickInputs,
                    snapshot = snapshot,
                    inFlightIds = inFlightIds,
                    onGesture = routedInput,
                    onVoiceStart = routedVoiceStart,
                    onVoiceStop = onVoiceStop,
                    inputBlocked = layerGuardActive,
                )
                "reasoning" -> ReasoningControl(
                    inputs = dialInputs,
                    reasoning = controller?.reasoning ?: Reasoning.Unavailable,
                    snapshot = snapshot,
                    inFlightIds = inFlightIds,
                    onInput = routedInput,
                    onVoiceStart = routedVoiceStart,
                    onVoiceStop = onVoiceStop,
                    onOpenModelPicker = onModelPicker,
                    inputBlocked = layerGuardActive,
                )
                else -> Commands(
                    inputs = keyInputs,
                    snapshot = snapshot,
                    hidNavigationAvailable = hidNavigationAvailable,
                    inFlightIds = inFlightIds,
                    onInput = routedInput,
                    onNavigationRepeat = routedNavigationRepeat,
                    onVoiceStart = routedVoiceStart,
                    onVoiceStop = onVoiceStop,
                    layerModifierPressed = layerModifierPressed,
                    inputBlocked = layerGuardActive,
                    onLayerChord = { inputId -> routeLayerInput(inputId, Gesture.Kind.TAP) },
                )
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun Deck(
    selected: String,
    workflowsAvailable: Boolean,
    reasoningAvailable: Boolean,
    onSelected: (String) -> Unit,
) {
    val options = listOf(
        Triple("keys", "Keys", true),
        Triple("workflows", "Workflows", workflowsAvailable),
        Triple("reasoning", "Reasoning", reasoningAvailable),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (id, label, available) ->
            val active = selected == id
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable(enabled = available && !active, onClick = { onSelected(id) })
                    .alpha(if (available) 1f else 0.38f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Views(
    selected: String,
    agentCount: Int,
    onSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            Triple("control", "Control", Icons.Default.Tune),
            Triple("agents", "Agents $agentCount", Icons.Default.AccountCircle),
        ).forEach { (id, label, icon) ->
            val active = selected == id
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable(enabled = !active, onClick = { onSelected(id) }),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    label,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
