package au.edu.uts.vibepocket.hardware

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import au.edu.uts.vibepocket.R

internal object Recovery {
    const val action = "au.edu.uts.vibepocket.hardware.RESTORE"
    const val ownerPid = "au.edu.uts.vibepocket.hardware.OWNER_PID"

    fun available(context: Context): Boolean {
        val notifications = context.getSystemService(NotificationManager::class.java)
        ensureChannel(notifications)
        return notifications.areNotificationsEnabled() &&
            notifications.getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun post(context: Context, pid: Int): Boolean {
        if (!available(context)) return false
        val launch = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, Return::class.java).putExtra(ownerPid, pid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_vibe_pocket)
            .setContentTitle("Return to Vibe Pocket")
            .setContentText("Tap to restore Bluetooth controls")
            .setContentIntent(launch)
            .setAutoCancel(true)
            .build()
        return runCatching {
            context.getSystemService(NotificationManager::class.java).notify(notificationId, notification)
            true
        }.getOrDefault(false)
    }

    private fun ensureChannel(notifications: NotificationManager) {
        notifications.createNotificationChannel(
            NotificationChannel(channelId, "Bluetooth recovery", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private const val channelId = "hardware-recovery"
    private const val notificationId = 62
}
