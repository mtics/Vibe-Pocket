package au.edu.uts.vibepocket.hardware.micro

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context

class Name(
    context: Context,
    private val adapter: BluetoothAdapter,
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    @SuppressLint("MissingPermission")
    fun acquire(): Boolean {
        if (preferences.getBoolean(activeKey, false) && !recover()) return false
        val original = adapter.name ?: return false
        if (!preferences.edit().putString(originalKey, original).putBoolean(activeKey, true).commit()) return false
        if (adapter.name == advertised || adapter.setName(advertised)) return true
        recover()
        return false
    }

    @SuppressLint("MissingPermission")
    fun recover(): Boolean {
        val active = preferences.getBoolean(activeKey, false)
        val original = preferences.getString(originalKey, null)
        return when (restoration(active, original, adapter.name)) {
            Restoration.NOTHING -> true
            Restoration.CLEAR -> preferences.edit().clear().commit()
            Restoration.REQUEST -> {
                if (!adapter.setName(original)) return false
                if (adapter.name == original) preferences.edit().clear().commit() else false
            }
            Restoration.RETAIN -> false
        }
    }

    fun release() = recover()

    companion object {
        const val advertised = "Codex Micro"
        private const val preferencesName = "micro-name"
        private const val originalKey = "original"
        private const val activeKey = "active"
    }
}
