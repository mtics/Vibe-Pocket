package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Gesture
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun Workflows(
    catalog: Catalog,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    blocked: Boolean,
    modifier: Modifier = Modifier,
) {
    val largeText = largeText(LocalDensity.current.fontScale)
    val items = listOf(
        "review-pr" to "Review",
        "debug" to "Debug",
        "refactor" to "Refactor",
        "test" to "Tests",
    )
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (id, label) ->
            val control = catalog.find("workflow", workflowId = id)
            if (control == null) {
                Box(
                    Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).alpha(0.52f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = if (largeText) {
                            MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp)
                        } else {
                            MaterialTheme.typography.labelMedium
                        },
                        fontWeight = FontWeight.Medium,
                    )
                }
            } else {
                InputButton(
                    input = control.input,
                    gesture = control.gesture,
                    snapshot = snapshot,
                    inFlightIds = inFlightIds,
                    onInput = onInput,
                    onVoiceStart = onVoiceStart,
                    onVoiceStop = onVoiceStop,
                    blocked = blocked,
                    labelPlacement = LabelPlacement.BELOW,
                    labelOverride = label,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
            }
        }
    }
}
