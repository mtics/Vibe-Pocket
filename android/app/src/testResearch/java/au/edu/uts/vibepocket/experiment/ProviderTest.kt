package au.edu.uts.vibepocket.experiment

import android.Manifest
import android.os.Build
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTest {
    @Test
    fun requestsRecoveryNotificationPermissionOnAndroid13() {
        assertTrue(Provider.available)
        assertArrayEquals(emptyArray<String>(), Provider.permissions(Build.VERSION_CODES.R))
        assertArrayEquals(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE),
            Provider.permissions(Build.VERSION_CODES.S),
        )
        assertArrayEquals(
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
            Provider.permissions(Build.VERSION_CODES.TIRAMISU),
        )
    }
}
