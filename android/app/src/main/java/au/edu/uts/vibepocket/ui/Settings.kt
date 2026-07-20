package au.edu.uts.vibepocket.ui

import au.edu.uts.vibepocket.connection.Config
import au.edu.uts.vibepocket.control.Snapshot
import au.edu.uts.vibepocket.hid.Status as HidStatus
import au.edu.uts.vibepocket.profile.Action
import au.edu.uts.vibepocket.profile.Binding
import au.edu.uts.vibepocket.profile.Choice
import au.edu.uts.vibepocket.profile.Gesture
import au.edu.uts.vibepocket.profile.Input
import au.edu.uts.vibepocket.profile.Layer
import au.edu.uts.vibepocket.profile.Profile
import au.edu.uts.vibepocket.profile.Workflow
import au.edu.uts.vibepocket.ui.preference.Hand
import au.edu.uts.vibepocket.ui.preference.Palette
import au.edu.uts.vibepocket.ui.preference.State as Display
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal data class MappingTarget(
    val layerId: String,
    val inputId: String,
    val gesture: Gesture.Kind,
)

internal data class ResolvedMappingTarget(
    val layer: Layer,
    val input: Input,
    val binding: Binding?,
    val action: Action?,
)

internal fun resolveMappingTarget(profile: Profile?, target: MappingTarget): ResolvedMappingTarget? {
    val layer = profile?.layers?.firstOrNull { it.id == target.layerId } ?: return null
    val input = profile.inputs.firstOrNull { it.id == target.inputId } ?: return null
    return ResolvedMappingTarget(
        layer = layer,
        input = input,
        binding = layer.bindings[target.inputId],
        action = layer.bindings[target.inputId]?.actions?.get(target.gesture),
    )
}

internal fun Binding?.allowsVoiceMapping(): Boolean =
    this?.actions.orEmpty().keys.none { it != Gesture.Kind.TAP }

internal fun disconnectMessage(hasPendingActions: Boolean): String = if (hasPendingActions) {
    "Pending controller actions may already have completed. Their recovery records will be discarded with this pairing."
} else {
    "The saved Bridge address and this phone's device credential will be removed."
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun Settings(
    config: Config,
    snapshot: Snapshot?,
    hidState: HidStatus,
    inFlightIds: Set<String>,
    contextTransitionPending: Boolean,
    connectionError: String?,
    display: Display,
    onDismiss: () -> Unit,
    onSaveConnection: (String, String) -> Boolean,
    onDisplay: (Display) -> Boolean,
    onDisconnect: () -> Unit,
    onResetProfile: () -> Unit,
    onPairHid: () -> Unit,
    onConnectHid: (String) -> Boolean,
    onRefreshHid: () -> Unit,
    onLayer: (String) -> Boolean,
    onUpdate: (String, String, Gesture.Kind, String) -> Unit,
    onClear: (String, String, Gesture.Kind) -> Unit,
    onRename: (String, String) -> Boolean,
    onColor: (String, String) -> Boolean,
    onWorkflow: (String, String) -> Boolean,
) {
    val controller = snapshot?.desktop
    val profile = controller?.profile
    val layer = profile?.layers?.firstOrNull { it.id == controller.activeLayerId } ?: profile?.layers?.firstOrNull()
    var url by remember(config.normalizedUrl) { mutableStateOf(config.normalizedUrl) }
    var saveTarget by remember { mutableStateOf<Config?>(null) }
    var target by remember { mutableStateOf<MappingTarget?>(null) }
    var layerName by rememberSaveable(layer?.id, layer?.name) { mutableStateOf(layer?.name.orEmpty()) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var recoveryExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedPalette by rememberSaveable(display.palette) { mutableStateOf(display.palette) }
    var selectedHand by rememberSaveable(display.hand) { mutableStateOf(display.hand) }
    val errorHost = remember { SnackbarHostState() }
    val busy = inFlightIds.any { pending ->
        pending.startsWith("mapping:")
            || pending.startsWith("rename:")
            || pending.startsWith("color:")
            || pending.startsWith("workflow:")
            || pending == "reset-profile"
    } || contextTransitionPending
    val candidate = remember(config, url) {
        runCatching { Config(url, config.credential) }
    }
    val validConfig = candidate.getOrNull()
    val connectionDirty = validConfig != null && validConfig.normalizedUrl != config.normalizedUrl
    val displayDraft = Display(palette = selectedPalette, hand = selectedHand)
    val appearanceDirty = displayDraft != display
    val savingConnection = inFlightIds.any { it.startsWith("connection:") }
    val hasPendingActions = contextTransitionPending || inFlightIds.any { !it.startsWith("connection:") }

    LaunchedEffect(config.normalizedUrl, config.credential, saveTarget) {
        val target = saveTarget ?: return@LaunchedEffect
        if (config.normalizedUrl == target.normalizedUrl && config.credential == target.credential) {
            saveTarget = null
            onDismiss()
        }
    }

    LaunchedEffect(profile, target) {
        val selected = target ?: return@LaunchedEffect
        if (resolveMappingTarget(profile, selected) == null) target = null
    }

    LaunchedEffect(connectionError) {
        connectionError?.takeIf { it.isNotBlank() }?.let { errorHost.showSnackbar(it) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = onResetProfile, enabled = !busy) {
                                Icon(Icons.Default.Restore, contentDescription = "Reset controller profile")
                            }
                            FilledTonalButton(
                                onClick = {
                                    validConfig?.let { candidateConfig ->
                                        val connectionAccepted = !connectionDirty ||
                                            onSaveConnection(candidateConfig.normalizedUrl, candidateConfig.credential)
                                        if (connectionAccepted) {
                                            val appearanceAccepted = !appearanceDirty || onDisplay(displayDraft)
                                            if (appearanceAccepted) {
                                                if (connectionDirty) saveTarget = candidateConfig else onDismiss()
                                            }
                                        }
                                    }
                                },
                                enabled = validConfig != null && settingsHaveChanges(
                                    connectionDirty,
                                    display,
                                    displayDraft,
                                ) && inFlightIds.isEmpty(),
                                modifier = Modifier.padding(end = 8.dp).heightIn(min = 40.dp),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                            ) {
                                if (savingConnection) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("Save")
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                    )
                },
                snackbarHost = { SnackbarHost(errorHost) },
            ) { contentPadding ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(contentPadding).imePadding(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 720.dp)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
            SectionHeader(Icons.Default.Link, "Bridge & pairing")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(config.normalizedUrl.removePrefix("https://"), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { recoveryExpanded = !recoveryExpanded }) {
                    Text(if (recoveryExpanded) "Hide" else "Advanced")
                }
            }
            if (recoveryExpanded) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Bridge URL") },
                )
                candidate.exceptionOrNull()?.message?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (saveTarget != null && !savingConnection) {
                    connectionError?.let { message ->
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f))

            SectionHeader(Icons.Default.Bluetooth, "Virtual hardware")
            Hardware(
                state = hidState,
                onPair = onPairHid,
                onConnect = onConnectHid,
                onRefresh = onRefreshHid,
            )
            Text("Control layout", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Hand.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = selectedHand == option,
                        onClick = { selectedHand = option },
                        shape = SegmentedButtonDefaults.itemShape(index, Hand.entries.size),
                        modifier = Modifier.weight(1f),
                        label = { Text(option.label) },
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f))
            SectionHeader(Icons.Default.Palette, "Appearance")
            Text("Theme", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Palette.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = selectedPalette == option,
                        onClick = { selectedPalette = option },
                        shape = SegmentedButtonDefaults.itemShape(index, Palette.entries.size),
                        modifier = Modifier.weight(1f),
                        label = { Text(option.label) },
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f))
            SectionHeader(Icons.Default.Tune, "Controller profile")
            if (profile == null || layer == null || controller.choices.isEmpty()) {
                Text(
                    "Controller profile unavailable",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                SettingsLayers(
                    layers = profile.layers.take(6),
                    activeLayerId = layer.id,
                    inFlightIds = inFlightIds,
                    enabled = snapshot.status.state == "ready" && !busy,
                    onLayer = onLayer,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = layerName,
                        onValueChange = { if (it.length <= 40) layerName = it },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        singleLine = true,
                        label = { Text("Layer name") },
                    )
                    FilledTonalButton(
                        onClick = { onRename(layer.id, layerName) },
                        enabled = !busy && layerName.trim().isNotEmpty() && layerName.trim() != layer.name,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(6.dp),
                    ) { Text("Rename") }
                }
                Text("Layer color", style = MaterialTheme.typography.labelLarge)
                LayerColors.chunked(4).forEach { colors ->
                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        colors.forEach { color ->
                            val parsed = profileColor(color)
                            val selected = layer.color.equals(color, ignoreCase = true)
                            val selectable = !busy && !selected
                            val ringFallback = if (
                                contrastRatio(Color.Black, parsed) >= contrastRatio(Color.White, parsed)
                            ) {
                                Color.Black
                            } else {
                                Color.White
                            }
                            val ring = contrastingColor(
                                preferred = MaterialTheme.colorScheme.onSurface,
                                background = parsed,
                                fallback = ringFallback,
                                minimumRatio = 3f,
                            )
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics {
                                        role = Role.Button
                                        this.selected = selected
                                        contentDescription = "Layer color $color"
                                        if (!selectable && !selected) disabled()
                                    }
                                    .clickable(enabled = selectable, onClick = { onColor(layer.id, color) }),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier.size(32.dp).clip(CircleShape).background(parsed)
                                        .border(
                                            if (selected) 3.dp else 1.dp,
                                            if (selected) ring else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                            CircleShape,
                                        ),
                                )
                            }
                        }
                    }
                }
                profile.inputs.forEach { input ->
                    MappingRow(
                        input = input,
                        binding = layer.bindings[input.id],
                        catalog = controller.choices,
                        enabled = !busy,
                        onSelect = { gesture ->
                            target = MappingTarget(
                                layerId = layer.id,
                                inputId = input.id,
                                gesture = gesture,
                            )
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f))
                }
                if (profile.workflows.isNotEmpty()) {
                    Text("Workflow prompts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    profile.workflows.forEach { workflow ->
                        WorkflowEditor(
                            workflow = workflow,
                            busy = busy,
                            onSave = { prompt -> onWorkflow(workflow.id, prompt) },
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f))
            SectionHeader(Icons.AutoMirrored.Filled.Logout, "Connection")
            OutlinedButton(
                onClick = { confirmDisconnect = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Disconnect and forget pairing")
            }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    target?.let { selected ->
        val selectedController = controller ?: return@let
        val resolved = resolveMappingTarget(profile, selected) ?: return@let
        ActionPicker(
            target = selected,
            inputLabel = resolved.input.label,
            binding = resolved.binding,
            currentAction = resolved.action,
            catalog = selectedController.choices,
            enabled = !busy,
            onDismiss = { target = null },
            onSelect = { actionId ->
                onUpdate(selected.layerId, selected.inputId, selected.gesture, actionId)
                target = null
            },
            onClear = {
                onClear(selected.layerId, selected.inputId, selected.gesture)
                target = null
            },
        )
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Forget this Bridge?") },
            text = { Text(disconnectMessage(hasPendingActions)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisconnect = false
                        onDisconnect()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
}

internal fun settingsHaveChanges(
    connectionDirty: Boolean,
    display: Display,
    draft: Display,
): Boolean = connectionDirty || display != draft

@Composable
private fun SectionHeader(icon: ImageVector, label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingsLayers(
    layers: List<Layer>,
    activeLayerId: String,
    inFlightIds: Set<String>,
    enabled: Boolean,
    onLayer: (String) -> Boolean,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        layers.forEachIndexed { index, layer ->
            val selected = layer.id == activeLayerId
            val color = profileColor(layer.color)
            val surface = MaterialTheme.colorScheme.surface
            val background = if (selected) compositedBackground(color, 0.22f, surface) else surface
            val accent = contrastingColor(
                preferred = color,
                background = background,
                fallback = MaterialTheme.colorScheme.onSurface,
                minimumRatio = 4.5f,
            )
            val loading = "layer:${layer.id}" in inFlightIds
            val selectable = enabled && !selected && !loading
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(background)
                    .border(
                        if (selected) 2.dp else 1.dp,
                        accent.copy(alpha = if (selected) 1f else 0.55f),
                        RoundedCornerShape(6.dp),
                    )
                    .semantics {
                        role = Role.Button
                        this.selected = selected
                        contentDescription = layerSemanticsLabel(index, layer.name)
                        if (!selectable && !selected) disabled()
                    }
                    .clickable(enabled = selectable, onClick = { onLayer(layer.id) }),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(17.dp), color = accent, strokeWidth = 2.dp)
                } else {
                    Text("${index + 1}", color = accent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun WorkflowEditor(
    workflow: Workflow,
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
            enabled = !busy,
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
private fun MappingRow(
    input: Input,
    binding: Binding?,
    catalog: List<Choice>,
    enabled: Boolean,
    onSelect: (Gesture.Kind) -> Unit,
) {
    val pushToTalk = binding?.actions?.get(Gesture.Kind.TAP)?.type == "voice"
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(iconForInput(input.icon), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(input.label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        }
        Gesture.Kind.entries.forEach { gesture ->
            val action = binding?.actions?.get(gesture)
            val label = catalog.firstOrNull { it.action == action }?.label ?: action?.type ?: "Unmapped"
            val gestureEnabled = enabled && (!pushToTalk || gesture == Gesture.Kind.TAP)
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
private fun ActionPicker(
    target: MappingTarget,
    inputLabel: String,
    binding: Binding?,
    currentAction: Action?,
    catalog: List<Choice>,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$inputLabel · ${target.gesture.shortLabel}") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                if (currentAction != null) {
                    TextButton(onClick = onClear, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text("Clear mapping", color = MaterialTheme.colorScheme.error)
                    }
                    HorizontalDivider()
                }
                catalog.filter { entry ->
                    target.gesture == Gesture.Kind.TAP || entry.action.type != "voice"
                }.forEach { entry ->
                    val selected = entry.action == currentAction
                    val optionEnabled = enabled && (entry.action.type != "voice" || binding.allowsVoiceMapping())
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = optionEnabled) { onSelect(entry.id) }
                            .alpha(if (optionEnabled) 1f else 0.48f)
                            .padding(horizontal = 4.dp, vertical = 11.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                        }
                        if (enabled && !optionEnabled) {
                            Text(
                                "Clear Double and Hold mappings first",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
