package au.edu.uts.vibepocket.experiment

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import au.edu.uts.vibepocket.hardware.micro.Peripheral
import androidx.core.content.ContextCompat

internal object Provider : Capability {
    override val available = true
    override val description = "Enable Micro"
    override val permissionFailure =
        "Nearby devices and notification permissions are required for Micro recovery."

    override fun permissions(sdk: Int): Array<String> = buildList {
        if (sdk >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (sdk >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    override fun start(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, Peripheral::class.java).setAction(Peripheral.startAction),
        )
    }

    override fun failure(error: Throwable): String = error.message ?: "Micro could not start."
}
