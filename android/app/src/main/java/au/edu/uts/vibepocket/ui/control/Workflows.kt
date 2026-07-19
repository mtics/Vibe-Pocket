package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import androidx.compose.foundation.layout.Arrangement
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
    blocked: Boolean,
    modifier: Modifier = Modifier,
) {
    if (inputs.isEmpty()) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        inputs.forEach { input ->
            InputButton(
                input = input,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onInput = onInput,
                blocked = blocked,
                labelPlacement = LabelPlacement.BELOW,
                labelOverride = when (input.id) {
                    "joystick_up" -> "Review PR"
                    "joystick_down" -> "Debug"
                    "joystick_left" -> "Refactor"
                    "joystick_right" -> "Tests"
                    else -> input.label
                },
                modifier = Modifier.weight(1f).height(72.dp),
            )
        }
    }
}
