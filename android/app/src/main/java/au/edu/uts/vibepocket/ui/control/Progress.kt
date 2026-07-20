package au.edu.uts.vibepocket.ui.control

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

@Composable
internal fun progressVisible(pending: Boolean): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(pending) {
        visible = false
        if (pending) {
            delay(ProgressDelayMillis)
            visible = true
        }
    }
    return pending && visible
}

internal const val ProgressDelayMillis = 700L
