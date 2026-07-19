package au.edu.uts.vibepocket.ui

import au.edu.uts.vibepocket.control.Selector
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.inputAllowsQueuedRepeat
import au.edu.uts.vibepocket.gesture.layer.isTarget as isLayerTarget
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import au.edu.uts.vibepocket.profile.Layer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun Layers(
    layers: List<Layer>,
    activeLayerId: String?,
    inFlightIds: Set<String>,
    enabled: Boolean,
    onLayer: (String) -> Boolean,
    modifierPressed: Boolean,
    onModifierPressed: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LayerShift(
            pressed = modifierPressed,
            enabled = enabled,
            onPressed = onModifierPressed,
            modifier = Modifier.weight(1f),
        )
        layers.forEachIndexed { index, layer ->
            val selected = layer.id == activeLayerId
            val layerColor = profileColor(layer.color)
            val loading = "layer:${layer.id}" in inFlightIds
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) layerColor.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surface)
                    .border(if (selected) 2.dp else 1.dp, layerColor.copy(alpha = if (selected) 1f else 0.45f), RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled && !selected && !loading, onClick = { onLayer(layer.id) }),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(17.dp), color = layerColor, strokeWidth = 2.dp)
                } else {
                    Text("${index + 1}", color = layerColor, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
@Composable
private fun LayerShift(
    pressed: Boolean,
    enabled: Boolean,
    onPressed: (Boolean) -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (pressed) MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f) else MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = if (pressed) 1f else 0.52f), RoundedCornerShape(6.dp))
            .semantics { contentDescription = "Layer shift modifier" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onPressed(true)
                    try {
                        var stillPressed = true
                        while (stillPressed) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            stillPressed = change?.pressed == true
                        }
                    } finally {
                        onPressed(false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("L1", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
internal fun Mode(
    mode: Selector,
    access: Selector,
    touchInput: Input?,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    layerModifierPressed: Boolean,
    inputBlocked: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Mode", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text(
                mode.label.ifBlank { "Unavailable" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
        }
        Column(Modifier.weight(1f)) {
            Text("Access", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text(
                access.label.ifBlank { "Unavailable" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
        }
        if (touchInput != null) {
            GestureButton(
                input = touchInput,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onInput = onInput,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                layerModifierPressed = layerModifierPressed,
                inputBlocked = inputBlocked,
                modifier = Modifier.width(142.dp).height(54.dp),
            )
        }
    }
}

@Composable
internal fun Commands(
    inputs: List<Input>,
    snapshot: Snapshot,
    hidNavigationAvailable: Boolean,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onNavigationRepeat: (String, Boolean) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    layerModifierPressed: Boolean,
    inputBlocked: Boolean,
    onLayerChord: (String) -> Boolean,
) {
    inputs.chunked(3).forEach { rowInputs ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowInputs.forEach { input ->
                CommandButton(
                    input = input,
                    snapshot = snapshot,
                    hidNavigationAvailable = hidNavigationAvailable,
                    inFlightIds = inFlightIds,
                    onInput = onInput,
                    onNavigationRepeat = onNavigationRepeat,
                    onVoiceStart = onVoiceStart,
                    onVoiceStop = onVoiceStop,
                    layerModifierPressed = layerModifierPressed,
                    inputBlocked = inputBlocked,
                    onLayerChord = onLayerChord,
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(3 - rowInputs.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun CommandButton(
    input: Input,
    snapshot: Snapshot,
    hidNavigationAvailable: Boolean,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onNavigationRepeat: (String, Boolean) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    layerModifierPressed: Boolean,
    inputBlocked: Boolean,
    onLayerChord: (String) -> Boolean,
    modifier: Modifier,
) {
    GestureButton(
        input = input,
        snapshot = snapshot,
        inFlightIds = inFlightIds,
        onInput = onInput,
        navigationRepeatEnabled = snapshot.supportsHidNavigationRepeat(input.id, hidNavigationAvailable),
        onNavigationRepeat = onNavigationRepeat,
        onVoiceStart = onVoiceStart,
        onVoiceStop = onVoiceStop,
        layerModifierPressed = layerModifierPressed,
        inputBlocked = inputBlocked,
        onLayerChord = onLayerChord,
        modifier = modifier.height(72.dp),
    )
}

@Composable
internal fun GestureButton(
    input: Input,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    navigationRepeatEnabled: Boolean = false,
    onNavigationRepeat: (String, Boolean) -> Unit = { _, _ -> },
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    layerModifierPressed: Boolean = false,
    inputBlocked: Boolean = false,
    onLayerChord: (String) -> Boolean = { false },
    modifier: Modifier,
) {
    val enabledGestures = Gesture.Kind.entries.filter { snapshot.inputEnabled(input.id, it) }
    val voiceTap = snapshot.voiceTapEnabled(input.id)
    val tapEnabled = Gesture.Kind.TAP in enabledGestures
    val doubleTapEnabled = Gesture.Kind.DOUBLE_TAP in enabledGestures
    val holdEnabled = Gesture.Kind.HOLD in enabledGestures
    val inputPending = inFlightIds.any { it.startsWith("input:${input.id}:") } &&
        !snapshot.inputAllowsQueuedRepeat(input.id)
    val effectiveVoiceTap = voiceTap && !layerModifierPressed
    val voicePressEnabled = effectiveVoiceTap && !inputPending && !inputBlocked
    val interactive = !inputBlocked && (effectiveVoiceTap || enabledGestures.isNotEmpty())
    val currentVoiceStart by rememberUpdatedState(onVoiceStart)
    val currentVoiceStop by rememberUpdatedState(onVoiceStop)
    val currentNavigationRepeat by rememberUpdatedState(onNavigationRepeat)
    val currentLayerChord by rememberUpdatedState(onLayerChord)
    val repeatNavigation = navigationRepeatEnabled && !inputPending && !inputBlocked && !layerModifierPressed
    val layerChordEnabled = layerModifierPressed && !inputBlocked && isLayerTarget(input.id)
    val accent = when (input.id) {
        "key_accept" -> MaterialTheme.colorScheme.primary
        "key_reject", "key_stop" -> ErrorColor
        "key_voice" -> MaterialTheme.colorScheme.tertiary
        "key_new_task" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val container = when (input.id) {
        "key_accept" -> MaterialTheme.colorScheme.primaryContainer
        "key_reject", "key_stop" -> ErrorColor.copy(alpha = 0.18f)
        "key_voice" -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
        "key_new_task" -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
        else -> MaterialTheme.colorScheme.surface
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(6.dp))
            .pointerInput(input.id, layerChordEnabled) {
                if (!layerChordEnabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!currentLayerChord(input.id)) return@awaitEachGesture
                    down.consume()
                    var pressed = true
                    while (pressed) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        pressed = change?.pressed == true
                        change?.consume()
                    }
                }
            }
            .pointerInput(input.id, voicePressEnabled) {
                if (!voicePressEnabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val started = currentVoiceStart(input.id)
                    try {
                        var pressed = true
                        while (pressed) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            pressed = change?.pressed == true
                        }
                    } finally {
                        if (started) currentVoiceStop(input.id)
                    }
                }
            }
            .pointerInput(input.id, repeatNavigation) {
                if (!repeatNavigation) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    currentNavigationRepeat(input.id, true)
                    try {
                        var pressed = true
                        while (pressed) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            pressed = change?.pressed == true
                            change?.consume()
                        }
                    } finally {
                        currentNavigationRepeat(input.id, false)
                    }
                }
            }
            .combinedClickable(
                enabled = !effectiveVoiceTap && enabledGestures.isNotEmpty() && !inputPending && !inputBlocked && !repeatNavigation,
                onClick = {
                    if (!effectiveVoiceTap && tapEnabled) {
                        onInput(input.id, Gesture.Kind.TAP)
                    }
                },
                onDoubleClick = if (doubleTapEnabled) {
                    { onInput(input.id, Gesture.Kind.DOUBLE_TAP) }
                } else null,
                onLongClick = if (holdEnabled) {
                    { onInput(input.id, Gesture.Kind.HOLD) }
                } else null,
                onLongClickLabel = if (holdEnabled) "Run hold binding" else null,
            )
            .alpha(if (interactive) 1f else 0.38f)
            .padding(horizontal = 7.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        if (inputPending) {
            CircularProgressIndicator(Modifier.size(21.dp), color = accent, strokeWidth = 2.dp)
        } else {
            Icon(iconForInput(input.icon), contentDescription = null, tint = accent, modifier = Modifier.size(21.dp))
        }
        Text(input.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        Gestures(snapshot, input.id)
    }
}

@Composable
private fun Gestures(snapshot: Snapshot, inputId: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.semantics { contentDescription = "Mapped gestures" },
    ) {
        Gesture.Kind.entries.forEach { gesture ->
            val mapped = snapshot.actionFor(inputId, gesture) != null
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(
                        if (mapped) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f),
                    ),
            )
        }
    }
}
