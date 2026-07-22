package au.edu.uts.vibepocket

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import au.edu.uts.vibepocket.hid.Keyboard
import java.util.concurrent.atomic.AtomicBoolean

internal class HardwareLease<T : AutoCloseable>(val value: T) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) value.close()
    }
}

class HardwareViewModel(application: Application) : AndroidViewModel(application) {
    val keyboard: Keyboard = (application as VibePocket).hardware.keyboard
}
