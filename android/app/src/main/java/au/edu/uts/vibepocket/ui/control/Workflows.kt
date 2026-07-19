package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun Workflows(
    inputs: List<Input>,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    blocked: Boolean,
) {
    if (inputs.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        inputs.chunked(2).forEach { rowInputs ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowInputs.forEach { input ->
                    InputButton(
                        input = input,
                        snapshot = snapshot,
                        inFlightIds = inFlightIds,
                        onInput = onInput,
                        onVoiceStart = onVoiceStart,
                        onVoiceStop = onVoiceStop,
                        blocked = blocked,
                        modifier = Modifier.weight(1f).height(64.dp),
                    )
                }
            }
        }
    }
}
