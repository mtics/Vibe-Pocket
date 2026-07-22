package au.edu.uts.vibepocket.hardware.micro

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

internal object AndroidBattery {
    @Suppress("DEPRECATION")
    fun sample(context: Context, event: Intent? = null): BatteryState? {
        val status = event ?: context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        status ?: return null
        val chargingStatus = status.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN,
        )
        return batteryState(
            level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            charging = chargingStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                chargingStatus == BatteryManager.BATTERY_STATUS_FULL,
        )
    }
}
