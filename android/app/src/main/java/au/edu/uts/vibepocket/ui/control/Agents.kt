package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.agentSlots
import au.edu.uts.vibepocket.ui.colorFor
import au.edu.uts.vibepocket.ui.iconFor
import au.edu.uts.vibepocket.ui.labelFor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun Agents(
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    blocked: Boolean,
    onAgent: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSkip: () -> Boolean = { false },
) {
    val slots = snapshot.agentSlots().filter { it.agent != null }
    if (slots.isEmpty()) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("No active Codex tasks", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    collectionInfo = CollectionInfo(rowCount = 1, columnCount = slots.size)
                    customActions = listOf(CustomAccessibilityAction("Skip agents", onSkip))
                },
        ) {
            val state = rememberLazyListState()
            LazyRow(
                modifier = Modifier.fillMaxSize(),
                state = state,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
            ) {
                itemsIndexed(
                    items = slots,
                    key = { _, slot -> requireNotNull(slot.agent).id },
                ) { index, slot ->
                    val agent = requireNotNull(slot.agent)
                    val color = colorFor(agent.activity)
                    val loading = "agent:${agent.id}" in inFlightIds
                    Row(
                        modifier = Modifier
                            .width(agentChipWidth(maxWidth))
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (slot.focused) color.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface)
                            .border(
                                width = if (slot.focused) 1.dp else 0.dp,
                                color = color.copy(alpha = 0.82f),
                                shape = RoundedCornerShape(8.dp),
                            )
                            .semantics {
                                role = Role.Button
                                selected = slot.focused
                                collectionItemInfo = CollectionItemInfo(
                                    rowIndex = 0,
                                    rowSpan = 1,
                                    columnIndex = index,
                                    columnSpan = 1,
                                )
                                stateDescription = agentPositionDescription(slot.focused, index, slots.size)
                            }
                            .clickable(enabled = slot.canFocus && !loading && !blocked, onClick = { onAgent(agent.id) })
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = color, strokeWidth = 2.dp)
                        } else {
                            Icon(iconFor(agent.activity), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                agent.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(labelFor(agent.activity), color = color, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

internal fun agentChipWidth(available: Dp, gap: Dp = 8.dp): Dp =
    if (available >= 280.dp) (available - gap) / 2f else available

internal fun agentPositionDescription(focused: Boolean, index: Int, total: Int): String =
    listOfNotNull(if (focused) "Focused" else null, "${index + 1} of $total").joinToString(", ")
