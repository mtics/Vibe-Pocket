package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Reasoning as State
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.ui.control.sheet.Handle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun Reasoning(
    state: State,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    target: State.Level?,
    onReasoning: (State.Level) -> Boolean,
    blocked: Boolean,
    modifier: Modifier = Modifier,
) {
    val largeText = largeText(LocalDensity.current.fontScale)
    val pendingTarget = reasoningPendingTarget(inFlightIds) ?: target
    val pending = pendingTarget != null
    val showProgress = progressVisible(pending)
    val label = settingsLabel("Reasoning", snapshot.desktop?.activity)
    val display = reasoningDisplay(state, pendingTarget)
    val options = state.options.ifEmpty {
        listOfNotNull(state.decreaseTo, state.level, state.increaseTo).distinct()
    }
    val enabled = !blocked && !pending && options.isNotEmpty() && snapshot.reasoningSelectionEnabled()
    var showOptions by rememberSaveable(snapshot.desktop?.focusedAgentId) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) showOptions = false }

    Row(
        modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .alpha(if (enabled || pending) 1f else 0.58f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Step(
            state.decreaseTo,
            "Decrease reasoning",
            Icons.Default.Remove,
            enabled,
            showProgress && pendingTarget == state.decreaseTo,
            onReasoning,
        )
        Column(
            Modifier.weight(1f)
                .semantics {
                    role = Role.Button
                    contentDescription = reasoningDescription(state, pendingTarget, label)
                    if (!enabled) disabled()
                }
                .clickable(enabled = enabled) { showOptions = true },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (!largeText) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                if (largeText) "$label: $display" else display,
                maxLines = if (largeText) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                style = if (largeText) {
                    MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 10.sp)
                } else {
                    MaterialTheme.typography.labelMedium
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
        Step(
            state.increaseTo,
            "Increase reasoning",
            Icons.Default.Add,
            enabled,
            showProgress && pendingTarget == state.increaseTo,
            onReasoning,
        )
    }

    if (showOptions) {
        ModalBottomSheet(
            onDismissRequest = { showOptions = false },
            dragHandle = { Handle() },
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()).semantics { selectableGroup() },
            ) {
                Text(
                    "Choose reasoning",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                options.forEach { level ->
                    val selected = level == state.level
                    val optionPending = level == pendingTarget
                    ListItem(
                        headlineContent = { Text(level.displayLabel) },
                        trailingContent = when {
                            optionPending -> ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) })
                            selected -> ({ Icon(Icons.Default.Check, contentDescription = null) })
                            else -> null
                        },
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = selected,
                            enabled = enabled && !optionPending,
                            role = Role.RadioButton,
                            onClick = {
                                if (!selected && onReasoning(level)) showOptions = false
                            },
                        ),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Step(
    target: State.Level?,
    action: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    showProgress: Boolean,
    onReasoning: (State.Level) -> Boolean,
) {
    Box(
        Modifier.size(48.dp)
            .semantics {
                role = Role.Button
                contentDescription = reasoningStepDescription(action, target)
                if (!enabled || target == null) disabled()
            }
            .clickable(enabled = enabled && target != null) { target?.let(onReasoning) },
        contentAlignment = Alignment.Center,
    ) {
        if (showProgress) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
    }
}

internal fun reasoningStepDescription(action: String, target: State.Level?): String =
    target?.let { "$action to ${it.displayLabel}" } ?: "$action unavailable"

internal fun reasoningPendingTarget(inFlightIds: Set<String>): State.Level? = inFlightIds
    .let { pendingSelectionId("reasoning", it) }
    ?.let(State.Level::fromWire)

internal fun reasoningDisplay(state: State, target: State.Level?): String {
    val confirmed = state.level?.displayLabel ?: state.label.ifBlank { "Unavailable" }
    return target?.takeIf { it != state.level }?.let { "$confirmed -> ${it.displayLabel}" } ?: confirmed
}

internal fun reasoningDescription(
    state: State,
    target: State.Level?,
    name: String = "Reasoning",
): String {
    val confirmed = state.level?.displayLabel ?: state.label.ifBlank { "Unavailable" }
    return target?.takeIf { it != state.level }
        ?.let { "$name, $confirmed, changing to ${it.displayLabel}" }
        ?: "$name, $confirmed"
}
