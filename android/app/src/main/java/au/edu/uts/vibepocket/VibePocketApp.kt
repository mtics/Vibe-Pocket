package au.edu.uts.vibepocket

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import kotlin.math.min

private val VibeColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF55D6A4),
    onPrimary = Color(0xFF062A1C),
    primaryContainer = Color(0xFF153B2B),
    secondary = Color(0xFFE8B86F),
    onSecondary = Color(0xFF2E1D04),
    secondaryContainer = Color(0xFF493419),
    tertiary = Color(0xFF65C9E8),
    background = Color(0xFF0E1210),
    onBackground = Color(0xFFF0F4F1),
    surface = Color(0xFF18201B),
    onSurface = Color(0xFFF0F4F1),
    surfaceVariant = Color(0xFF29322D),
    onSurfaceVariant = Color(0xFFC1CBC4),
    error = Color(0xFFFF8877),
    onError = Color(0xFF3B0904),
)

private val IdleColor = Color(0xFF9AA39D)
private val UnreadColor = Color(0xFFF08BC1)
private val ThinkingColor = Color(0xFFB9A7FF)
private val ExecutingColor = Color(0xFF59C7F2)
private val WaitingColor = Color(0xFFF2B95F)
private val CompleteColor = Color(0xFF55D6A4)
private val ErrorColor = Color(0xFFFF776B)

@Composable
fun VibePocketTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VibeColors, content = content)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun VibePocketApp(viewModel: PocketViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showMappings by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setForeground(true)
                Lifecycle.Event.ON_STOP -> viewModel.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.setForeground(true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setForeground(false)
        }
    }

    LaunchedEffect(viewModel, view) {
        viewModel.feedback.collect { feedback ->
            val constant = when (feedback) {
                PocketFeedback.Success -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                }
                PocketFeedback.Error -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.REJECT
                } else {
                    HapticFeedbackConstants.LONG_PRESS
                }
            }
            view.performHapticFeedback(constant)
        }
    }

    if (state.config == null) {
        ConnectScreen(onConnect = viewModel::connect, error = state.error)
        return
    }

    val onInput: (String, ControllerGesture) -> Unit = { inputId, gesture ->
        if (viewModel.activateInput(inputId, gesture)) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
    val onVoiceStart: (String) -> Boolean = { inputId ->
        viewModel.startVoice(inputId).also { started ->
            if (started) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
    val onVoiceStop: (String) -> Unit = { inputId ->
        viewModel.stopVoice(inputId)
    }
    val onAgent: (String) -> Unit = { agentId ->
        if (viewModel.focusAgent(agentId)) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
    val onLayer: (String) -> Unit = { layerId ->
        if (viewModel.selectLayer(layerId)) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Vibe Pocket", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (state.snapshot?.controller?.actionCatalog?.isNotEmpty() == true) {
                        IconButton(onClick = { showMappings = true }, enabled = state.inFlightId == null) {
                            Icon(Icons.Filled.Settings, contentDescription = "Configure controller mappings")
                        }
                    }
                    IconButton(onClick = viewModel::refresh, enabled = !state.isRefreshing) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh controller state")
                        }
                    }
                    IconButton(onClick = viewModel::disconnect) {
                        Icon(Icons.Filled.Close, contentDescription = "Disconnect bridge")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            state.error?.let { ErrorBanner(it) }
            val snapshot = state.snapshot
            if (snapshot == null) {
                LoadingScreen(isRefreshing = state.isRefreshing, onRefresh = viewModel::refresh)
            } else {
                ControllerScreen(
                    snapshot = snapshot,
                    inFlightId = state.inFlightId,
                    onInput = onInput,
                    onVoiceStart = onVoiceStart,
                    onVoiceStop = onVoiceStop,
                    onAgent = onAgent,
                    onLayer = onLayer,
                )
            }
        }
    }

    val controller = state.snapshot?.controller
    if (showMappings && controller?.profile != null && controller.actionCatalog.isNotEmpty()) {
        MappingSheet(
            snapshot = requireNotNull(state.snapshot),
            inFlightId = state.inFlightId,
            onDismiss = { showMappings = false },
            onUpdate = { layerId, inputId, gesture, actionId ->
                if (viewModel.updateBinding(layerId, inputId, gesture, actionId)) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
            },
            onClear = { layerId, inputId, gesture ->
                if (viewModel.clearBinding(layerId, inputId, gesture)) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
            },
            onRename = viewModel::renameLayer,
            onReset = viewModel::resetProfile,
        )
    }
}

@Composable
private fun ConnectScreen(onConnect: (String, String) -> Unit, error: String?) {
    var url by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Vibe Pocket", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Bridge URL") },
            placeholder = { Text("https://m5.tailnet.ts.net") },
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Pairing token") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        error?.let {
            Spacer(Modifier.height(12.dp))
            ErrorBanner(it)
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { onConnect(url, token) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
        ) { Text("Connect") }
    }
}

@Composable
private fun ControllerScreen(
    snapshot: PocketSnapshot,
    inFlightId: String?,
    onInput: (String, ControllerGesture) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    onAgent: (String) -> Unit,
    onLayer: (String) -> Unit,
) {
    val controller = snapshot.controller
    val inputs = controller?.profile?.inputs.orEmpty()
    val profileKeys = inputs.filter { it.kind == InputKind.KEY }
    val keyInputs = (profileKeys + FallbackKeyInputs.filter { fallback -> profileKeys.none { it.id == fallback.id } })
        .distinctBy(ControllerInput::id)
        .take(13)
    val touchInput = inputs.firstOrNull { it.kind == InputKind.TOUCH }
    val joystickInputs = inputs.filter { it.kind == InputKind.JOYSTICK }
    val dialInputs = inputs.filter { it.kind == InputKind.DIAL }
    val busy = inFlightId != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TaskStatusStrip(snapshot)
        SectionLabel("Agents")
        AgentGrid(
            snapshot = snapshot,
            inFlightId = inFlightId,
            onAgentClick = onAgent,
        )
        controller?.profile?.layers?.take(6)?.let { layers ->
            SectionLabel("Layers")
            LayerSelector(
                layers = layers,
                activeLayerId = controller.activeLayerId,
                inFlightId = inFlightId,
                enabled = !busy && snapshot.status.state == "ready",
                onLayer = onLayer,
            )
        }
        ModeAndFocus(
            mode = controller?.mode ?: SelectorStatus(false, "Unavailable"),
            touchInput = touchInput,
            snapshot = snapshot,
            busy = busy,
            inFlightId = inFlightId,
            onInput = onInput,
            onVoiceStart = onVoiceStart,
            onVoiceStop = onVoiceStop,
        )
        SectionLabel("Command keys")
        CommandKeyGrid(
            inputs = keyInputs,
            snapshot = snapshot,
            inFlightId = inFlightId,
            onInput = onInput,
            onVoiceStart = onVoiceStart,
            onVoiceStop = onVoiceStop,
        )
        if (joystickInputs.isNotEmpty()) {
            SectionLabel("Workflows")
            WorkflowJoystick(
                inputs = joystickInputs,
                snapshot = snapshot,
                busy = busy,
                onRelease = { onInput(it, ControllerGesture.TAP) },
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
            )
        }
        if (dialInputs.isNotEmpty()) {
            SectionLabel("Reasoning")
            ReasoningDial(
                inputs = dialInputs,
                reasoning = controller?.reasoning ?: SelectorStatus(false, "Unavailable"),
                snapshot = snapshot,
                inFlightId = inFlightId,
                onInput = { onInput(it, ControllerGesture.TAP) },
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
            )
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun TaskStatusStrip(snapshot: PocketSnapshot) {
    val state = snapshot.controller?.taskState ?: if (snapshot.status.state == "ready") TaskState.IDLE else TaskState.ERROR
    val color = stateColor(state)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(stateIcon(state), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(9.dp))
        Text(stateLabel(state), fontWeight = FontWeight.SemiBold)
        snapshot.status.message?.let { message ->
            Spacer(Modifier.width(10.dp))
            Text(
                message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AgentGrid(
    snapshot: PocketSnapshot,
    inFlightId: String?,
    onAgentClick: (String) -> Unit,
) {
    val slots = snapshot.agentSlots()
    slots.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { slot ->
                val agent = slot.agent
                AgentTile(
                    agent = agent,
                    focused = slot.focused,
                    enabled = inFlightId == null && slot.canFocus,
                    loading = agent != null && inFlightId == "agent:${agent.id}",
                    onClick = { agent?.id?.let(onAgentClick) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AgentTile(
    agent: AgentStatus?,
    focused: Boolean,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val color = agent?.state?.let(::stateColor) ?: MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused && agent != null) color.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface)
            .border(if (focused) 2.dp else 1.dp, color.copy(alpha = if (agent == null) 0.2f else 0.7f), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = if (agent == null) Arrangement.Center else Arrangement.SpaceBetween,
    ) {
        if (agent == null) {
            Text(
                "Empty",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(15.dp), color = color, strokeWidth = 2.dp)
                } else {
                    Icon(stateIcon(agent.state), contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    agent.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                stateLabel(agent.state),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

@Composable
private fun LayerSelector(
    layers: List<ControllerLayer>,
    activeLayerId: String?,
    inFlightId: String?,
    enabled: Boolean,
    onLayer: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        layers.forEachIndexed { index, layer ->
            val selected = layer.id == activeLayerId
            val layerColor = parseProfileColor(layer.color)
            val loading = inFlightId == "layer:${layer.id}"
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) layerColor.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surface)
                    .border(if (selected) 2.dp else 1.dp, layerColor.copy(alpha = if (selected) 1f else 0.45f), RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled && !selected, onClick = { onLayer(layer.id) }),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(17.dp), color = layerColor, strokeWidth = 2.dp)
                } else {
                    Text("${index + 1}", color = layerColor, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ModeAndFocus(
    mode: SelectorStatus,
    touchInput: ControllerInput?,
    snapshot: PocketSnapshot,
    busy: Boolean,
    inFlightId: String?,
    onInput: (String, ControllerGesture) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Mode", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text(
                mode.label.ifBlank { "Unavailable" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
        }
        if (touchInput != null) {
            GestureControl(
                input = touchInput,
                snapshot = snapshot,
                busy = busy,
                inFlightId = inFlightId,
                onInput = onInput,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                modifier = Modifier.width(142.dp).height(54.dp),
            )
        }
    }
}

@Composable
private fun CommandKeyGrid(
    inputs: List<ControllerInput>,
    snapshot: PocketSnapshot,
    inFlightId: String?,
    onInput: (String, ControllerGesture) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
) {
    inputs.chunked(3).forEach { rowInputs ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowInputs.forEach { input ->
                CommandKey(
                    input = input,
                    snapshot = snapshot,
                    busy = inFlightId != null,
                    inFlightId = inFlightId,
                    onInput = onInput,
                    onVoiceStart = onVoiceStart,
                    onVoiceStop = onVoiceStop,
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(3 - rowInputs.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun CommandKey(
    input: ControllerInput,
    snapshot: PocketSnapshot,
    busy: Boolean,
    inFlightId: String?,
    onInput: (String, ControllerGesture) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    modifier: Modifier,
) {
    GestureControl(
        input = input,
        snapshot = snapshot,
        busy = busy,
        inFlightId = inFlightId,
        onInput = onInput,
        onVoiceStart = onVoiceStart,
        onVoiceStop = onVoiceStop,
        modifier = modifier.height(66.dp),
    )
}

@Composable
private fun GestureControl(
    input: ControllerInput,
    snapshot: PocketSnapshot,
    busy: Boolean,
    inFlightId: String?,
    onInput: (String, ControllerGesture) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    modifier: Modifier,
) {
    val enabledGestures = ControllerGesture.entries.filter { snapshot.inputEnabled(input.id, it) }
    val voiceTap = snapshot.voiceTapEnabled(input.id)
    val voicePressEnabled = voiceTap && !busy
    val currentVoiceStart by rememberUpdatedState(onVoiceStart)
    val currentVoiceStop by rememberUpdatedState(onVoiceStop)
    val loading = inFlightId?.startsWith("input:${input.id}:") == true
    val container = when (input.id) {
        "key_accept" -> MaterialTheme.colorScheme.primaryContainer
        "key_reject", "key_stop" -> ErrorColor.copy(alpha = 0.18f)
        "key_voice" -> Color(0xFF285169)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .pointerInput(input.id, voicePressEnabled) {
                if (!voicePressEnabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val started = currentVoiceStart(input.id)
                    try {
                        var pressed = true
                        while (pressed) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            pressed = change?.pressed == true
                        }
                    } finally {
                        if (started) currentVoiceStop(input.id)
                    }
                }
            }
            .combinedClickable(
                enabled = enabledGestures.any { it != ControllerGesture.TAP || !voiceTap } && !busy,
                onClick = {
                    if (!voiceTap && ControllerGesture.TAP in enabledGestures) {
                        onInput(input.id, ControllerGesture.TAP)
                    }
                },
                onDoubleClick = {
                    if (ControllerGesture.DOUBLE_TAP in enabledGestures) onInput(input.id, ControllerGesture.DOUBLE_TAP)
                },
                onLongClick = {
                    if (ControllerGesture.HOLD in enabledGestures) onInput(input.id, ControllerGesture.HOLD)
                },
                onLongClickLabel = "Run hold binding",
            )
            .alpha(if (enabledGestures.isNotEmpty()) 1f else 0.38f)
            .padding(horizontal = 7.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(inputIcon(input.icon), contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Text(input.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        GestureIndicators(snapshot, input.id)
    }
}

@Composable
private fun GestureIndicators(snapshot: PocketSnapshot, inputId: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ControllerGesture.entries.forEach { gesture ->
            val mapped = snapshot.actionFor(inputId, gesture) != null
            Text(
                gesture.shortLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (mapped) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                fontWeight = if (mapped) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun WorkflowJoystick(
    inputs: List<ControllerInput>,
    snapshot: PocketSnapshot,
    busy: Boolean,
    onRelease: (String) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
) {
    val byDirection = remember(inputs) { inputs.associateBy { it.id.substringAfterLast('_') } }
    val enabledIds = inputs.filter { snapshot.inputEnabled(it.id) && !busy }.mapTo(mutableSetOf(), ControllerInput::id)
    val voiceIds = inputs.filter { snapshot.voiceTapEnabled(it.id) && !busy }.mapTo(mutableSetOf(), ControllerInput::id)
    var selectedId by remember { mutableStateOf<String?>(null) }
    val currentOnRelease by rememberUpdatedState(onRelease)
    val currentVoiceStart by rememberUpdatedState(onVoiceStart)
    val currentVoiceStop by rememberUpdatedState(onVoiceStop)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(182.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(176.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                .pointerInput(enabledIds, voiceIds, inputs) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var candidate = joystickInputAt(down.position.x, down.position.y, size, byDirection)
                            ?.takeIf { it.id in enabledIds }
                        selectedId = candidate?.id
                        var voiceCandidateId = candidate?.id?.takeIf { it in voiceIds }
                        var activeVoiceId = voiceCandidateId?.takeIf { currentVoiceStart(it) }
                        try {
                            var pressed = true
                            while (pressed) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                candidate = joystickInputAt(change.position.x, change.position.y, size, byDirection)
                                    ?.takeIf { it.id in enabledIds }
                                selectedId = candidate?.id
                                val nextVoiceId = candidate?.id?.takeIf { it in voiceIds }
                                if (nextVoiceId != voiceCandidateId) {
                                    activeVoiceId?.let(currentVoiceStop)
                                    voiceCandidateId = nextVoiceId
                                    activeVoiceId = nextVoiceId?.takeIf { currentVoiceStart(it) }
                                }
                                pressed = change.pressed
                                change.consume()
                            }
                            val releaseId = selectedId
                            if (releaseId != null && releaseId !in voiceIds) currentOnRelease(releaseId)
                        } finally {
                            activeVoiceId?.let(currentVoiceStop)
                            selectedId = null
                        }
                    }
                },
        ) {
            JoystickDirection(byDirection["up"], selectedId, Alignment.TopCenter, enabledIds)
            JoystickDirection(byDirection["down"], selectedId, Alignment.BottomCenter, enabledIds)
            JoystickDirection(byDirection["left"], selectedId, Alignment.CenterStart, enabledIds)
            JoystickDirection(byDirection["right"], selectedId, Alignment.CenterEnd, enabledIds)
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (selectedId == null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Tune, contentDescription = "Workflow joystick", tint = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun BoxScope.JoystickDirection(
    input: ControllerInput?,
    selectedId: String?,
    alignment: Alignment,
    enabledIds: Set<String>,
) {
    if (input == null) return
    val selected = selectedId == input.id
    val enabled = input.id in enabledIds
    Column(
        modifier = Modifier
            .align(alignment)
            .size(width = 72.dp, height = 57.dp)
            .padding(4.dp)
            .alpha(if (enabled) 1f else 0.35f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            directionIcon(input.id),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (selected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(input.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ReasoningDial(
    inputs: List<ControllerInput>,
    reasoning: SelectorStatus,
    snapshot: PocketSnapshot,
    inFlightId: String?,
    onInput: (String) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
) {
    val counterClockwise = inputs.firstOrNull { it.id.endsWith("ccw") }
    val clockwise = inputs.firstOrNull { it.id.endsWith("cw") && !it.id.endsWith("ccw") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        DialStepButton(
            counterClockwise,
            Icons.Filled.Remove,
            snapshot,
            inFlightId,
            onInput,
            onVoiceStart,
            onVoiceStop,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(2.dp, MaterialTheme.colorScheme.secondary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                reasoning.label.ifBlank { "Unavailable" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
        }
        DialStepButton(
            clockwise,
            Icons.Filled.Add,
            snapshot,
            inFlightId,
            onInput,
            onVoiceStart,
            onVoiceStop,
        )
    }
}

@Composable
private fun DialStepButton(
    input: ControllerInput?,
    icon: ImageVector,
    snapshot: PocketSnapshot,
    inFlightId: String?,
    onInput: (String) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
) {
    val enabled = input != null && snapshot.inputEnabled(input.id) && inFlightId == null
    val voiceTap = input?.let { snapshot.voiceTapEnabled(it.id) } == true
    val currentVoiceStart by rememberUpdatedState(onVoiceStart)
    val currentVoiceStop by rememberUpdatedState(onVoiceStop)
    val voiceModifier = if (input == null) {
        Modifier
    } else {
        Modifier.pointerInput(input.id, enabled, voiceTap) {
            if (!enabled || !voiceTap) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val started = currentVoiceStart(input.id)
                try {
                    var pressed = true
                    while (pressed) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        pressed = change?.pressed == true
                    }
                } finally {
                    if (started) currentVoiceStop(input.id)
                }
            }
        }
    }
    IconButton(
        onClick = { if (!voiceTap) input?.id?.let(onInput) },
        enabled = enabled,
        modifier = Modifier.size(52.dp).then(voiceModifier),
    ) {
        if (input != null && inFlightId == "input:${input.id}:${ControllerGesture.TAP.wireValue}") {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = input?.label, modifier = Modifier.size(28.dp))
        }
    }
}

private data class MappingTarget(
    val input: ControllerInput,
    val gesture: ControllerGesture,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MappingSheet(
    snapshot: PocketSnapshot,
    inFlightId: String?,
    onDismiss: () -> Unit,
    onUpdate: (String, String, ControllerGesture, String) -> Unit,
    onClear: (String, String, ControllerGesture) -> Unit,
    onRename: (String, String) -> Boolean,
    onReset: () -> Boolean,
) {
    val controller = snapshot.controller ?: return
    val profile = controller.profile ?: return
    val layer = profile.layers.firstOrNull { it.id == controller.activeLayerId } ?: profile.layers.firstOrNull() ?: return
    var target by remember { mutableStateOf<MappingTarget?>(null) }
    var layerName by rememberSaveable(layer.id, layer.name) { mutableStateOf(layer.name) }
    var confirmReset by remember { mutableStateOf(false) }
    val busy = inFlightId != null

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mappings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(layer.name, color = parseProfileColor(layer.color), style = MaterialTheme.typography.labelMedium)
                }
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = layerName,
                    onValueChange = { if (it.length <= 40) layerName = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Layer name") },
                )
                TextButton(
                    onClick = { onRename(layer.id, layerName) },
                    enabled = !busy && layerName.trim().isNotEmpty() && layerName.trim() != layer.name,
                ) { Text("Rename") }
            }
            Spacer(Modifier.height(10.dp))
            profile.inputs.forEach { input ->
                MappingInputRow(
                    input = input,
                    binding = layer.bindings[input.id],
                    catalog = controller.actionCatalog,
                    enabled = !busy,
                    onSelect = { gesture -> target = MappingTarget(input, gesture) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f))
            }
            TextButton(
                onClick = { confirmReset = true },
                enabled = !busy,
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Reset all layers") }
            Spacer(Modifier.height(18.dp))
        }
    }

    target?.let { selected ->
        ActionPickerDialog(
            target = selected,
            currentAction = layer.bindings[selected.input.id]?.actions?.get(selected.gesture),
            catalog = controller.actionCatalog,
            onDismiss = { target = null },
            onSelect = { actionId ->
                onUpdate(layer.id, selected.input.id, selected.gesture, actionId)
                target = null
            },
            onClear = {
                onClear(layer.id, selected.input.id, selected.gesture)
                target = null
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset all mappings?") },
            text = { Text("All six layers will return to the default profile.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (onReset()) confirmReset = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MappingInputRow(
    input: ControllerInput,
    binding: BindingDescriptor?,
    catalog: List<ActionCatalogEntry>,
    enabled: Boolean,
    onSelect: (ControllerGesture) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(inputIcon(input.icon), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(input.label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        }
        ControllerGesture.entries.forEach { gesture ->
            val action = binding?.actions?.get(gesture)
            val label = catalog.firstOrNull { it.action == action }?.label ?: action?.type ?: "Unmapped"
            Column(
                modifier = Modifier
                    .width(61.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        if (action == null) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.primaryContainer,
                    )
                    .clickable(enabled = enabled, onClick = { onSelect(gesture) })
                    .padding(horizontal = 4.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(gesture.shortLabel, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                Text(
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun ActionPickerDialog(
    target: MappingTarget,
    currentAction: ControllerAction?,
    catalog: List<ActionCatalogEntry>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${target.input.label} · ${target.gesture.shortLabel}") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                if (currentAction != null) {
                    TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                        Text("Clear mapping", color = MaterialTheme.colorScheme.error)
                    }
                    HorizontalDivider()
                }
                catalog.forEach { entry ->
                    val selected = entry.action == currentAction
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(entry.id) }
                            .padding(horizontal = 4.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(entry.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SectionLabel(label: String) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Error, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LoadingScreen(isRefreshing: Boolean, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isRefreshing) CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(12.dp))
        Text(if (isRefreshing) "Connecting to M5..." else "No controller state yet")
        if (!isRefreshing) {
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onRefresh, shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Retry")
            }
        }
    }
}

private fun joystickInputAt(
    x: Float,
    y: Float,
    size: IntSize,
    inputs: Map<String, ControllerInput>,
): ControllerInput? {
    val dx = x - size.width / 2f
    val dy = y - size.height / 2f
    if (abs(dx) < min(size.width, size.height) * 0.12f && abs(dy) < min(size.width, size.height) * 0.12f) return null
    val direction = if (abs(dx) > abs(dy)) {
        if (dx > 0) "right" else "left"
    } else {
        if (dy > 0) "down" else "up"
    }
    return inputs[direction]
}

private fun stateColor(state: TaskState): Color = when (state) {
    TaskState.IDLE -> IdleColor
    TaskState.UNREAD -> UnreadColor
    TaskState.THINKING -> ThinkingColor
    TaskState.EXECUTING -> ExecutingColor
    TaskState.WAITING -> WaitingColor
    TaskState.COMPLETE -> CompleteColor
    TaskState.ERROR -> ErrorColor
}

private fun stateIcon(state: TaskState): ImageVector = when (state) {
    TaskState.IDLE -> Icons.Filled.RadioButtonUnchecked
    TaskState.UNREAD -> Icons.Filled.MarkChatUnread
    TaskState.THINKING -> Icons.Filled.Psychology
    TaskState.EXECUTING -> Icons.Filled.PlayArrow
    TaskState.WAITING -> Icons.Filled.HourglassTop
    TaskState.COMPLETE -> Icons.Filled.Done
    TaskState.ERROR -> Icons.Filled.Error
}

private fun stateLabel(state: TaskState): String = when (state) {
    TaskState.IDLE -> "Idle"
    TaskState.UNREAD -> "Unread"
    TaskState.THINKING -> "Thinking"
    TaskState.EXECUTING -> "Running"
    TaskState.WAITING -> "Needs input"
    TaskState.COMPLETE -> "Complete"
    TaskState.ERROR -> "Error"
}

private fun inputIcon(name: String): ImageVector = when (name) {
    "check" -> Icons.Filled.Check
    "close" -> Icons.Filled.Close
    "mic" -> Icons.Filled.Mic
    "add" -> Icons.Filled.Add
    "stop" -> Icons.Filled.Stop
    "cycle" -> Icons.Filled.Sync
    "clear" -> Icons.AutoMirrored.Filled.Backspace
    "agent" -> Icons.Filled.AccountCircle
    "up" -> Icons.Filled.ArrowUpward
    "down" -> Icons.Filled.ArrowDownward
    "left" -> Icons.AutoMirrored.Filled.ArrowBack
    "right" -> Icons.AutoMirrored.Filled.ArrowForward
    "focus" -> Icons.Filled.CenterFocusStrong
    "touch" -> Icons.Filled.TouchApp
    "review" -> Icons.Filled.RateReview
    "debug" -> Icons.Filled.BugReport
    "refactor" -> Icons.Filled.Build
    "test" -> Icons.Filled.Science
    else -> Icons.Filled.CenterFocusStrong
}

private fun directionIcon(inputId: String): ImageVector = when {
    inputId.endsWith("_up") -> Icons.Filled.ArrowUpward
    inputId.endsWith("_down") -> Icons.Filled.ArrowDownward
    inputId.endsWith("_left") -> Icons.AutoMirrored.Filled.ArrowBack
    else -> Icons.AutoMirrored.Filled.ArrowForward
}

private fun parseProfileColor(value: String?): Color = runCatching {
    Color(android.graphics.Color.parseColor(value))
}.getOrDefault(CompleteColor)
