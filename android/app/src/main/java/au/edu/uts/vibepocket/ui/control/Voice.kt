package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun Voice(
    input: Input,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    blocked: Boolean,
    height: Dp = 96.dp,
    modifier: Modifier = Modifier,
) {
    InputButton(
        input = input,
        snapshot = snapshot,
        inFlightIds = inFlightIds,
        onInput = onInput,
        onVoiceStart = onVoiceStart,
        onVoiceStop = onVoiceStop,
        blocked = blocked,
        dedicatedVoiceControl = true,
        labelPlacement = LabelPlacement.BESIDE,
        modifier = modifier.fillMaxWidth().height(height),
    )
}
