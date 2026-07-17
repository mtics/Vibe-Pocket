package au.edu.uts.vibepocket

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val VibeColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF55D6A4),
    onPrimary = Color(0xFF062A1C),
    primaryContainer = Color(0xFF143E2C),
    secondary = Color(0xFFE7B56D),
    secondaryContainer = Color(0xFF4C371A),
    background = Color(0xFF101512),
    onBackground = Color(0xFFF0F5F1),
    surface = Color(0xFF19211B),
    onSurface = Color(0xFFF0F5F1),
    surfaceVariant = Color(0xFF28332B),
    onSurfaceVariant = Color(0xFFC2CEC3),
    error = Color(0xFFFF8C79),
    onError = Color(0xFF3B0A05),
)

@Composable
fun VibePocketTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VibeColors, content = content)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun VibePocketApp(viewModel: PocketViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.config == null) {
        ConnectScreen(onConnect = viewModel::connect, error = state.error)
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Vibe Pocket", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh desktop controls")
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
                ControlPad(
                    snapshot = snapshot,
                    onAttach = viewModel::attach,
                    onVoice = viewModel::voice,
                    onStop = viewModel::stop,
                    onNewTask = viewModel::newTask,
                    onApprove = viewModel::approve,
                    onReject = viewModel::reject,
                )
            }
        }
    }
}

@Composable
private fun ConnectScreen(onConnect: (String, String) -> Unit, error: String?) {
    var url by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
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
private fun ControlPad(
    snapshot: PocketSnapshot,
    onAttach: () -> Unit,
    onVoice: () -> Unit,
    onStop: () -> Unit,
    onNewTask: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val controls = snapshot.controls
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionStatus(snapshot.status)
        Text("Controls", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ControlButton(
                label = "听写",
                icon = Icons.Filled.Mic,
                onClick = onVoice,
                modifier = Modifier.weight(1f),
                enabled = controls.voice,
                primary = true,
            )
            ControlButton(
                label = "停止",
                icon = Icons.Filled.Stop,
                onClick = onStop,
                modifier = Modifier.weight(1f),
                enabled = controls.stop,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ControlButton(
                label = "定位",
                icon = Icons.Filled.PlayArrow,
                onClick = onAttach,
                modifier = Modifier.weight(1f),
            )
            ControlButton(
                label = "新建任务",
                icon = Icons.Filled.Add,
                onClick = onNewTask,
                modifier = Modifier.weight(1f),
                enabled = controls.newTask,
            )
        }
        if (controls.approve || controls.reject) {
            Text("Approval", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (controls.approve) {
                    ControlButton(
                        label = "允许",
                        icon = Icons.Filled.Check,
                        onClick = onApprove,
                        modifier = Modifier.weight(1f),
                        primary = true,
                    )
                }
                if (controls.reject) {
                    ControlButton(
                        label = "拒绝",
                        icon = Icons.Filled.Close,
                        onClick = onReject,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ConnectionStatus(status: BridgeStatus) {
    val ready = status.state == "ready"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(10.dp).clip(CircleShape).background(
                    if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                ),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (ready) "M5 Codex ready" else "Bridge unavailable", fontWeight = FontWeight.SemiBold)
                status.message?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val colors = if (primary) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(78.dp),
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = colors,
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
            Text(label, maxLines = 1)
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Text(if (isRefreshing) "Connecting to M5..." else "No controller state yet")
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onRefresh, shape = RoundedCornerShape(8.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Retry")
        }
    }
}
