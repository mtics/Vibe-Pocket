package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
internal fun Deck(
    inputs: List<Input>,
    snapshot: Snapshot,
    hidNavigationAvailable: Boolean,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onNavigationRepeat: (String, Boolean) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    shift: Boolean,
    blocked: Boolean,
    onLayerChord: (String) -> Boolean,
) {
    val byId = inputs.associateBy(Input::id)
    val primaryIds = listOf("key_accept", "key_reject", "key_voice", "key_clear")
    val directionIds = listOf("key_up", "key_down", "key_left", "key_right")
    val primary = primaryIds.mapNotNull(byId::get)
    val secondary = inputs
        .filter { it.id !in primaryIds && it.id !in directionIds }
        .distinctBy { snapshot.actionFor(it.id) ?: Action("physical:${it.id}") }
        .take(5)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(168.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Dpad(
                inputs = directionIds.mapNotNull(byId::get),
                snapshot = snapshot,
                hidNavigationAvailable = hidNavigationAvailable,
                inFlightIds = inFlightIds,
                onInput = onInput,
                onNavigationRepeat = onNavigationRepeat,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                shift = shift,
                blocked = blocked,
                onLayerChord = onLayerChord,
            )
            Faces(
                inputs = primary,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onInput = onInput,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                shift = shift,
                blocked = blocked,
                onLayerChord = onLayerChord,
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
        }
        if (secondary.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                secondary.forEach { input ->
                    InputButton(
                        input = input,
                        snapshot = snapshot,
                        inFlightIds = inFlightIds,
                        onInput = onInput,
                        navigationRepeatEnabled = snapshot.supportsHidNavigationRepeat(input.id, hidNavigationAvailable),
                        onNavigationRepeat = onNavigationRepeat,
                        onVoiceStart = onVoiceStart,
                        onVoiceStop = onVoiceStop,
                        shift = shift,
                        blocked = blocked,
                        onLayerChord = onLayerChord,
                        iconOnly = true,
                        modifier = Modifier.weight(1f).height(52.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Faces(
    inputs: List<Input>,
    snapshot: Snapshot,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    shift: Boolean,
    blocked: Boolean,
    onLayerChord: (String) -> Boolean,
    modifier: Modifier,
) {
    val byId = inputs.associateBy(Input::id)
    Box(modifier) {
        listOf(
            "key_voice" to Alignment.TopCenter,
            "key_reject" to Alignment.CenterStart,
            "key_accept" to Alignment.CenterEnd,
            "key_clear" to Alignment.BottomCenter,
        ).forEach { (id, alignment) ->
            val input = byId[id] ?: return@forEach
            InputButton(
                input = input,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onInput = onInput,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                shift = shift,
                blocked = blocked,
                onLayerChord = onLayerChord,
                iconOnly = true,
                shape = CircleShape,
                modifier = Modifier.align(alignment).size(62.dp),
            )
        }
    }
}

@Composable
private fun Dpad(
    inputs: List<Input>,
    snapshot: Snapshot,
    hidNavigationAvailable: Boolean,
    inFlightIds: Set<String>,
    onInput: (String, Gesture.Kind) -> Unit,
    onNavigationRepeat: (String, Boolean) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    shift: Boolean,
    blocked: Boolean,
    onLayerChord: (String) -> Boolean,
) {
    val byId = inputs.associateBy(Input::id)
    Box(modifier = Modifier.size(168.dp)) {
        listOf(
            "key_up" to Alignment.TopCenter,
            "key_down" to Alignment.BottomCenter,
            "key_left" to Alignment.CenterStart,
            "key_right" to Alignment.CenterEnd,
        ).forEach { (id, alignment) ->
            val input = byId[id] ?: return@forEach
            InputButton(
                input = input,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onInput = onInput,
                navigationRepeatEnabled = snapshot.supportsHidNavigationRepeat(input.id, hidNavigationAvailable),
                onNavigationRepeat = onNavigationRepeat,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                shift = shift,
                blocked = blocked,
                onLayerChord = onLayerChord,
                iconOnly = true,
                modifier = Modifier.align(alignment).size(58.dp),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(46.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}
