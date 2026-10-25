package com.s3vault.app.domain.privacy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PrivateSessionTest {
    private var now = 0L
    private val s = PrivateSession { now }
    private val key = ByteArray(32) { it.toByte() }

    @Test fun `a new session is locked and refuses to hand out a key`() {
        assertFalse(s.unlocked.value)
        try { s.keyCopy(); fail() } catch (_: VaultLockedException) { }
    }

    @Test fun `unlocking stores a copy and locking overwrites it with zeros`() {
        s.unlock(key)
        assertTrue(s.unlocked.value)
        val held = s.keyCopy()
        assertArrayEquals(key, held)
        s.lock()
        assertFalse(s.unlocked.value)
        try { s.keyCopy(); fail() } catch (_: VaultLockedException) { }
        // The caller's own copy is the caller's to wipe; the session's master copy is gone.
        assertArrayEquals(key, held)
    }

    @Test fun `the session does not keep the array it was given`() {
        val given = key.copyOf()
        s.unlock(given)
        given.fill(0)
        assertArrayEquals(key, s.keyCopy())
    }

    @Test fun `leaving the app locks at once when the delay is zero`() {
        s.autoLockMs = 0; s.unlock(key)
        s.onAppBackgrounded()
        assertFalse(s.unlocked.value)
    }

    @Test fun `a short absence keeps it open and a long one locks it`() {
        s.autoLockMs = 60_000; s.unlock(key)
        now = 1_000; s.onAppBackgrounded(); now = 30_000; s.onAppForegrounded()
        assertTrue(s.unlocked.value)
        now = 40_000; s.onAppBackgrounded(); now = 40_000 + 60_000; s.onAppForegrounded()
        assertFalse(s.unlocked.value)
    }

    @Test fun `the screen turning off locks and a lock listener runs once per lock`() {
        var n = 0
        s.addLockListener { n++ }
        s.unlock(key); s.onScreenOff()
        assertFalse(s.unlocked.value); assertEquals(1, n)
        s.lock(); assertEquals("locking twice is not two events", 1, n)
        s.unlock(key); s.lock(); assertEquals(2, n)
    }

    @Test fun `a listener that throws does not stop the lock`() {
        s.addLockListener { error("boom") }
        s.unlock(key); s.lock()
        assertFalse(s.unlocked.value)
    }

    @Test fun `coming back without having left does nothing`() {
        s.unlock(key); s.onAppForegrounded()
        assertTrue(s.unlocked.value)
    }
}
