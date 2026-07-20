package au.edu.uts.vibepocket.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Connect light", widthDp = 393, heightDp = 873, showBackground = true)
@Composable
fun connectLight() = Theme(dark = false) {
    Connect(onInvitation = { true }, error = null, isConnecting = false)
}

@PreviewTest
@Preview(
    name = "Connect dark",
    widthDp = 393,
    heightDp = 873,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun connectDark() = Theme(dark = true) {
    Connect(onInvitation = { true }, error = null, isConnecting = false)
}
