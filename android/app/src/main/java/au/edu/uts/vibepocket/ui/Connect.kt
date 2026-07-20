package au.edu.uts.vibepocket.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

internal fun normalizedScannedInvitation(rawValue: String?): String? =
    rawValue?.trim()?.takeIf(String::isNotEmpty)

@Composable
internal fun Connect(
    onInvitation: (String) -> Boolean,
    error: String?,
    isConnecting: Boolean,
) {
    var invitation by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    val activity = LocalContext.current.findActivity()
    val scanner = remember(activity) { activity?.qrScanner() }

    fun startScan() {
        scanError = null
        if (scanner == null) {
            scanError = "QR scanner is unavailable. Try again or paste the invitation."
            return
        }
        isScanning = true
        try {
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val scannedInvitation = normalizedScannedInvitation(barcode.rawValue)
                    if (scannedInvitation == null) {
                        scanError = "The QR code is empty. Scan again or paste the invitation."
                    } else {
                        onInvitation(scannedInvitation)
                    }
                }
                .addOnFailureListener {
                    scanError = "Could not scan the QR code. Scan again or paste the invitation."
                }
                .addOnCompleteListener { isScanning = false }
        } catch (_: RuntimeException) {
            isScanning = false
            scanError = "QR scanner is unavailable. Try again or paste the invitation."
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeContent)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 32.dp),
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
                Text(
                    "Scan the code shown on your Mac",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = ::startScan,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = !isConnecting && !isScanning,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan QR code")
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (isConnecting) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    (scanError ?: error)?.let {
                        if (isConnecting) Spacer(Modifier.height(12.dp))
                        ErrorNotice(it)
                    }
                }
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
                        onValueChange = {
                            invitation = it
                            scanError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        label = { Text("Pairing invitation") },
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            scanError = null
                            onInvitation(invitation)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isConnecting && invitation.isNotBlank(),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Continue")
                    }
                }
            }
        }
    }
}

private fun Activity.qrScanner(): GmsBarcodeScanner {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .enableAutoZoom()
        .build()
    return GmsBarcodeScanning.getClient(this, options)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
