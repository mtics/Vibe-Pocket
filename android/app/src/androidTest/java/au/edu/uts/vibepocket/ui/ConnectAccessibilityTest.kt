package au.edu.uts.vibepocket.ui

import au.edu.uts.vibepocket.ui.control.BoardTestActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectAccessibilityTest {
    @get:Rule
    val rule = createAndroidComposeRule<BoardTestActivity>()

    @Before
    fun enableAccessibilityValidation() {
        rule.enableAccessibilityChecks()
    }

    @Test
    fun lightConnectPassesAutomatedChecks() {
        rule.setContent { Theme(dark = false) { Connect({ true }, null, false) } }

        rule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun darkConnectPassesAutomatedChecks() {
        rule.setContent { Theme(dark = true) { Connect({ true }, null, false) } }

        rule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun pairingErrorDoesNotMovePrimaryControls() {
        var error by mutableStateOf<String?>(null)
        rule.setContent { Theme { Connect({ true }, error, false) } }
        fun bounds() = listOf("Scan QR code", "Paste invitation").map {
            rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot
        }
        val ready = bounds()

        rule.runOnIdle { error = "The Bridge uses pairing protocol 9, but this app requires 11." }

        assertEquals(ready, bounds())
        rule.onRoot().tryPerformAccessibilityChecks()
    }
}
