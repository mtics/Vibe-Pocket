package au.edu.uts.vibepocket.ui

import au.edu.uts.vibepocket.control.Activity
import au.edu.uts.vibepocket.control.Agent
import au.edu.uts.vibepocket.control.Question
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.control.agentSlots
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.min

@Composable
internal fun QuestionPrompt(question: Question) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(WaitingColor.copy(alpha = 0.09f), RoundedCornerShape(6.dp))
            .border(1.dp, WaitingColor.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.HourglassTop, contentDescription = null, tint = WaitingColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(question.header, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                "${question.index + 1}/${question.count}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Text(question.text, style = MaterialTheme.typography.bodyMedium)
        question.options.forEachIndexed { index, option ->
            val selected = index == question.selectedOptionIndex && !question.hasSpokenAnswer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (selected) WaitingColor.copy(alpha = 0.16f) else Color.Transparent,
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (selected) Icons.Default.Check else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) WaitingColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(option.label, style = MaterialTheme.typography.labelLarge)
                    if (option.description.isNotEmpty()) {
                        Text(
                            option.description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (question.hasSpokenAnswer) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = WaitingColor, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (question.isSecret) "Private spoken answer ready" else "Spoken answer ready",
                    color = WaitingColor,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
internal fun TaskSummary(snapshot: Snapshot, message: String?) {
    val state = snapshot.desktop?.activity ?: if (snapshot.status.state == "ready") Activity.IDLE else Activity.ERROR
    val color = colorFor(state)
    val focusedAgent = snapshot.agentSlots().firstOrNull { it.focused }?.agent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.32f), RoundedCornerShape(6.dp))
            .heightIn(min = 68.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(iconFor(state), contentDescription = null, tint = color, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                focusedAgent?.label ?: "Codex controller",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                listOfNotNull(labelFor(state), message?.takeIf { it.isNotBlank() }).joinToString(" · "),
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun Agents(
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onAgentClick: (String) -> Unit,
) {
    val slots = snapshot.agentSlots()
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(slots.size) {
        if (slots.size <= COLLAPSED_AGENT_COUNT) expanded = false
    }
    val visibleSlots = if (expanded) slots else slots.take(COLLAPSED_AGENT_COUNT)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (visibleSlots.isEmpty()) {
            Text(
                "No active Codex tasks detected",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        visibleSlots.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { slot ->
                    val agent = slot.agent
                    AgentItem(
                        agent = agent,
                        focused = slot.focused,
                        enabled = agent != null && "agent:${agent.id}" !in inFlightIds && slot.canFocus,
                        loading = agent != null && "agent:${agent.id}" in inFlightIds,
                        onClick = { agent?.id?.let(onAgentClick) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(2 - row.size) {
                    Spacer(Modifier.weight(1f).height(68.dp))
                }
            }
        }
        if (slots.size > COLLAPSED_AGENT_COUNT) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (expanded) "Show fewer" else "Show ${slots.size - COLLAPSED_AGENT_COUNT} more")
            }
        }
    }
}

@Composable
private fun AgentItem(
    agent: Agent?,
    focused: Boolean,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val color = agent?.activity?.let(::colorFor) ?: MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .height(68.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused && agent != null) color.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface)
            .border(if (focused) 2.dp else 1.dp, color.copy(alpha = if (agent == null) 0.2f else 0.7f), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = if (agent == null) Arrangement.Center else Arrangement.SpaceBetween,
    ) {
        if (agent == null) {
            Text(
                "Empty",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(15.dp), color = color, strokeWidth = 2.dp)
                } else {
                    Icon(iconFor(agent.activity), contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    agent.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                labelFor(agent.activity),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}


private const val COLLAPSED_AGENT_COUNT = 6
