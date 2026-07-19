package au.edu.uts.vibepocket

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import au.edu.uts.vibepocket.connection.Vault
import au.edu.uts.vibepocket.session.Session
import au.edu.uts.vibepocket.ui.App
import au.edu.uts.vibepocket.ui.Theme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<Session> {
        Session.create(Vault(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        consume(intent)
        setContent {
            Theme {
                App(viewModel = viewModel)
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
