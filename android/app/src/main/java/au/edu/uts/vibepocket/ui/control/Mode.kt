package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Selector
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.ui.control.sheet.Handle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.runtime.remember
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
internal fun Mode(
    state: Selector,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onMode: (String) -> Boolean,
    blocked: Boolean,
    modifier: Modifier = Modifier,
) {
    val largeText = largeText(LocalDensity.current.fontScale)
    var showOptions by remember(snapshot.desktop?.focusedAgentId) { mutableStateOf(false) }
    val pendingId = pendingSelectionId("mode", inFlightIds)
    val pending = pendingId != null
    val confirmed = state.label.ifBlank { "Unavailable" }
    val target = state.options.firstOrNull { it.id == pendingId }?.label ?: pendingId
    val display = selectionDisplay(confirmed, target)
    val showProgress = progressVisible(pending)
    val enabled = !blocked && !pending && snapshot.transportFresh &&
        snapshot.capabilities.modeCycle && state.available && state.options.isNotEmpty() &&
        snapshot.desktop?.foreground == true && snapshot.desktop.question == null &&
        snapshot.desktop.voice?.active != true

    LaunchedEffect(enabled) { if (!enabled) showOptions = false }

    Box(
        modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.34f), RoundedCornerShape(8.dp))
            .semantics {
                role = Role.Button
                contentDescription = selectionDescription("Mode", confirmed, target)
                if (!enabled) disabled()
            }
            .clickable(enabled = enabled) { showOptions = true }
            .alpha(if (enabled || pending) 1f else 0.58f)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                if (!largeText) {
                    Text("Mode", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    if (largeText) "Mode: $display" else display,
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
            if (showProgress) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else if (!largeText) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    if (showOptions) {
        ModalBottomSheet(
            onDismissRequest = { showOptions = false },
            dragHandle = { Handle() },
        ) {
            Column(Modifier.semantics { selectableGroup() }) {
                Text(
                    "Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                state.options.forEach { option ->
                    val selected = option.id == state.id
                    ListItem(
                        headlineContent = { Text(option.label) },
                        supportingContent = { Text(option.id) },
                        trailingContent = {
                            if (selected) Icon(Icons.Default.Check, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(
                                selected = selected,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = {
                                    if (!selected && onMode(option.id)) showOptions = false
                                },
                            ),
                    )
                }
            }
        }
    }
}
