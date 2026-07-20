package au.edu.uts.vibepocket.ui.control

import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
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
    blocked: Boolean,
    modifier: Modifier = Modifier,
) {
    val byId = inputs.associateBy(Input::id)
    val primaryIds = listOf("key_accept", "key_reject", "key_clear")
    val directionIds = listOf("key_up", "key_down", "key_left", "key_right")
    val secondaryIds = listOf("key_new_task", "key_stop", "key_mode")
    val primary = primaryIds.mapNotNull(byId::get)
    val secondary = secondaryIds.mapNotNull(byId::get)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
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
                blocked = blocked,
                modifier = Modifier.weight(0.92f).fillMaxHeight(),
            )
            Faces(
                inputs = primary,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onInput = onInput,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                blocked = blocked,
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
        }
        if (secondary.isNotEmpty()) {
            secondary.chunked(3).forEach { rowInputs ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowInputs.forEach { input ->
                        InputButton(
                            input = input,
                            snapshot = snapshot,
                            inFlightIds = inFlightIds,
                            onInput = onInput,
                            navigationRepeatEnabled = snapshot.supportsHidNavigationRepeat(input.id, hidNavigationAvailable),
                            onNavigationRepeat = onNavigationRepeat,
                            onVoiceStart = onVoiceStart,
                            onVoiceStop = onVoiceStop,
                            blocked = blocked,
                            labelPlacement = LabelPlacement.BESIDE,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        )
                    }
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
    blocked: Boolean,
    modifier: Modifier,
) {
    val byId = inputs.associateBy(Input::id)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            listOf("key_clear"),
            listOf("key_reject", "key_accept"),
        ).forEach { rowIds ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowIds.forEach { id ->
                    val input = byId[id] ?: return@forEach
                    InputButton(
                        input = input,
                        snapshot = snapshot,
                        inFlightIds = inFlightIds,
                        onInput = onInput,
                        onVoiceStart = onVoiceStart,
                        onVoiceStop = onVoiceStop,
                        blocked = blocked,
                        labelPlacement = LabelPlacement.BESIDE,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun Dpad(
    inputs: List<Input>,
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
    val byId = inputs.associateBy(Input::id)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier) {
            val side = minOf(maxWidth, maxHeight)
            val button = (side * 0.34f).coerceIn(48.dp, 64.dp)
            val center = (side * 0.27f).coerceIn(40.dp, 52.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(side),
            ) {
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
                        blocked = blocked,
                        labelPlacement = LabelPlacement.HIDDEN,
                        shape = CircleShape,
                        modifier = Modifier.align(alignment).size(button),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(center)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}
