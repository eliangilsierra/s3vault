package com.s3vault.app.domain.privacy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the private albums are open, and the vault key while they are. The key lives only here, only while unlocked, and is
 * overwritten with zeros when the session locks. Nothing is persisted: a new process always starts locked.
 *
 * It locks when the user asks, when the screen turns off, and when the app has been out of the foreground for [autoLockMs]
 * (0 locks as soon as the app is left). It does not lock on inactivity while the app stays in front.
 */
class PrivateSession(private val clock: () -> Long = System::currentTimeMillis) {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked

    private val lock = Any()
    private var key: ByteArray? = null
    private var leftAtMs: Long? = null
    private val listeners = ArrayList<() -> Unit>()

    @Volatile var autoLockMs: Long = DEFAULT_AUTO_LOCK_MS

    fun unlock(vaultKey: ByteArray) {
        synchronized(lock) {
            key?.fill(0)
            key = vaultKey.copyOf()
            _unlocked.value = true
        }
    }

    /** A copy of the key for one operation. The caller overwrites it when done; locking does not reach into it. */
    fun keyCopy(): ByteArray = synchronized(lock) { key?.copyOf() } ?: throw VaultLockedException()

    fun lock() {
        val toNotify: List<() -> Unit>
        synchronized(lock) {
            if (key == null && !_unlocked.value) return
            key?.fill(0); key = null
            _unlocked.value = false
            toNotify = listeners.toList()
        }
        toNotify.forEach { runCatching(it) }
    }

    /** [onLock] runs every time the session locks (to drop decrypted bitmaps and the like). */
    fun addLockListener(onLock: () -> Unit) { synchronized(lock) { listeners += onLock } }

    fun onAppBackgrounded() {
        if (!_unlocked.value) return
        synchronized(lock) { leftAtMs = clock() }
        if (autoLockMs <= 0) lock()
    }

    fun onAppForegrounded() {
        val left = synchronized(lock) { leftAtMs.also { leftAtMs = null } } ?: return
        if (_unlocked.value && clock() - left >= autoLockMs) lock()
    }

    fun onScreenOff() = lock()

    companion object {
        const val DEFAULT_AUTO_LOCK_MS = 60_000L
        val CHOICES_MS = longArrayOf(0L, 60_000L, 300_000L)
    }
}
