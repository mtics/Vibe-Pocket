package au.edu.uts.vibepocket.ui

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.gesture.release.Decision as ReleaseDecision
import au.edu.uts.vibepocket.gesture.release.Timing as ReleaseTiming
import au.edu.uts.vibepocket.input.Dispatch
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import au.edu.uts.vibepocket.profile.Workflow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun Workflows(
    inputs: List<Input>,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onGesture: (String, Gesture.Kind) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    inputBlocked: Boolean,
) {
    val byDirection = remember(inputs) { inputs.associateBy { it.id.substringAfterLast('_') } }
    val enabledIds = inputs.filter { input ->
        !inputBlocked && !inFlightIds.any { it.startsWith("input:${input.id}:") }
            && Gesture.Kind.entries.any { snapshot.inputEnabled(input.id, it) }
    }.mapTo(mutableSetOf(), Input::id)
    val voiceIds = inputs.filter {
        !inputBlocked && snapshot.voiceTapEnabled(it.id) && !inFlightIds.any { pending -> pending.startsWith("input:${it.id}:") }
    }.mapTo(mutableSetOf(), Input::id)
    var selectedId by remember { mutableStateOf<String?>(null) }
    val currentOnGesture by rememberUpdatedState(onGesture)
    val currentVoiceStart by rememberUpdatedState(onVoiceStart)
    val currentVoiceStop by rememberUpdatedState(onVoiceStop)
    val gestureScope = rememberCoroutineScope()
    val viewConfiguration = LocalViewConfiguration.current
    val pendingTapJobs = remember { mutableMapOf<String, Job>() }
    val releaseArbiter = remember(viewConfiguration.doubleTapTimeoutMillis) {
        ReleaseTiming(viewConfiguration.doubleTapTimeoutMillis)
    }

    fun dispatchRelease(inputId: String, downAt: Long, releasedAt: Long) {
        when (val decision = releaseArbiter.release(
            inputId = inputId,
            downAt = downAt,
            releasedAt = releasedAt,
            tapEnabled = snapshot.inputEnabled(inputId, Gesture.Kind.TAP),
            doubleTapEnabled = snapshot.inputEnabled(inputId, Gesture.Kind.DOUBLE_TAP),
            holdEnabled = snapshot.inputEnabled(inputId, Gesture.Kind.HOLD),
            longPressTimeoutMillis = viewConfiguration.longPressTimeoutMillis,
        )) {
            is ReleaseDecision.Dispatch -> {
                pendingTapJobs.remove(inputId)?.cancel()
                currentOnGesture(inputId, decision.gesture)
            }
            is ReleaseDecision.DeferTap -> {
                pendingTapJobs.remove(inputId)?.cancel()
                pendingTapJobs[inputId] = gestureScope.launch {
                    delay(viewConfiguration.doubleTapTimeoutMillis)
                    pendingTapJobs.remove(inputId)
                    releaseArbiter.completeDeferredTap(inputId, decision.token)?.let { gesture ->
                        currentOnGesture(inputId, gesture)
                    }
                }
            }
            ReleaseDecision.None -> pendingTapJobs.remove(inputId)?.cancel()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(182.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(176.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                .pointerInput(enabledIds, voiceIds, inputs, inputBlocked) {
                    if (inputBlocked) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downAt = down.uptimeMillis
                        var candidate = joystickInput(down.position.x, down.position.y, size, byDirection)
                            ?.takeIf { it.id in enabledIds }
                        var candidateStartedAt = downAt
                        selectedId = candidate?.id
                        var voiceCandidateId = candidate?.id?.takeIf { it in voiceIds }
                        var activeVoiceId = voiceCandidateId?.takeIf { currentVoiceStart(it) }
                        var releasedAt = downAt
                        try {
                            var pressed = true
                            while (pressed) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                releasedAt = change.uptimeMillis
                                val nextCandidate = joystickInput(change.position.x, change.position.y, size, byDirection)
                                    ?.takeIf { it.id in enabledIds }
                                if (nextCandidate?.id != candidate?.id) candidateStartedAt = change.uptimeMillis
                                candidate = nextCandidate
                                selectedId = candidate?.id
                                val nextVoiceId = candidate?.id?.takeIf { it in voiceIds }
                                if (nextVoiceId != voiceCandidateId) {
                                    activeVoiceId?.let(currentVoiceStop)
                                    voiceCandidateId = nextVoiceId
                                    activeVoiceId = nextVoiceId?.takeIf { currentVoiceStart(it) }
                                }
                                pressed = change.pressed
                                change.consume()
                            }
                            val releaseId = selectedId
                            if (releaseId != null && releaseId !in voiceIds) {
                                dispatchRelease(releaseId, candidateStartedAt, releasedAt)
                            }
                        } finally {
                            activeVoiceId?.let(currentVoiceStop)
                            selectedId = null
                        }
                    }
                },
        ) {
            Direction(byDirection["up"], selectedId, Alignment.TopCenter, enabledIds)
            Direction(byDirection["down"], selectedId, Alignment.BottomCenter, enabledIds)
            Direction(byDirection["left"], selectedId, Alignment.CenterStart, enabledIds)
            Direction(byDirection["right"], selectedId, Alignment.CenterEnd, enabledIds)
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (selectedId == null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Tune, contentDescription = "Workflow joystick", tint = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun BoxScope.Direction(
    input: Input?,
    selectedId: String?,
    alignment: Alignment,
    enabledIds: Set<String>,
) {
    if (input == null) return
    val selected = selectedId == input.id
    val enabled = input.id in enabledIds
    Column(
        modifier = Modifier
            .align(alignment)
            .size(width = 72.dp, height = 57.dp)
            .padding(4.dp)
            .alpha(if (enabled) 1f else 0.35f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            iconForDirection(input.id),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (selected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(input.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
    }
}
