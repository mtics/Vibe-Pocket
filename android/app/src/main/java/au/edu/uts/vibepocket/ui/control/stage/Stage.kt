package au.edu.uts.vibepocket.ui.control.stage

import au.edu.uts.vibepocket.ui.colorFor
import au.edu.uts.vibepocket.ui.control.state.State
import au.edu.uts.vibepocket.ui.iconFor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun Stage(state: State, modifier: Modifier = Modifier) {
    val accent = colorFor(state.activity)
    val largeText = au.edu.uts.vibepocket.ui.control.largeText(LocalDensity.current.fontScale)
    val expandable = stageCanExpand(state)
    var showDetails by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.kind, state.task) { showDetails = false }
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (expandable) {
                    Modifier.clickable(onClickLabel = "Show status details") { showDetails = true }
                } else {
                    Modifier
                },
            )
            .semantics {
                if (state.kind != State.Kind.READY || state.activity.wireValue != "idle") {
                    liveRegion = LiveRegionMode.Polite
                }
                stateDescription = stageDescription(state)
            },
    ) {
        Box(Modifier.fillMaxHeight().width(4.dp).background(accent))
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(iconFor(state.activity), contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = if (largeText) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!largeText) {
                    Text(
                        state.task ?: state.selection ?: state.detail.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (!largeText) state.meta?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }

    if (showDetails) {
        ModalBottomSheet(onDismissRequest = { showDetails = false }) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                Text(state.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                state.task?.takeIf(String::isNotBlank)?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.titleSmall)
                }
                state.detail?.takeIf(String::isNotBlank)?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodyLarge)
                }
                state.selection?.takeIf(String::isNotBlank)?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                state.meta?.takeIf(String::isNotBlank)?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

internal fun stageCanExpand(state: State): Boolean =
    listOf(state.task, state.detail, state.selection, state.meta).any { !it.isNullOrBlank() }

internal fun stageDescription(state: State): String =
    listOfNotNull(state.title, state.task, state.detail, state.selection, state.meta)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(". ")
