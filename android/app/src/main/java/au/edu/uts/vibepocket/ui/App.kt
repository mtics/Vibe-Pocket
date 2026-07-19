package au.edu.uts.vibepocket.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.view.HapticFeedbackConstants
import au.edu.uts.vibepocket.hid.Keyboard
import au.edu.uts.vibepocket.connection.Invitation
import au.edu.uts.vibepocket.input.Dispatch
import au.edu.uts.vibepocket.input.remote
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.session.Feedback
import au.edu.uts.vibepocket.session.Session
import au.edu.uts.vibepocket.ui.control.Screen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun App(viewModel: Session) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val context = LocalContext.current
    val hidController = remember(context) { Keyboard(context) }
    val inputOrchestrator = remember(hidController, viewModel) {
        Dispatch(
            hid = hidController,
            bridge = remote(viewModel),
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
                    inputOrchestrator.release()
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
            inputOrchestrator.release()
            hidController.close()
            viewModel.setForeground(false)
        }
    }

    LaunchedEffect(viewModel, view) {
        viewModel.feedback.collect { feedback ->
            val constant = when (feedback) {
                Feedback.Success -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                }
                Feedback.Error -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.REJECT
                } else {
                    HapticFeedbackConstants.LONG_PRESS
                }
            }
            view.performHapticFeedback(constant)
        }
    }

    LaunchedEffect(state.error, state.config) {
        val message = state.error
        if (state.config != null && !message.isNullOrBlank()) snackbar.showSnackbar(message)
    }

    if (state.config == null) {
        Box(Modifier.fillMaxSize()) {
            Connect(
                onInvitation = viewModel::offer,
                error = state.error,
                isConnecting = state.inFlightIds.any { it.startsWith("connection:") },
            )
            state.invitation?.let { invitation ->
                PairingDialog(
                    invitation = invitation,
                    busy = state.inFlightIds.any { it.startsWith("connection:") },
                    onConfirm = viewModel::pair,
                    onDismiss = viewModel::dismissPairing,
                )
            }
        }
        return
    }

    val dispatchInput: (String, Gesture.Kind) -> Boolean = { inputId, gesture ->
        inputOrchestrator.activate(state.snapshot, inputId, gesture)
    }
    val onInput: (String, Gesture.Kind) -> Unit = { inputId, gesture ->
        if (dispatchInput(inputId, gesture)) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
    val onNavigationRepeat: (String, Boolean) -> Unit = { inputId, initial ->
        if (initial) {
            if (inputOrchestrator.startRepeat(state.snapshot, inputId)) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        } else {
            inputOrchestrator.stopRepeat()
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
        if (inputOrchestrator.openModel(state.snapshot)) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
    val onLayer: (String) -> Boolean = { layerId ->
        viewModel.selectLayer(layerId).also { selected ->
            if (selected) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    val onPairHid: () -> Unit = {
        pairRequested = true
        when {
            !hidController.hasPermissions() -> nearbyPermissionLauncher.launch(bluetoothPermissions)
            !hidState.enabled -> bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> launchDiscoverable()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Vibe Pocket",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Open settings")
                    }
                    IconButton(onClick = viewModel::refresh, enabled = !state.isRefreshing) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh controller state")
                        }
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
                Loading(isRefreshing = state.isRefreshing, error = state.error, onRefresh = viewModel::refresh)
            } else {
                Screen(
                    snapshot = snapshot,
                    hidNavigationAvailable = hidState.connected,
                    inFlightIds = state.inFlightIds,
                    onInput = onInput,
                    onNavigationRepeat = onNavigationRepeat,
                    onVoiceStart = onVoiceStart,
                    onVoiceStop = onVoiceStop,
                    onAgent = onAgent,
                    onModel = onModelPicker,
                    onLayer = onLayer,
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset controller profile?") },
            text = { Text("All six layers, mappings, colors, and workflow prompts will return to their defaults.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (viewModel.resetProfile()) confirmReset = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }

    if (showSettings) {
        Settings(
            config = requireNotNull(state.config),
            snapshot = state.snapshot,
            hidState = hidState,
            inFlightIds = state.inFlightIds,
            connectionError = state.error,
            onDismiss = { showSettings = false },
            onSaveConnection = viewModel::connect,
            onDisconnect = viewModel::disconnect,
            onResetProfile = { confirmReset = true },
            onPairHid = onPairHid,
            onConnectHid = hidController::connect,
            onRefreshHid = {
                if (hidController.hasPermissions()) hidController.start()
                hidController.refreshHosts()
            },
            onLayer = onLayer,
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
        )
    }

    state.invitation?.let { invitation ->
        PairingDialog(
            invitation = invitation,
            busy = state.inFlightIds.any { it.startsWith("connection:") },
            onConfirm = viewModel::pair,
            onDismiss = viewModel::dismissPairing,
        )
    }
}

@Composable
private fun PairingDialog(
    invitation: Invitation,
    busy: Boolean,
    onConfirm: () -> Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with this Mac?") },
        text = { Text(invitation.origin.removePrefix("https://")) },
        confirmButton = {
            TextButton(onClick = { onConfirm() }, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Pair")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
