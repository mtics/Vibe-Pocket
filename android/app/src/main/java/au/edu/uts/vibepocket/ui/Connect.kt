package au.edu.uts.vibepocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun Connect(
    onInvitation: (String) -> Boolean,
    error: String?,
    isConnecting: Boolean,
) {
    var invitation by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Vibe Pocket", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Scan the code shown on your Mac", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        if (isConnecting) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
        error?.let {
            Spacer(Modifier.height(16.dp))
            ErrorNotice(it)
        }
        Spacer(Modifier.height(18.dp))
        TextButton(onClick = { advanced = !advanced }) {
            Icon(
                if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Paste invitation")
        }
        if (advanced) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = invitation,
                onValueChange = { invitation = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                label = { Text("Pairing invitation") },
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { onInvitation(invitation) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnecting && invitation.isNotBlank(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Continue")
            }
        }
    }
}
