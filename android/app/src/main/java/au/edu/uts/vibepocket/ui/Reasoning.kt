package au.edu.uts.vibepocket.ui

import android.view.HapticFeedbackConstants
import au.edu.uts.vibepocket.control.Reasoning
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.inputAllowsQueuedRepeat
import au.edu.uts.vibepocket.gesture.dial.DeadZoneFraction as DialDeadZone
import au.edu.uts.vibepocket.gesture.dial.advance as advanceDial
import au.edu.uts.vibepocket.gesture.dial.begin as beginDial
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min

@Composable
internal fun ReasoningControl(
    inputs: List<Input>,
    reasoning: Reasoning,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    onOpenModelPicker: () -> Unit,
    inputBlocked: Boolean,
) {
    val counterClockwise = inputs.firstOrNull { it.id.endsWith("ccw") }
    val clockwise = inputs.firstOrNull { it.id.endsWith("cw") && !it.id.endsWith("ccw") }
    val counterClockwiseEnabled = counterClockwise?.let { snapshot.inputEnabled(it.id, Gesture.Kind.TAP) } == true
    val clockwiseEnabled = clockwise?.let { snapshot.inputEnabled(it.id, Gesture.Kind.TAP) } == true
    val inputPending = inputs.any { input ->
        inFlightIds.any { pending -> pending.startsWith("input:${input.id}:") } &&
            !snapshot.inputAllowsQueuedRepeat(input.id)
    }
    val rotationEnabled = !inputBlocked && !inputPending && (counterClockwiseEnabled || clockwiseEnabled)
    val currentOnInput by rememberUpdatedState(onInput)
    val currentOnOpenModelPicker by rememberUpdatedState(onOpenModelPicker)
    val view = LocalView.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val modelPickerEnabled = !inputBlocked && !inputPending &&
        snapshot.desktop?.foreground == true &&
        snapshot.desktop.question == null &&
        reasoning.available
    var rotationDegrees by remember { mutableStateOf(0f) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Step(
            counterClockwise,
            Icons.Filled.Remove,
            snapshot,
            inFlightIds,
            onInput,
            onVoiceStart,
            onVoiceStop,
            inputBlocked,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(2.dp, MaterialTheme.colorScheme.secondary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .semantics {
                            contentDescription = "Reasoning dial. Tap to choose a model, rotate to adjust reasoning."
                        }
                        .pointerInput(rotationEnabled, modelPickerEnabled, clockwise?.id, counterClockwise?.id) {
                            if (!rotationEnabled && !modelPickerEnabled) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val minimumRadius = min(size.width, size.height) * DialDeadZone
                                var dialState = beginDial(
                                    down.position.x,
                                    down.position.y,
                                    size.width / 2f,
                                    size.height / 2f,
                                    minimumRadius,
                                )
                                var moved = false
                                var stepped = false
                                var released = false
                                try {
                                    var pressed = true
                                    while (pressed) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        if (
                                            abs(change.position.x - down.position.x) > touchSlop ||
                                            abs(change.position.y - down.position.y) > touchSlop
                                        ) {
                                            moved = true
                                        }
                                        if (rotationEnabled) {
                                            val update = advanceDial(
                                                dialState,
                                                change.position.x,
                                                change.position.y,
                                                size.width / 2f,
                                                size.height / 2f,
                                                minimumRadius,
                                            )
                                            dialState = update.state
                                            rotationDegrees += Math.toDegrees(update.deltaRadians).toFloat()
                                            when (update.step) {
                                                1 -> clockwise?.id?.let { inputId ->
                                                    currentOnInput(inputId, Gesture.Kind.TAP)
                                                }
                                                -1 -> counterClockwise?.id?.let { inputId ->
                                                    currentOnInput(inputId, Gesture.Kind.TAP)
                                                }
                                            }
                                            if (update.step != 0) {
                                                stepped = true
                                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            }
                                        }
                                        pressed = change.pressed
                                        if (!pressed) released = true
                                        change.consume()
                                    }
                                } finally {
                                    rotationDegrees %= 360f
                                }
                                if (released && !moved && !stepped && modelPickerEnabled) {
                                    currentOnOpenModelPicker()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .size(34.dp)
                            .graphicsLayer { rotationZ = rotationDegrees },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (reasoning.modelLabel.isNotBlank()) {
                Text(
                    reasoning.modelLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    reasoning.level?.displayLabel ?: reasoning.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            } else {
                Text(
                    reasoning.label.ifBlank { if (reasoning.available) "Reasoning" else "Unavailable" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Step(
            clockwise,
            Icons.Filled.Add,
            snapshot,
            inFlightIds,
            onInput,
            onVoiceStart,
            onVoiceStop,
            inputBlocked,
        )
    }
}

@Composable
private fun Step(
    input: Input?,
    icon: ImageVector,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    inputBlocked: Boolean,
) {
    val enabledGestures = if (input == null) emptyList() else {
        Gesture.Kind.entries.filter { snapshot.inputEnabled(input.id, it) }
    }
    val inputPending = input?.let { candidate ->
        inFlightIds.any { it.startsWith("input:${candidate.id}:") } &&
            !snapshot.inputAllowsQueuedRepeat(candidate.id)
    } == true
    val baseEnabled = !inputBlocked && input != null && enabledGestures.isNotEmpty()
    val enabled = baseEnabled && !inputPending
    val voiceTap = input?.let { snapshot.voiceTapEnabled(it.id) } == true
    val tapEnabled = Gesture.Kind.TAP in enabledGestures
    val doubleTapEnabled = Gesture.Kind.DOUBLE_TAP in enabledGestures
    val holdEnabled = Gesture.Kind.HOLD in enabledGestures
    val currentVoiceStart by rememberUpdatedState(onVoiceStart)
    val currentVoiceStop by rememberUpdatedState(onVoiceStop)
    val voiceModifier = if (input == null) {
        Modifier
    } else {
        Modifier.pointerInput(input.id, enabled, voiceTap) {
            if (!enabled || !voiceTap) return@pointerInput
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
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(voiceModifier)
            .combinedClickable(
                enabled = enabled && !voiceTap,
                onClick = {
                    if (tapEnabled) {
                        input?.id?.let { onInput(it, Gesture.Kind.TAP) }
                    }
                },
                onDoubleClick = if (doubleTapEnabled) {
                    {
                        input?.id?.let { onInput(it, Gesture.Kind.DOUBLE_TAP) }
                    }
                } else null,
                onLongClick = if (holdEnabled) {
                    {
                        input?.id?.let { onInput(it, Gesture.Kind.HOLD) }
                    }
                } else null,
                onLongClickLabel = if (holdEnabled) "Run hold binding" else null,
            )
            .alpha(if (baseEnabled) 1f else 0.38f),
    ) {
        if (inputPending) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = input?.label, modifier = Modifier.size(28.dp))
        }
    }
}
