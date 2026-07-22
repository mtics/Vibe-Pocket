package au.edu.uts.vibepocket.hardware

import android.content.Context
import au.edu.uts.vibepocket.hid.Keyboard

internal enum class Handover {
    READY,
    FAILED,
}

internal class Radio<T>(
    private val pause: (T, (Handover) -> Unit) -> Unit,
    private val resume: (T) -> Unit,
) {
    private enum class Settlement {
        IGNORE,
        READY,
        RESTORE,
    }

    private val lock = Any()
    private var target: T? = null
    private var owner: Any? = null
    private var waiting = false
    private var released: Any? = null
    private var restoring = false

    fun attach(value: T) {
        synchronized(lock) {
            check(target == null || target === value) { "Bluetooth radio already has a classic owner." }
            check(owner == null && released == null && !restoring) {
                "Classic hardware must attach before Micro can claim Bluetooth."
            }
            target = value
        }
    }

    fun detach(value: T) {
        synchronized(lock) {
            check(owner == null && released == null && !restoring) {
                "Bluetooth cannot detach during a hardware handover."
            }
            if (target === value) target = null
        }
    }

    fun claim(key: Any, ready: () -> Unit): Boolean {
        val current = synchronized(lock) {
            if (owner != null || released != null || restoring) return false
            owner = key
            waiting = target != null
            target
        }
        if (current == null) readyIfOwned(key, ready) else pause(current) { outcome ->
            settle(key, ready, outcome)
        }
        return true
    }

    fun release(key: Any) {
        var current: T? = null
        val resumeNow = synchronized(lock) {
            if (owner !== key) return
            owner = null
            if (waiting) {
                released = key
                false
            } else {
                current = target
                restoring = current != null
                true
            }
        }
        if (resumeNow) restore(current)
    }

    private fun settle(key: Any, ready: () -> Unit, handover: Handover) {
        var current: T? = null
        val outcome = synchronized(lock) {
            when {
                handover == Handover.FAILED && owner === key -> {
                    owner = null
                    waiting = false
                    current = target
                    restoring = current != null
                    Settlement.RESTORE
                }
                handover == Handover.FAILED && released === key -> {
                    released = null
                    waiting = false
                    current = target
                    restoring = current != null
                    Settlement.RESTORE
                }
                owner === key -> {
                    waiting = false
                    Settlement.READY
                }
                released === key -> {
                    released = null
                    waiting = false
                    current = target
                    restoring = current != null
                    Settlement.RESTORE
                }
                else -> Settlement.IGNORE
            }
        }
        when (outcome) {
            Settlement.READY -> readyIfOwned(key, ready)
            Settlement.RESTORE -> restore(current)
            Settlement.IGNORE -> Unit
        }
    }

    private fun restore(value: T?) {
        if (value == null) return
        try {
            resume(value)
        } finally {
            synchronized(lock) { restoring = false }
        }
    }

    private fun readyIfOwned(key: Any, ready: () -> Unit) {
        if (synchronized(lock) { owner === key }) ready()
    }
}

internal class Hardware(context: Context) : AutoCloseable {
    val keyboard = Keyboard(context)
    val radio = Radio(
        pause = Keyboard::pause,
        resume = Keyboard::resume,
    )

    init {
        radio.attach(keyboard)
    }

    override fun close() {
        radio.detach(keyboard)
        keyboard.close()
    }
}
