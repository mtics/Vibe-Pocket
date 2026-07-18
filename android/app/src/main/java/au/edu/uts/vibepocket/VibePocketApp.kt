package au.edu.uts.vibepocket

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
private val LayerColorChoices = listOf(
    "#F4F4F2", "#A020F0", "#25D9E8", "#FF8C24",
    "#FF4F9A", "#FFE04A", "#55D6A4", "#FF776B",
)

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
    val context = LocalContext.current
    val hidController = remember(context) { BluetoothHidKeyboardController(context) }
    val inputOrchestrator = remember(hidController, viewModel) {
        ControllerInputOrchestrator(
            hid = hidController,
            bridge = PocketViewModelBridgeTransport(viewModel),
            onHidAction = viewModel::applyLocalHidAction,
        )
    }
    val hidState by hidController.state.collectAsStateWithLifecycle()
    var pairRequested by remember { mutableStateOf(false) }
    val discoverableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        pairRequested = false
        hidController.refreshHosts()
    }
    val launchDiscoverable = {
        hidController.start()
        discoverableLauncher.launch(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300),
        )
    }
    val bluetoothEnableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        hidController.start()
        if (pairRequested && hidController.state.value.enabled) launchDiscoverable()
    }
    val bluetoothPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyArray()
        }
    }
    val nearbyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (bluetoothPermissions.all { grants[it] == true }) {
            hidController.start()
            if (pairRequested) {
                if (hidController.state.value.enabled) launchDiscoverable()
                else bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        } else {
            pairRequested = false
            viewModel.reportLocalError("Nearby devices permission is required for Bluetooth keyboard.")
        }
    }

    LaunchedEffect(hidController) {
        if (hidController.hasPermissions()) hidController.start()
    }

    DisposableEffect(lifecycleOwner, viewModel, inputOrchestrator) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setForeground(true)
                Lifecycle.Event.ON_STOP -> {
                    inputOrchestrator.releaseHeldInput()
                    viewModel.setForeground(false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.setForeground(true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            inputOrchestrator.releaseHeldInput()
            hidController.close()
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

    val dispatchInput: (String, ControllerGesture) -> Boolean = { inputId, gesture ->
        inputOrchestrator.activate(state.snapshot, inputId, gesture)
    }
    val onInput: (String, ControllerGesture) -> Unit = { inputId, gesture ->
        if (dispatchInput(inputId, gesture)) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
    val onNavigationRepeat: (String, Boolean) -> Unit = { inputId, initial ->
        if (initial) {
            if (inputOrchestrator.startNavigationRepeat(state.snapshot, inputId)) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        } else {
            inputOrchestrator.stopNavigationRepeat()
        }
    }
    val onVoiceStart: (String) -> Boolean = { inputId ->
        val started = inputOrchestrator.startVoice(state.snapshot, inputId)
        started.also {
            if (it) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
    val onVoiceStop: (String) -> Unit = { inputId ->
        inputOrchestrator.stopVoice(inputId)
    }
    val onAgent: (String) -> Unit = { agentId ->
        if (viewModel.focusAgent(agentId)) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
    val onModelPicker: () -> Unit = {
        if (inputOrchestrator.openModelPicker(state.snapshot)) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
    val onLayer: (String) -> Boolean = { layerId ->
        viewModel.selectLayer(layerId).also { selected ->
            if (selected) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Vibe Pocket", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (state.snapshot?.controller?.actionCatalog?.isNotEmpty() == true) {
                        IconButton(onClick = { showMappings = true }, enabled = state.inFlightIds.isEmpty()) {
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
                    IconButton(onClick = {
                        viewModel.disconnect()
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Disconnect bridge")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val snapshot = state.snapshot
            if (snapshot == null) {
                LoadingScreen(isRefreshing = state.isRefreshing, onRefresh = viewModel::refresh)
            } else {
                ControllerScreen(
                    snapshot = snapshot,
                    statusMessage = state.error ?: snapshot.status.message,
                    hidState = hidState,
                    inFlightIds = state.inFlightIds,
                    onPairHid = {
                        pairRequested = true
                        when {
                            !hidController.hasPermissions() -> nearbyPermissionLauncher.launch(bluetoothPermissions)
                            !hidState.enabled -> bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            else -> launchDiscoverable()
                        }
                    },
                    onConnectHid = hidController::connect,
                    onRefreshHid = {
                        if (hidController.hasPermissions()) hidController.start()
                        hidController.refreshHosts()
                    },
                    onInput = onInput,
                    onNavigationRepeat = onNavigationRepeat,
                    onVoiceStart = onVoiceStart,
                    onVoiceStop = onVoiceStop,
                    onAgent = onAgent,
                    onModelPicker = onModelPicker,
                    onLayer = onLayer,
                )
            }
        }
    }

    val controller = state.snapshot?.controller
    if (showMappings && controller?.profile != null && controller.actionCatalog.isNotEmpty()) {
        MappingSheet(
            snapshot = requireNotNull(state.snapshot),
            inFlightIds = state.inFlightIds,
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
            onColor = viewModel::updateLayerColor,
            onWorkflow = viewModel::updateWorkflowPrompt,
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
    statusMessage: String?,
    hidState: HidKeyboardState,
    inFlightIds: Set<String>,
    onPairHid: () -> Unit,
    onConnectHid: (String) -> Boolean,
    onRefreshHid: () -> Unit,
    onInput: (String, ControllerGesture) -> Unit,
    onNavigationRepeat: (String, Boolean) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    onAgent: (String) -> Unit,
    onModelPicker: () -> Unit,
    onLayer: (String) -> Boolean,
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
    val layerScope = rememberCoroutineScope()
    var layerModifierPressed by remember { mutableStateOf(false) }
    var layerGuardActive by remember { mutableStateOf(false) }

    fun armLayerGuard() {
        layerGuardActive = true
        layerScope.launch {
            delay(LAYER_SHIFT_GUARD_MILLIS)
            layerGuardActive = false
        }
    }

    fun routeLayerInput(inputId: String, gesture: ControllerGesture): Boolean = when (
        val route = routeLayerShift(inputId, gesture, layerModifierPressed, layerGuardActive)
    ) {
        LayerShiftRoute.Pass -> false
        LayerShiftRoute.Suppress -> true
        is LayerShiftRoute.Select -> {
            if (onLayer(route.layerId)) armLayerGuard()
            true
        }
    }

    val routedInput: (String, ControllerGesture) -> Unit = { inputId, gesture ->
        if (!routeLayerInput(inputId, gesture)) onInput(inputId, gesture)
    }
    val routedVoiceStart: (String) -> Boolean = { inputId ->
        if (routeLayerInput(inputId, ControllerGesture.TAP)) false else onVoiceStart(inputId)
    }
    val routedNavigationRepeat: (String, Boolean) -> Unit = { inputId, initial ->
        if (!initial || !routeLayerInput(inputId, ControllerGesture.TAP)) {
            onNavigationRepeat(inputId, initial)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TaskStatusStrip(snapshot, statusMessage)
        snapshot.controller?.userInput?.let { CodexQuestionPanel(it) }
        SectionLabel("Agents")
        AgentGrid(
            snapshot = snapshot,
            inFlightIds = inFlightIds,
            onAgentClick = onAgent,
        )
        controller?.profile?.layers?.take(6)?.let { layers ->
            SectionLabel("Layers")
            LayerSelector(
                layers = layers,
                activeLayerId = controller.activeLayerId,
                inFlightIds = inFlightIds,
                enabled = snapshot.status.state == "ready",
                onLayer = onLayer,
                modifierPressed = layerModifierPressed,
                onModifierPressed = { layerModifierPressed = it },
            )
        }
        ModeAndFocus(
            mode = controller?.mode ?: SelectorStatus(false, "Unavailable"),
            access = controller?.access ?: SelectorStatus(false, "Unavailable"),
            touchInput = touchInput,
            snapshot = snapshot,
            inFlightIds = inFlightIds,
            onInput = routedInput,
            onVoiceStart = routedVoiceStart,
            onVoiceStop = onVoiceStop,
            layerModifierPressed = layerModifierPressed,
            inputBlocked = layerGuardActive,
        )
        SectionLabel("Command keys")
        CommandKeyGrid(
            inputs = keyInputs,
            snapshot = snapshot,
            hidNavigationAvailable = hidState.connected,
            inFlightIds = inFlightIds,
            onInput = routedInput,
            onNavigationRepeat = routedNavigationRepeat,
            onVoiceStart = routedVoiceStart,
            onVoiceStop = onVoiceStop,
            layerModifierPressed = layerModifierPressed,
            inputBlocked = layerGuardActive,
            onLayerChord = { inputId -> routeLayerInput(inputId, ControllerGesture.TAP) },
        )
        if (joystickInputs.isNotEmpty()) {
            SectionLabel("Workflows")
            WorkflowJoystick(
                inputs = joystickInputs,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onGesture = routedInput,
                onVoiceStart = routedVoiceStart,
                onVoiceStop = onVoiceStop,
                inputBlocked = layerGuardActive,
            )
        }
        if (dialInputs.isNotEmpty()) {
            SectionLabel("Reasoning")
            ReasoningDial(
                inputs = dialInputs,
                reasoning = controller?.reasoning ?: ReasoningStatus.Unavailable,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onInput = routedInput,
                onVoiceStart = routedVoiceStart,
                onVoiceStop = onVoiceStop,
                onOpenModelPicker = onModelPicker,
                inputBlocked = layerGuardActive,
            )
        }
        VirtualHardwarePanel(
            state = hidState,
            onPair = onPairHid,
            onConnect = onConnectHid,
            onRefresh = onRefreshHid,
        )
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun VirtualHardwarePanel(
    state: HidKeyboardState,
    onPair: () -> Unit,
    onConnect: (String) -> Boolean,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (state.connected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                contentDescription = null,
                tint = if (state.connected) CompleteColor else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(21.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Virtual hardware", fontWeight = FontWeight.SemiBold)
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh Bluetooth hosts")
            }
            FilledTonalButton(
                onClick = onPair,
                enabled = state.supported,
                shape = RoundedCornerShape(6.dp),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Pair")
            }
        }
        state.pairedHosts.take(4).forEach { host ->
            val connected = host.address == state.connectedHostAddress
            val connecting = host.address == state.connectingHostAddress
            FilledTonalButton(
                onClick = { onConnect(host.address) },
                enabled = state.registered && !connecting,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (connected) CompleteColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                if (connecting) {
                    CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (connected) Icons.Default.Check else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(host.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CodexQuestionPanel(question: CodexQuestion) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(WaitingColor.copy(alpha = 0.09f), RoundedCornerShape(6.dp))
            .border(1.dp, WaitingColor.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.HourglassTop, contentDescription = null, tint = WaitingColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(question.header, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                "${question.questionIndex + 1}/${question.questionCount}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Text(question.question, style = MaterialTheme.typography.bodyMedium)
        question.options.forEachIndexed { index, option ->
            val selected = index == question.selectedOptionIndex && !question.hasSpokenAnswer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (selected) WaitingColor.copy(alpha = 0.16f) else Color.Transparent,
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (selected) Icons.Default.Check else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) WaitingColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(option.label, style = MaterialTheme.typography.labelLarge)
                    if (option.description.isNotEmpty()) {
                        Text(
                            option.description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (question.hasSpokenAnswer) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = WaitingColor, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (question.isSecret) "Private spoken answer ready" else "Spoken answer ready",
                    color = WaitingColor,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun TaskStatusStrip(snapshot: PocketSnapshot, message: String?) {
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
        message?.let { text ->
            Spacer(Modifier.width(10.dp))
            Text(
                text,
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
    inFlightIds: Set<String>,
    onAgentClick: (String) -> Unit,
) {
    val slots = snapshot.agentSlots()
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(slots.size) {
        if (slots.size <= COLLAPSED_AGENT_COUNT) expanded = false
    }
    val visibleSlots = if (expanded) slots else slots.take(COLLAPSED_AGENT_COUNT)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (visibleSlots.isEmpty()) {
            Text(
                "No active Codex tasks detected",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        visibleSlots.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { slot ->
                    val agent = slot.agent
                    AgentTile(
                        agent = agent,
                        focused = slot.focused,
                        enabled = agent != null && "agent:${agent.id}" !in inFlightIds && slot.canFocus,
                        loading = agent != null && "agent:${agent.id}" in inFlightIds,
                        onClick = { agent?.id?.let(onAgentClick) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f).height(58.dp))
                }
            }
        }
        if (slots.size > COLLAPSED_AGENT_COUNT) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (expanded) "Show fewer" else "Show ${slots.size - COLLAPSED_AGENT_COUNT} more")
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
    inFlightIds: Set<String>,
    enabled: Boolean,
    onLayer: (String) -> Boolean,
    modifierPressed: Boolean,
    onModifierPressed: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LayerModifierKey(
            pressed = modifierPressed,
            enabled = enabled,
            onPressed = onModifierPressed,
            modifier = Modifier.weight(1f),
        )
        layers.forEachIndexed { index, layer ->
            val selected = layer.id == activeLayerId
            val layerColor = parseProfileColor(layer.color)
            val loading = "layer:${layer.id}" in inFlightIds
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) layerColor.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surface)
                    .border(if (selected) 2.dp else 1.dp, layerColor.copy(alpha = if (selected) 1f else 0.45f), RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled && !selected && !loading, onClick = { onLayer(layer.id) }),
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
private fun LayerModifierKey(
    pressed: Boolean,
    enabled: Boolean,
    onPressed: (Boolean) -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (pressed) MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f) else MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = if (pressed) 1f else 0.52f), RoundedCornerShape(6.dp))
            .semantics { contentDescription = "Layer shift modifier" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onPressed(true)
                    try {
                        var stillPressed = true
                        while (stillPressed) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            stillPressed = change?.pressed == true
                        }
                    } finally {
                        onPressed(false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("L1", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun ModeAndFocus(
    mode: SelectorStatus,
    access: SelectorStatus,
    touchInput: ControllerInput?,
    snapshot: PocketSnapshot,
    inFlightIds: Set<String>,
    onInput: (String, ControllerGesture) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    layerModifierPressed: Boolean,
    inputBlocked: Boolean,
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
        Column(Modifier.weight(1f)) {
            Text("Access", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text(
                access.label.ifBlank { "Unavailable" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
        }
        if (touchInput != null) {
            GestureControl(
                input = touchInput,
                snapshot = snapshot,
                inFlightIds = inFlightIds,
                onInput = onInput,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                layerModifierPressed = layerModifierPressed,
                inputBlocked = inputBlocked,
                modifier = Modifier.width(142.dp).height(54.dp),
            )
        }
    }
}

@Composable
private fun CommandKeyGrid(
    inputs: List<ControllerInput>,
    snapshot: PocketSnapshot,
    hidNavigationAvailable: Boolean,
    inFlightIds: Set<String>,
    onInput: (String, ControllerGesture) -> Unit,
    onNavigationRepeat: (String, Boolean) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    layerModifierPressed: Boolean,
    inputBlocked: Boolean,
    onLayerChord: (String) -> Boolean,
) {
    inputs.chunked(3).forEach { rowInputs ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowInputs.forEach { input ->
                CommandKey(
                    input = input,
                    snapshot = snapshot,
                    hidNavigationAvailable = hidNavigationAvailable,
                    inFlightIds = inFlightIds,
                    onInput = onInput,
                    onNavigationRepeat = onNavigationRepeat,
                    onVoiceStart = onVoiceStart,
                    onVoiceStop = onVoiceStop,
                    layerModifierPressed = layerModifierPressed,
                    inputBlocked = inputBlocked,
                    onLayerChord = onLayerChord,
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
    hidNavigationAvailable: Boolean,
    inFlightIds: Set<String>,
    onInput: (String, ControllerGesture) -> Unit,
    onNavigationRepeat: (String, Boolean) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    layerModifierPressed: Boolean,
    inputBlocked: Boolean,
    onLayerChord: (String) -> Boolean,
    modifier: Modifier,
) {
    GestureControl(
        input = input,
        snapshot = snapshot,
        inFlightIds = inFlightIds,
        onInput = onInput,
        navigationRepeatEnabled = snapshot.supportsHidNavigationRepeat(input.id, hidNavigationAvailable),
        onNavigationRepeat = onNavigationRepeat,
        onVoiceStart = onVoiceStart,
        onVoiceStop = onVoiceStop,
        layerModifierPressed = layerModifierPressed,
        inputBlocked = inputBlocked,
        onLayerChord = onLayerChord,
        modifier = modifier.height(66.dp),
    )
}

@Composable
private fun GestureControl(
    input: ControllerInput,
    snapshot: PocketSnapshot,
    inFlightIds: Set<String>,
    onInput: (String, ControllerGesture) -> Unit,
    navigationRepeatEnabled: Boolean = false,
    onNavigationRepeat: (String, Boolean) -> Unit = { _, _ -> },
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    layerModifierPressed: Boolean = false,
    inputBlocked: Boolean = false,
    onLayerChord: (String) -> Boolean = { false },
    modifier: Modifier,
) {
    val enabledGestures = ControllerGesture.entries.filter { snapshot.inputEnabled(input.id, it) }
    val voiceTap = snapshot.voiceTapEnabled(input.id)
    val tapEnabled = ControllerGesture.TAP in enabledGestures
    val doubleTapEnabled = ControllerGesture.DOUBLE_TAP in enabledGestures
    val holdEnabled = ControllerGesture.HOLD in enabledGestures
    val inputPending = inFlightIds.any { it.startsWith("input:${input.id}:") } &&
        !snapshot.inputAllowsQueuedRepeat(input.id)
    val effectiveVoiceTap = voiceTap && !layerModifierPressed
    val voicePressEnabled = effectiveVoiceTap && !inputPending && !inputBlocked
    val interactive = !inputBlocked && (effectiveVoiceTap || enabledGestures.isNotEmpty())
    val currentVoiceStart by rememberUpdatedState(onVoiceStart)
    val currentVoiceStop by rememberUpdatedState(onVoiceStop)
    val currentNavigationRepeat by rememberUpdatedState(onNavigationRepeat)
    val currentLayerChord by rememberUpdatedState(onLayerChord)
    val repeatNavigation = navigationRepeatEnabled && !inputPending && !inputBlocked && !layerModifierPressed
    val layerChordEnabled = layerModifierPressed && !inputBlocked && isLayerShiftTarget(input.id)
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
            .pointerInput(input.id, layerChordEnabled) {
                if (!layerChordEnabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!currentLayerChord(input.id)) return@awaitEachGesture
                    down.consume()
                    var pressed = true
                    while (pressed) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        pressed = change?.pressed == true
                        change?.consume()
                    }
                }
            }
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
            .pointerInput(input.id, repeatNavigation) {
                if (!repeatNavigation) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    currentNavigationRepeat(input.id, true)
                    try {
                        var pressed = true
                        while (pressed) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            pressed = change?.pressed == true
                            change?.consume()
                        }
                    } finally {
                        currentNavigationRepeat(input.id, false)
                    }
                }
            }
            .combinedClickable(
                enabled = !effectiveVoiceTap && enabledGestures.isNotEmpty() && !inputPending && !inputBlocked && !repeatNavigation,
                onClick = {
                    if (!effectiveVoiceTap && tapEnabled) {
                        onInput(input.id, ControllerGesture.TAP)
                    }
                },
                onDoubleClick = if (doubleTapEnabled) {
                    { onInput(input.id, ControllerGesture.DOUBLE_TAP) }
                } else null,
                onLongClick = if (holdEnabled) {
                    { onInput(input.id, ControllerGesture.HOLD) }
                } else null,
                onLongClickLabel = if (holdEnabled) "Run hold binding" else null,
            )
            .alpha(if (interactive) 1f else 0.38f)
            .padding(horizontal = 7.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(inputIcon(input.icon), contentDescription = null, modifier = Modifier.size(20.dp))
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
    inFlightIds: Set<String>,
    onGesture: (String, ControllerGesture) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    inputBlocked: Boolean,
) {
    val byDirection = remember(inputs) { inputs.associateBy { it.id.substringAfterLast('_') } }
    val enabledIds = inputs.filter { input ->
        !inputBlocked && !inFlightIds.any { it.startsWith("input:${input.id}:") }
            && ControllerGesture.entries.any { snapshot.inputEnabled(input.id, it) }
    }.mapTo(mutableSetOf(), ControllerInput::id)
    val voiceIds = inputs.filter {
        !inputBlocked && snapshot.voiceTapEnabled(it.id) && !inFlightIds.any { pending -> pending.startsWith("input:${it.id}:") }
    }.mapTo(mutableSetOf(), ControllerInput::id)
    var selectedId by remember { mutableStateOf<String?>(null) }
    val currentOnGesture by rememberUpdatedState(onGesture)
    val currentVoiceStart by rememberUpdatedState(onVoiceStart)
    val currentVoiceStop by rememberUpdatedState(onVoiceStop)
    val gestureScope = rememberCoroutineScope()
    val viewConfiguration = LocalViewConfiguration.current
    val pendingTapJobs = remember { mutableMapOf<String, Job>() }
    val releaseArbiter = remember(viewConfiguration.doubleTapTimeoutMillis) {
        GestureReleaseArbiter(viewConfiguration.doubleTapTimeoutMillis)
    }

    fun dispatchRelease(inputId: String, downAt: Long, releasedAt: Long) {
        when (val decision = releaseArbiter.release(
            inputId = inputId,
            downAt = downAt,
            releasedAt = releasedAt,
            tapEnabled = snapshot.inputEnabled(inputId, ControllerGesture.TAP),
            doubleTapEnabled = snapshot.inputEnabled(inputId, ControllerGesture.DOUBLE_TAP),
            holdEnabled = snapshot.inputEnabled(inputId, ControllerGesture.HOLD),
            longPressTimeoutMillis = viewConfiguration.longPressTimeoutMillis,
        )) {
            is GestureReleaseDecision.Dispatch -> {
                pendingTapJobs.remove(inputId)?.cancel()
                currentOnGesture(inputId, decision.gesture)
            }
            is GestureReleaseDecision.DeferTap -> {
                pendingTapJobs.remove(inputId)?.cancel()
                pendingTapJobs[inputId] = gestureScope.launch {
                    delay(viewConfiguration.doubleTapTimeoutMillis)
                    pendingTapJobs.remove(inputId)
                    releaseArbiter.completeDeferredTap(inputId, decision.token)?.let { gesture ->
                        currentOnGesture(inputId, gesture)
                    }
                }
            }
            GestureReleaseDecision.None -> pendingTapJobs.remove(inputId)?.cancel()
        }
    }
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
                .pointerInput(enabledIds, voiceIds, inputs, inputBlocked) {
                    if (inputBlocked) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downAt = down.uptimeMillis
                        var candidate = joystickInputAt(down.position.x, down.position.y, size, byDirection)
                            ?.takeIf { it.id in enabledIds }
                        var candidateStartedAt = downAt
                        selectedId = candidate?.id
                        var voiceCandidateId = candidate?.id?.takeIf { it in voiceIds }
                        var activeVoiceId = voiceCandidateId?.takeIf { currentVoiceStart(it) }
                        var releasedAt = downAt
                        try {
                            var pressed = true
                            while (pressed) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                releasedAt = change.uptimeMillis
                                val nextCandidate = joystickInputAt(change.position.x, change.position.y, size, byDirection)
                                    ?.takeIf { it.id in enabledIds }
                                if (nextCandidate?.id != candidate?.id) candidateStartedAt = change.uptimeMillis
                                candidate = nextCandidate
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
                            if (releaseId != null && releaseId !in voiceIds) {
                                dispatchRelease(releaseId, candidateStartedAt, releasedAt)
                            }
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
    reasoning: ReasoningStatus,
    snapshot: PocketSnapshot,
    inFlightIds: Set<String>,
    onInput: (String, ControllerGesture) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    onOpenModelPicker: () -> Unit,
    inputBlocked: Boolean,
) {
    val counterClockwise = inputs.firstOrNull { it.id.endsWith("ccw") }
    val clockwise = inputs.firstOrNull { it.id.endsWith("cw") && !it.id.endsWith("ccw") }
    val counterClockwiseEnabled = counterClockwise?.let { snapshot.inputEnabled(it.id, ControllerGesture.TAP) } == true
    val clockwiseEnabled = clockwise?.let { snapshot.inputEnabled(it.id, ControllerGesture.TAP) } == true
    val inputPending = inputs.any { input ->
        inFlightIds.any { pending -> pending.startsWith("input:${input.id}:") } &&
            !snapshot.inputAllowsQueuedRepeat(input.id)
    }
    val rotationEnabled = !inputBlocked && !inputPending && (counterClockwiseEnabled || clockwiseEnabled)
    val currentOnInput by rememberUpdatedState(onInput)
    val currentOnOpenModelPicker by rememberUpdatedState(onOpenModelPicker)
    val view = LocalView.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val modelPickerEnabled = !inputBlocked && !inputPending &&
        snapshot.controller?.desktopFocused == true &&
        snapshot.controller.userInput == null &&
        reasoning.available
    var rotationDegrees by remember { mutableStateOf(0f) }
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
            inFlightIds,
            onInput,
            onVoiceStart,
            onVoiceStop,
            inputBlocked,
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
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .semantics {
                            contentDescription = "Reasoning dial. Tap to choose a model, rotate to adjust reasoning."
                        }
                        .pointerInput(rotationEnabled, modelPickerEnabled, clockwise?.id, counterClockwise?.id) {
                            if (!rotationEnabled && !modelPickerEnabled) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val minimumRadius = min(size.width, size.height) * DIAL_ROTATION_DEAD_ZONE_FRACTION
                                var dialState = beginDialRotation(
                                    down.position.x,
                                    down.position.y,
                                    size.width / 2f,
                                    size.height / 2f,
                                    minimumRadius,
                                )
                                var moved = false
                                var stepped = false
                                var released = false
                                try {
                                    var pressed = true
                                    while (pressed) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        if (
                                            abs(change.position.x - down.position.x) > touchSlop ||
                                            abs(change.position.y - down.position.y) > touchSlop
                                        ) {
                                            moved = true
                                        }
                                        if (rotationEnabled) {
                                            val update = advanceDialRotation(
                                                dialState,
                                                change.position.x,
                                                change.position.y,
                                                size.width / 2f,
                                                size.height / 2f,
                                                minimumRadius,
                                            )
                                            dialState = update.state
                                            rotationDegrees += Math.toDegrees(update.deltaRadians).toFloat()
                                            when (update.step) {
                                                1 -> clockwise?.id?.let { inputId ->
                                                    currentOnInput(inputId, ControllerGesture.TAP)
                                                }
                                                -1 -> counterClockwise?.id?.let { inputId ->
                                                    currentOnInput(inputId, ControllerGesture.TAP)
                                                }
                                            }
                                            if (update.step != 0) {
                                                stepped = true
                                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            }
                                        }
                                        pressed = change.pressed
                                        if (!pressed) released = true
                                        change.consume()
                                    }
                                } finally {
                                    rotationDegrees %= 360f
                                }
                                if (released && !moved && !stepped && modelPickerEnabled) {
                                    currentOnOpenModelPicker()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .size(34.dp)
                            .graphicsLayer { rotationZ = rotationDegrees },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (reasoning.modelLabel.isNotBlank()) {
                Text(
                    reasoning.modelLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    reasoning.level?.displayLabel ?: reasoning.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            } else {
                Text(
                    reasoning.label.ifBlank { if (reasoning.available) "Reasoning" else "Unavailable" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        DialStepButton(
            clockwise,
            Icons.Filled.Add,
            snapshot,
            inFlightIds,
            onInput,
            onVoiceStart,
            onVoiceStop,
            inputBlocked,
        )
    }
}

private const val COLLAPSED_AGENT_COUNT = 6

@Composable
private fun DialStepButton(
    input: ControllerInput?,
    icon: ImageVector,
    snapshot: PocketSnapshot,
    inFlightIds: Set<String>,
    onInput: (String, ControllerGesture) -> Unit,
    onVoiceStart: (String) -> Boolean,
    onVoiceStop: (String) -> Unit,
    inputBlocked: Boolean,
) {
    val enabledGestures = if (input == null) emptyList() else {
        ControllerGesture.entries.filter { snapshot.inputEnabled(input.id, it) }
    }
    val inputPending = input?.let { candidate ->
        inFlightIds.any { it.startsWith("input:${candidate.id}:") } &&
            !snapshot.inputAllowsQueuedRepeat(candidate.id)
    } == true
    val baseEnabled = !inputBlocked && input != null && enabledGestures.isNotEmpty()
    val enabled = baseEnabled && !inputPending
    val voiceTap = input?.let { snapshot.voiceTapEnabled(it.id) } == true
    val tapEnabled = ControllerGesture.TAP in enabledGestures
    val doubleTapEnabled = ControllerGesture.DOUBLE_TAP in enabledGestures
    val holdEnabled = ControllerGesture.HOLD in enabledGestures
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
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(voiceModifier)
            .combinedClickable(
                enabled = enabled && !voiceTap,
                onClick = {
                    if (tapEnabled) {
                        input?.id?.let { onInput(it, ControllerGesture.TAP) }
                    }
                },
                onDoubleClick = if (doubleTapEnabled) {
                    {
                        input?.id?.let { onInput(it, ControllerGesture.DOUBLE_TAP) }
                    }
                } else null,
                onLongClick = if (holdEnabled) {
                    {
                        input?.id?.let { onInput(it, ControllerGesture.HOLD) }
                    }
                } else null,
                onLongClickLabel = if (holdEnabled) "Run hold binding" else null,
            )
            .alpha(if (baseEnabled) 1f else 0.38f),
    ) {
        Icon(icon, contentDescription = input?.label, modifier = Modifier.size(28.dp))
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
    inFlightIds: Set<String>,
    onDismiss: () -> Unit,
    onUpdate: (String, String, ControllerGesture, String) -> Unit,
    onClear: (String, String, ControllerGesture) -> Unit,
    onRename: (String, String) -> Boolean,
    onColor: (String, String) -> Boolean,
    onWorkflow: (String, String) -> Boolean,
    onReset: () -> Boolean,
) {
    val controller = snapshot.controller ?: return
    val profile = controller.profile ?: return
    val layer = profile.layers.firstOrNull { it.id == controller.activeLayerId } ?: profile.layers.firstOrNull() ?: return
    var target by remember { mutableStateOf<MappingTarget?>(null) }
    var layerName by rememberSaveable(layer.id, layer.name) { mutableStateOf(layer.name) }
    var confirmReset by remember { mutableStateOf(false) }
    val busy = inFlightIds.any { pending ->
        pending.startsWith("mapping:")
            || pending.startsWith("rename:")
            || pending.startsWith("color:")
            || pending.startsWith("workflow:")
            || pending == "reset-profile"
    }

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
            Text("Layer color", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(7.dp))
            LayerColorChoices.chunked(4).forEach { colors ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    colors.forEach { color ->
                        val parsed = parseProfileColor(color)
                        val selected = layer.color.equals(color, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(parsed)
                                .border(
                                    if (selected) 3.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                    CircleShape,
                                )
                                .semantics { contentDescription = "Layer color $color" }
                                .clickable(enabled = !busy && !selected, onClick = { onColor(layer.id, color) }),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(14.dp))
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
            Spacer(Modifier.height(14.dp))
            Text("Workflow prompts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            profile.workflows.forEach { workflow ->
                WorkflowPromptEditor(
                    workflow = workflow,
                    busy = busy,
                    onSave = { prompt -> onWorkflow(workflow.id, prompt) },
                )
                Spacer(Modifier.height(10.dp))
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
private fun WorkflowPromptEditor(
    workflow: ControllerWorkflow,
    busy: Boolean,
    onSave: (String) -> Boolean,
) {
    var prompt by rememberSaveable(workflow.id, workflow.prompt) { mutableStateOf(workflow.prompt) }
    val trimmed = prompt.trim()
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        OutlinedTextField(
            value = prompt,
            onValueChange = { if (it.length <= 4_000) prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(workflow.label) },
            minLines = 3,
            maxLines = 6,
        )
        TextButton(
            onClick = { onSave(prompt) },
            enabled = !busy && trimmed.isNotEmpty() && trimmed != workflow.prompt,
            modifier = Modifier.align(Alignment.End),
        ) {
            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("Save")
        }
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
    val pushToTalk = binding?.actions?.get(ControllerGesture.TAP)?.type == "voice"
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
            val gestureEnabled = enabled && (!pushToTalk || gesture == ControllerGesture.TAP)
            Column(
                modifier = Modifier
                    .width(61.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        if (action == null) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.primaryContainer,
                    )
                    .clickable(enabled = gestureEnabled, onClick = { onSelect(gesture) })
                    .alpha(if (gestureEnabled) 1f else 0.38f)
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
                catalog.filter { entry ->
                    target.gesture == ControllerGesture.TAP || entry.action.type != "voice"
                }.forEach { entry ->
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
private fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
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
