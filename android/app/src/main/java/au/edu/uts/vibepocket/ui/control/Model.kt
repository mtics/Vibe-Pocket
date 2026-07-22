package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Model as State
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.ui.control.sheet.Handle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SmartToy
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
import androidx.compose.ui.draw.clip
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
internal fun Model(
    state: State,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    targetId: String?,
    onModel: (String) -> Boolean,
    blocked: Boolean,
    modifier: Modifier = Modifier,
) {
    val largeText = largeText(LocalDensity.current.fontScale)
    val pendingId = pendingSelectionId("model", inFlightIds) ?: targetId
    val pending = pendingId != null
    val confirmed = state.label.ifBlank { "Choose" }
    val target = state.options.firstOrNull { it.id == pendingId }?.label ?: pendingId
    val display = selectionDisplay(confirmed, target)
    val showProgress = progressVisible(pending)
    val label = settingsLabel("Model", snapshot.desktop?.activity)
    val enabled = modelSelectionAllowed(
        blocked = blocked,
        pending = pending,
        selectionAvailable = snapshot.modelSelectionEnabled(),
    )
    var showOptions by rememberSaveable(snapshot.desktop?.focusedAgentId) { mutableStateOf(false) }

    LaunchedEffect(enabled) {
        if (!enabled) showOptions = false
    }

    Row(
        modifier = modifier
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .semantics {
                role = Role.Button
                contentDescription = selectionDescription(label, confirmed, target)
                if (!enabled) disabled()
            }
            .clickable(enabled = enabled) { if (enabled) showOptions = true }
            .alpha(if (enabled || pending) 1f else 0.62f)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!largeText) {
            Icon(
                Icons.Default.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f)) {
            if (!largeText) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                if (largeText) "$label: $display" else display,
                maxLines = if (largeText) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                style = if (largeText) {
                    MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp)
                } else {
                    MaterialTheme.typography.labelMedium
                },
                fontWeight = FontWeight.Medium,
            )
        }
        if (showProgress) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else if (!largeText) {
            Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }

    if (showOptions) {
        ModalBottomSheet(
            onDismissRequest = { showOptions = false },
            dragHandle = { Handle() },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .semantics { selectableGroup() },
            ) {
                Text(
                    "Choose model",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                state.options.forEach { option ->
                    val optionPending = option.id == pendingId
                    val optionEnabled = enabled && !optionPending
                    ListItem(
                        headlineContent = { Text(option.label) },
                        trailingContent = when {
                            optionPending -> ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) })
                            option.selected -> ({ Icon(Icons.Default.Check, contentDescription = null) })
                            else -> null
                        },
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = option.selected,
                            enabled = optionEnabled,
                            role = Role.RadioButton,
                            onClick = {
                                if (!option.selected && onModel(option.id)) showOptions = false
                            },
                        ),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

internal fun modelSelectionAllowed(
    blocked: Boolean,
    pending: Boolean,
    selectionAvailable: Boolean,
): Boolean = !blocked && !pending && selectionAvailable
