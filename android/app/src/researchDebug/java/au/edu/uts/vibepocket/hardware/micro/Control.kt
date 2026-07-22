package au.edu.uts.vibepocket.hardware.micro

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class Control : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Peripheral.stopAction && !hasPermissions(context)) {
            Log.e(tag, "event=command_blocked reason=bluetooth_permissions action=${intent.action}")
            return
        }
        val service = Intent(context, Peripheral::class.java).setAction(intent.action)
        runCatching { ContextCompat.startForegroundService(context, service) }
            .onFailure { Log.e(tag, "event=command_blocked reason=${it.javaClass.simpleName}", it) }
    }

    private fun hasPermissions(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    companion object {
        private const val tag = "VibePocketMicro"
    }
}
