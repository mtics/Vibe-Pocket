package au.edu.uts.vibepocket.ui.control.actions

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import au.edu.uts.vibepocket.ui.control.Dpad
import au.edu.uts.vibepocket.ui.control.InputButton
import au.edu.uts.vibepocket.ui.control.LabelPlacement
import au.edu.uts.vibepocket.ui.control.state.State
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun Actions(
    state: State,
    inputs: List<Input>,
    modeInput: Input?,
    snapshot: Snapshot,
    hidNavigationAvailable: Boolean,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onNavigationRepeat: (String, Boolean) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    blocked: Boolean,
    modifier: Modifier = Modifier,
) {
    val byId = inputs.associateBy(Input::id)
    when (state.kind) {
        State.Kind.ERROR -> Error(
            byId = byId,
            snapshot = snapshot,
            inFlightIds = inFlightIds,
            onInput = onInput,
            onVoiceStart = onVoiceStart,
            onVoiceStop = onVoiceStop,
            blocked = blocked,
            modifier = modifier,
        )
        State.Kind.READY, State.Kind.QUESTION, State.Kind.DECISION, State.Kind.RUNNING -> Controller(
            byId = byId,
            modeInput = modeInput,
            snapshot = snapshot,
            hidNavigationAvailable = hidNavigationAvailable,
            inFlightIds = inFlightIds,
            onInput = onInput,
            onNavigationRepeat = onNavigationRepeat,
            onVoiceStart = onVoiceStart,
            onVoiceStop = onVoiceStop,
            blocked = blocked,
            modifier = modifier,
        )
    }
}

@Composable
private fun Controller(
    byId: Map<String, Input>,
    modeInput: Input?,
    snapshot: Snapshot,
    hidNavigationAvailable: Boolean,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onNavigationRepeat: (String, Boolean) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    blocked: Boolean,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val footer = listOfNotNull(byId["key_reject"], byId["key_stop"])
        Row(
            Modifier.fillMaxWidth().heightIn(min = 208.dp, max = 300.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dpad(
                inputs = listOf("key_up", "key_down", "key_left", "key_right").mapNotNull(byId::get),
                snapshot = snapshot,
                hidNavigationAvailable = hidNavigationAvailable,
                inFlightIds = inFlightIds,
                onInput = onInput,
                onNavigationRepeat = onNavigationRepeat,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                blocked = blocked,
                modifier = Modifier.weight(1.15f).aspectRatio(1f),
            )
            Column(
                modifier = Modifier.weight(0.85f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOfNotNull(
                    modeInput,
                    byId["key_clear"],
                    byId["key_new_task"],
                    byId["key_accept"],
                ).distinctBy(Input::id).forEach { input ->
                    val isMode = input.id == modeInput?.id
                    InputButton(
                        input = input,
                        snapshot = snapshot,
                        inFlightIds = inFlightIds,
                        onInput = onInput,
                        onVoiceStart = onVoiceStart,
                        onVoiceStop = onVoiceStop,
                        blocked = blocked,
                        labelPlacement = if (isMode) LabelPlacement.TEXT else LabelPlacement.BESIDE,
                        labelOverride = if (isMode) {
                            snapshot.desktop?.mode?.label?.takeIf(String::isNotBlank) ?: "Default"
                        } else null,
                        supportingLabel = if (isMode) "Mode" else null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            footer.forEach { input ->
                InputButton(
                    input = input,
                    snapshot = snapshot,
                    inFlightIds = inFlightIds,
                    onInput = onInput,
                    onVoiceStart = onVoiceStart,
                    onVoiceStop = onVoiceStop,
                    blocked = blocked,
                    labelPlacement = LabelPlacement.BESIDE,
                    modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                )
            }
        }
    }
}

@Composable
private fun Error(
    byId: Map<String, Input>,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    blocked: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        listOfNotNull(byId["key_attach"], byId["key_new_task"]).forEach { input ->
            InputButton(
                input = input,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onInput = onInput,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                blocked = blocked,
                labelPlacement = LabelPlacement.BESIDE,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            )
        }
    }
}
