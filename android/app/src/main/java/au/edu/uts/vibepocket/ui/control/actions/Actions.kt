package au.edu.uts.vibepocket.ui.control.actions

import au.edu.uts.vibepocket.control.Selector
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.ui.control.Catalog
import au.edu.uts.vibepocket.ui.control.Control
import au.edu.uts.vibepocket.ui.control.InputButton
import au.edu.uts.vibepocket.ui.control.LabelPlacement
import au.edu.uts.vibepocket.ui.control.Layout
import au.edu.uts.vibepocket.ui.control.Mode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun Actions(
    catalog: Catalog,
    mode: Selector,
    snapshot: Snapshot,
    hidNavigationAvailable: Boolean,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onNavigationRepeat: (String, Boolean) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    onMode: (String) -> Boolean,
    blocked: Boolean,
    layout: Layout,
    modifier: Modifier = Modifier,
) {
    val directions = listOf("up", "down", "left", "right")
        .associateWith { catalog.find("navigate", direction = it) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(layout.gap)) {
        Row(
            Modifier.fillMaxWidth().height(layout.pad),
            horizontalArrangement = Arrangement.spacedBy(layout.actionGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pad(
                directions = directions,
                snapshot = snapshot,
                hidNavigationAvailable = hidNavigationAvailable,
                inFlightIds = inFlightIds,
                onInput = onInput,
                onNavigationRepeat = onNavigationRepeat,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                blocked = blocked,
                directionSize = layout.direction,
                centerSize = layout.center,
                modifier = Modifier.size(layout.pad),
            )
            Column(
                modifier = Modifier.weight(1f).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(layout.gap),
            ) {
                Mode(
                    state = mode,
                    snapshot = snapshot,
                    inFlightIds = inFlightIds,
                    onMode = onMode,
                    blocked = blocked,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                Slot(
                    catalog.find("delete_backward"), "Delete", snapshot, inFlightIds,
                    onInput, onVoiceStart, onVoiceStop, blocked, Modifier.weight(1f).fillMaxWidth(),
                )
                Slot(
                    catalog.find("new_task"), "New task", snapshot, inFlightIds,
                    onInput, onVoiceStart, onVoiceStop, blocked, Modifier.weight(1f).fillMaxWidth(),
                )
                Slot(
                    catalog.find("approve"), "Accept", snapshot, inFlightIds,
                    onInput, onVoiceStart, onVoiceStop, blocked, Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().height(layout.safety),
            horizontalArrangement = Arrangement.spacedBy(layout.gap),
        ) {
            listOf(
                catalog.find("clear_input") to "Clear",
                catalog.find("reject") to "Reject",
                catalog.find("stop") to "Stop",
            ).forEach { (control, label) ->
                Slot(
                    control, label, snapshot, inFlightIds, onInput, onVoiceStart, onVoiceStop,
                    blocked, Modifier.weight(1f).fillMaxSize(),
                )
            }
        }
    }
}

@Composable
internal fun LandscapeActions(
    catalog: Catalog,
    mode: Selector,
    snapshot: Snapshot,
    hidNavigationAvailable: Boolean,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onNavigationRepeat: (String, Boolean) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    onMode: (String) -> Boolean,
    blocked: Boolean,
    layout: Layout,
    modifier: Modifier = Modifier,
) {
    val directions = listOf("up", "down", "left", "right")
        .associateWith { catalog.find("navigate", direction = it) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(layout.gap)) {
        Row(
            Modifier.fillMaxWidth().height(layout.pad),
            horizontalArrangement = Arrangement.spacedBy(layout.actionGap),
        ) {
            Pad(
                directions = directions,
                snapshot = snapshot,
                hidNavigationAvailable = hidNavigationAvailable,
                inFlightIds = inFlightIds,
                onInput = onInput,
                onNavigationRepeat = onNavigationRepeat,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                blocked = blocked,
                directionSize = layout.direction,
                centerSize = layout.center,
                modifier = Modifier.size(layout.pad),
            )
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(layout.gap),
            ) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(layout.gap)) {
                    Mode(
                        state = mode,
                        snapshot = snapshot,
                        inFlightIds = inFlightIds,
                        onMode = onMode,
                        blocked = blocked,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    Slot(
                        catalog.find("delete_backward"), "Delete", snapshot, inFlightIds,
                        onInput, onVoiceStart, onVoiceStop, blocked,
                        Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(layout.gap)) {
                    Slot(
                        catalog.find("new_task"), "New task", snapshot, inFlightIds,
                        onInput, onVoiceStart, onVoiceStop, blocked,
                        Modifier.weight(1f).fillMaxHeight(),
                    )
                    Slot(
                        catalog.find("approve"), "Accept", snapshot, inFlightIds,
                        onInput, onVoiceStart, onVoiceStop, blocked,
                        Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(layout.safety),
            horizontalArrangement = Arrangement.spacedBy(layout.gap),
        ) {
            listOf(
                catalog.find("clear_input") to "Clear",
                catalog.find("reject") to "Reject",
                catalog.find("stop") to "Stop",
            ).forEach { (control, label) ->
                Slot(
                    control, label, snapshot, inFlightIds, onInput, onVoiceStart, onVoiceStop,
                    blocked, Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun Pad(
    directions: Map<String, Control?>,
    snapshot: Snapshot,
    hidNavigationAvailable: Boolean,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onNavigationRepeat: (String, Boolean) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    blocked: Boolean,
    directionSize: androidx.compose.ui.unit.Dp,
    centerSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
) {
    Box(modifier) {
        listOf(
            "up" to Alignment.TopCenter,
            "down" to Alignment.BottomCenter,
            "left" to Alignment.CenterStart,
            "right" to Alignment.CenterEnd,
        ).forEach { (direction, alignment) ->
            val control = directions[direction]
            if (control == null) {
                Empty(direction, Modifier.align(alignment).size(directionSize))
            } else {
                InputButton(
                    input = control.input,
                    gesture = control.gesture,
                    snapshot = snapshot,
                    inFlightIds = inFlightIds,
                    onInput = onInput,
                    navigationRepeatEnabled = control.gesture == Gesture.Kind.TAP &&
                        snapshot.supportsHidNavigationRepeat(control.input.id, hidNavigationAvailable),
                    onNavigationRepeat = onNavigationRepeat,
                    onVoiceStart = onVoiceStart,
                    onVoiceStop = onVoiceStop,
                    blocked = blocked,
                    labelPlacement = LabelPlacement.HIDDEN,
                    shape = CircleShape,
                    modifier = Modifier.align(alignment).size(directionSize),
                )
            }
        }
        Box(
            Modifier.align(Alignment.Center).size(centerSize).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
private fun Slot(
    control: Control?,
    label: String,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    blocked: Boolean,
    modifier: Modifier,
) {
    if (control == null) {
        Empty(label, modifier)
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
        labelPlacement = LabelPlacement.TEXT,
        labelOverride = label,
        modifier = modifier,
    )
}

@Composable
private fun Empty(label: String, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .alpha(0.52f),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
    }
}
