package au.edu.uts.vibepocket.ui.control.actions

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import au.edu.uts.vibepocket.ui.control.Dpad
import au.edu.uts.vibepocket.ui.control.InputButton
import au.edu.uts.vibepocket.ui.control.LabelPlacement
import au.edu.uts.vibepocket.ui.control.state.State
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    modifier: Modifier = Modifier,
) {
    val byId = inputs.associateBy(Input::id)
    when (state.kind) {
        State.Kind.ERROR -> Error(
            byId = byId,
            snapshot = snapshot,
            inFlightIds = inFlightIds,
            onInput = onInput,
            modifier = modifier,
        )
        State.Kind.READY, State.Kind.QUESTION, State.Kind.DECISION, State.Kind.RUNNING -> Controller(
            state = state,
            byId = byId,
            modeInput = modeInput,
            snapshot = snapshot,
            hidNavigationAvailable = hidNavigationAvailable,
            inFlightIds = inFlightIds,
            onInput = onInput,
            onNavigationRepeat = onNavigationRepeat,
            modifier = modifier,
        )
    }
}

@Composable
private fun Controller(
    state: State,
    byId: Map<String, Input>,
    modeInput: Input?,
    snapshot: Snapshot,
    hidNavigationAvailable: Boolean,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onNavigationRepeat: (String, Boolean) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val footer = listOfNotNull(byId["key_reject"], byId["key_stop"])
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Dpad(
                inputs = listOf("key_up", "key_down", "key_left", "key_right").mapNotNull(byId::get),
                snapshot = snapshot,
                hidNavigationAvailable = hidNavigationAvailable,
                inFlightIds = inFlightIds,
                onInput = onInput,
                onNavigationRepeat = onNavigationRepeat,
                blocked = false,
                modifier = Modifier.weight(1.15f).fillMaxHeight(),
            )
            Column(
                modifier = Modifier.weight(0.85f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOfNotNull(
                    modeInput,
                    byId["key_clear"],
                    byId["key_new_task"],
                    byId["key_accept"],
                ).forEach { input ->
                    val isMode = input.id == modeInput?.id
                    InputButton(
                        input = input,
                        snapshot = snapshot,
                        inFlightIds = inFlightIds,
                        onInput = onInput,
                        blocked = false,
                        labelPlacement = if (isMode) LabelPlacement.TEXT else LabelPlacement.BESIDE,
                        labelOverride = if (isMode) {
                            snapshot.desktop?.mode?.label?.takeIf(String::isNotBlank) ?: "Default"
                        } else null,
                        supportingLabel = if (isMode) "Mode" else null,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            footer.forEach { input ->
                InputButton(
                    input = input,
                    snapshot = snapshot,
                    inFlightIds = inFlightIds,
                    onInput = onInput,
                    blocked = false,
                    labelPlacement = LabelPlacement.BESIDE,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
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
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        listOfNotNull(byId["key_attach"], byId["key_new_task"]).forEach { input ->
            InputButton(
                input = input,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onInput = onInput,
                blocked = false,
                labelPlacement = LabelPlacement.BESIDE,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            )
        }
    }
}
