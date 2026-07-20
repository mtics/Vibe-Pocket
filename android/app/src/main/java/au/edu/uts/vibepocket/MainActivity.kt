package au.edu.uts.vibepocket

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import au.edu.uts.vibepocket.connection.Vault
import au.edu.uts.vibepocket.session.Session
import au.edu.uts.vibepocket.ui.App
import au.edu.uts.vibepocket.ui.Theme
import au.edu.uts.vibepocket.ui.preference.Store
import au.edu.uts.vibepocket.ui.preference.usesDark

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<Session> {
        Session.create(Vault(applicationContext))
    }
    private val hardware by viewModels<HardwareViewModel>()
    private lateinit var preferences: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = Store(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        consume(intent)
        setContent {
            var display by remember { mutableStateOf(preferences.read()) }
            val dark = display.palette.usesDark(isSystemInDarkTheme())
            SideEffect {
                val style = if (dark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    )
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            Theme(dark = dark) {
                App(
                    viewModel = viewModel,
                    hidController = hardware.keyboard,
                    display = display,
                    onDisplay = { selected ->
                        preferences.write(selected).also { saved -> if (saved) display = selected }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consume(intent)
    }

    private fun consume(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val invitation = intent.dataString ?: return
        setIntent(Intent(intent).setData(null))
        viewModel.offer(invitation)
    }
}
