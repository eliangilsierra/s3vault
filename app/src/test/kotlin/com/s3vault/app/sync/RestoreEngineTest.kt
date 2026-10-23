package com.s3vault.app.sync

import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.SyncState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var env: TestEnv
    private val sink = FakeSink()
    private val bytes = "the-original-photo-content".toByteArray()

    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() { env.close() }

    private suspend fun backupOne(): String {
        env.index(env.scanned(1, name = "foto.jpg", bytes = bytes))
        env.enqueue(1)
        env.engine().run(1)
        env.catalog.refresh(env.ctx)
        return env.db.remote().getAll().single().key
    }

    private suspend fun queueRestore(key: String) {
        env.db.remote().transition(key, SyncState.REMOTE_ONLY, SyncState.RESTORING, null)
    }

    @Test fun `restores, verifies and publishes the file without overwriting anything`() = runBlocking {
        val key = backupOne()
        queueRestore(key)

        val out = env.restoreEngine(sink, tmp.newFolder("parts")).run()

        assertEquals(RestoreOutcome.Done(1, 0), out)
        val w = sink.results.single()
        assertTrue(w.committed)
        assertArrayEquals(bytes, w.bytes.toByteArray())
        assertEquals("foto.jpg", w.name) // collision handling is delegated to the sink (MediaStore renames)
        val row = env.db.remote().get(key)!!
        assertEquals(SyncState.REMOTE_ONLY, row.state)
        assertNotNull(row.restoredUri)
    }

    @Test fun `a corrupted download is never published`() = runBlocking {
        val key = backupOne()
        queueRestore(key)
        env.remote.corruptDownload = true

        val out = env.restoreEngine(sink, tmp.newFolder("parts")).run()

        assertTrue(out.toString(), out is RestoreOutcome.RetryLater)
        assertTrue(sink.results.isEmpty())
        assertNull(env.db.remote().get(key)!!.restoredUri)
    }

    @Test fun `interrupted downloads resume from the partial file`() = runBlocking {
        val key = backupOne()
        queueRestore(key)
        val parts = tmp.newFolder("parts")
        env.remote.downloadFailureAfterBytes = 10
        val engine = env.restoreEngine(sink, parts)

        val first = engine.run()
        assertTrue(first is RestoreOutcome.RetryLater)
        assertTrue(sink.results.isEmpty())

        val second = engine.run()
        assertEquals(RestoreOutcome.Done(1, 0), second)
        assertEquals(listOf(0L, 10L), env.remote.downloadOffsets)
        assertArrayEquals(bytes, sink.results.single().bytes.toByteArray())
    }

    @Test fun `expired credentials keep the restore queued`() = runBlocking {
        val key = backupOne()
        queueRestore(key)
        env.creds.failure = RemoteException(RemoteErrorKind.CREDENTIALS_EXPIRED)
        val out = env.restoreEngine(sink, tmp.newFolder("parts")).run()
        assertTrue(out is RestoreOutcome.NeedsCredentials)
        assertEquals(SyncState.RESTORING, env.db.remote().get(key)!!.state)
        assertTrue(sink.results.isEmpty())
    }

    @Test fun `object removed remotely fails cleanly`() = runBlocking {
        val key = backupOne()
        queueRestore(key)
        env.remote.objects.clear()
        val out = env.restoreEngine(sink, tmp.newFolder("parts")).run()
        assertEquals(RestoreOutcome.Done(0, 1), out)
        assertEquals(SyncState.REMOTE_ONLY, env.db.remote().get(key)!!.state)
        assertNotNull(env.db.remote().get(key)!!.lastError)
        assertFalse(sink.results.any { it.committed })
    }

    @Test fun `restoring does not touch the remote object`() = runBlocking {
        val key = backupOne()
        queueRestore(key)
        env.restoreEngine(sink, tmp.newFolder("parts")).run()
        assertTrue(env.remote.objects.containsKey(key))
        assertEquals(1, env.remote.objects.size)
    }
}
