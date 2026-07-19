package au.edu.uts.vibepocket

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<PocketViewModel> {
        PocketViewModelFactory(SecureConfigStore(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            VibePocketTheme {
                VibePocketApp(viewModel = viewModel)
            }
        }
    }
}
