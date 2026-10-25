package com.s3vault.app.sync

import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.SyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupEngineTest {
    private lateinit var env: TestEnv

    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() { env.close() }

    private suspend fun item(id: Long) = env.db.media().get(id)!!

    @Test fun `uploads a queued photo, verifies it and marks it backed up`() = runBlocking {
        env.index(env.scanned(1, name = "Vacaciones 1.jpg"))
        env.enqueue(1)

        val out = env.engine().run(2)

        assertTrue(out.toString(), out is RunOutcome.Completed)
        val i = item(1)
        assertEquals(SyncState.BACKED_UP, i.syncState)
        assertNotNull(i.lastBackupAtMs)
        assertEquals(1, env.remote.uploadCalls)
        // {prefix}photos/{assetId}/{versionId}/{safeName}
        val key = i.remoteKey!!
        assertTrue(key, key.startsWith("mi/prefijo/photos/${i.assetId}/${i.remoteVersionId}/"))
        assertTrue(key.endsWith("/Vacaciones_1.jpg"))
        assertEquals(i.contentSha256, i.remoteVersionId)
        assertTrue(env.remote.objects.containsKey(key))
    }

    @Test fun `everything uploaded stays inside the configured prefix`() = runBlocking {
        env.index(env.scanned(1), env.scanned(2))
        env.enqueue(1, 2)
        env.engine().run(2)
        assertTrue(env.remote.uploadedKeys.isNotEmpty())
        assertTrue(env.remote.uploadedKeys.all { it.startsWith("mi/prefijo/photos/") })
    }

    @Test fun `same file name from different folders does not collide remotely`() = runBlocking {
        env.index(env.scanned(1, name = "IMG.jpg", bytes = "a".toByteArray()), env.scanned(2, name = "IMG.jpg", bytes = "b".toByteArray()))
        env.enqueue(1, 2)
        env.engine().run(2)
        assertEquals(2, env.remote.objects.size)
        assertEquals(2, env.remote.objects.keys.toSet().size)
    }

    @Test fun `already backed up files are not queued or uploaded again`() = runBlocking {
        env.index(env.scanned(1))
        env.enqueue(1)
        env.engine().run(2)
        assertEquals(1, env.remote.uploadCalls)

        assertEquals(0, env.db.media().enqueue(1, env.now)) // BACKED_UP cannot be re-queued
        val out = env.engine().run(2)
        assertTrue(out is RunOutcome.Completed)
        assertEquals(1, env.remote.uploadCalls)
    }

    @Test fun `identical content is linked to the existing remote object instead of uploaded again`() = runBlocking {
        val same = "same-bytes".toByteArray()
        env.index(env.scanned(1, name = "a.jpg", bytes = same))
        env.enqueue(1)
        env.engine().run(2)

        // e.g. a restored copy, or a second install: a different local asset with identical content
        env.index(env.scanned(1, name = "a.jpg", bytes = same), env.scanned(2, name = "copy.jpg", bytes = same))
        env.enqueue(2)
        val out = env.engine().run(2) as RunOutcome.Completed

        assertEquals(1, out.skippedAlreadyBackedUp)
        assertEquals(1, env.remote.uploadCalls)
        assertEquals(item(1).remoteKey, item(2).remoteKey)
        assertEquals(SyncState.BACKED_UP, item(2).syncState)
    }

    @Test fun `a modified file gets a new version and the old object is kept`() = runBlocking {
        env.index(env.scanned(1, bytes = "v1".toByteArray(), mtime = 100))
        env.enqueue(1)
        env.engine().run(2)
        val firstKey = item(1).remoteKey!!

        val r = env.index(env.scanned(1, bytes = "version-two".toByteArray(), mtime = 200))
        assertEquals(1, r.modifiedSinceBackup)
        assertEquals(SyncState.LOCAL_ONLY, item(1).syncState)

        env.enqueue(1)
        env.engine().run(2)
        val second = item(1)
        assertEquals(SyncState.BACKED_UP, second.syncState)
        assertTrue(second.remoteKey != firstKey)
        assertTrue("old version must remain", env.remote.objects.containsKey(firstKey))
        assertTrue(env.remote.objects.containsKey(second.remoteKey!!))
        // same asset, different version segment
        assertEquals(firstKey.substringBeforeLast("/").substringBeforeLast("/"), second.remoteKey!!.substringBeforeLast("/").substringBeforeLast("/"))
    }

    @Test fun `touching only the modification time re-verifies without uploading`() = runBlocking {
        env.index(env.scanned(1, bytes = "same".toByteArray(), mtime = 100))
        env.enqueue(1)
        env.engine().run(2)
        env.index(env.scanned(1, bytes = "same".toByteArray(), mtime = 999))
        assertEquals(SyncState.LOCAL_ONLY, item(1).syncState)
        env.enqueue(1)
        env.engine().run(2)
        assertEquals(SyncState.BACKED_UP, item(1).syncState)
        assertEquals(1, env.remote.uploadCalls)
    }

    @Test fun `403 on HEAD of a vanished object (prefix scoped policy) falls back to a normal upload`() = runBlocking {
        env.index(env.scanned(1, bytes = "same".toByteArray(), mtime = 100))
        env.enqueue(1)
        env.engine().run(2)
        env.remote.objects.clear() // somebody removed it outside this app
        env.index(env.scanned(1, bytes = "same".toByteArray(), mtime = 200))
        env.enqueue(1)
        env.remote.headFailures += RemoteException(RemoteErrorKind.ACCESS_DENIED)

        val out = env.engine().run(2)

        assertTrue(out.toString(), out is RunOutcome.Completed)
        assertEquals(SyncState.BACKED_UP, item(1).syncState)
        assertEquals(2, env.remote.uploadCalls)
        assertEquals(1, env.remote.objects.size)
    }

    @Test fun `hash is cached and only recomputed when size or mtime change`() = runBlocking {
        env.index(env.scanned(1, bytes = "hash-me".toByteArray(), mtime = 100))
        assertNull(item(1).contentSha256) // never hashed just by indexing
        env.enqueue(1)
        env.engine().run(2)
        val i = item(1)
        assertTrue(i.hashIsValid())
        env.index(env.scanned(1, bytes = "hash-me".toByteArray(), mtime = 101))
        assertFalse(item(1).hashIsValid())
    }

    @Test fun `network interruption keeps the item queued and a later run completes it`() = runBlocking {
        env.index(env.scanned(1))
        env.enqueue(1)
        env.remote.uploadFailures += RemoteException(RemoteErrorKind.NETWORK)

        val first = env.engine().run(1)
        assertTrue(first is RunOutcome.RetryLater)
        val queued = item(1)
        assertEquals(SyncState.QUEUED, queued.syncState)
        assertEquals(1, queued.attempts)
        assertNotNull(queued.lastError)

        val second = env.engine().run(1)
        assertTrue(second is RunOutcome.Completed)
        assertEquals(SyncState.BACKED_UP, item(1).syncState)
    }

    @Test fun `retries are bounded and end in FAILED which can be retried manually`() = runBlocking {
        env.index(env.scanned(1))
        env.enqueue(1)
        repeat(10) { env.remote.uploadFailures += RemoteException(RemoteErrorKind.NETWORK) }
        repeat(3) { env.engine(maxAttempts = 3).run(1) }
        val failed = item(1)
        assertEquals(SyncState.FAILED, failed.syncState)
        assertFalse(failed.lastErrorPermanent)
        assertEquals(1, env.db.media().enqueue(1, env.now)) // FAILED -> QUEUED is allowed
    }

    @Test fun `missing credentials stop the run and keep the queue intact`() = runBlocking {
        env.index(env.scanned(1), env.scanned(2))
        env.enqueue(1, 2)
        env.creds.failure = RemoteException(RemoteErrorKind.CREDENTIALS_MISSING)

        val out = env.engine().run(2)

        assertTrue(out is RunOutcome.NeedsCredentials)
        assertEquals(0, env.remote.uploadCalls)
        assertEquals(listOf(SyncState.QUEUED, SyncState.QUEUED), listOf(item(1).syncState, item(2).syncState))
    }

    @Test fun `expired credentials never mark anything as backed up and work resumes after renewal`() = runBlocking {
        env.index(env.scanned(1), env.scanned(2))
        env.enqueue(1, 2)
        env.creds.failure = RemoteException(RemoteErrorKind.CREDENTIALS_EXPIRED)

        val out = env.engine().run(2) as RunOutcome.NeedsCredentials
        assertEquals(RemoteErrorKind.CREDENTIALS_EXPIRED, out.reason)
        assertTrue(listOf(item(1), item(2)).none { it.syncState == SyncState.BACKED_UP })
        assertEquals(2, env.db.media().queuedCount())

        env.creds.failure = null // user pasted new credentials
        val resumed = env.engine().run(2)
        assertTrue(resumed is RunOutcome.Completed)
        assertEquals(listOf(SyncState.BACKED_UP, SyncState.BACKED_UP), listOf(item(1).syncState, item(2).syncState))
    }

    @Test fun `credentials that expire mid-run requeue the item without error or attempt`() = runBlocking {
        env.index(env.scanned(1))
        env.enqueue(1)
        env.remote.uploadFailures += RemoteException(RemoteErrorKind.CREDENTIALS_EXPIRED)

        val out = env.engine().run(1)

        assertTrue(out is RunOutcome.NeedsCredentials)
        val i = item(1)
        assertEquals(SyncState.QUEUED, i.syncState)
        assertEquals(0, i.attempts)
    }

    @Test fun `insufficient permissions mark the item failed permanently and block the run`() = runBlocking {
        env.index(env.scanned(1), env.scanned(2))
        env.enqueue(1, 2)
        env.remote.uploadFailures += RemoteException(RemoteErrorKind.ACCESS_DENIED)

        val out = env.engine().run(1)

        assertEquals(RunOutcome.Blocked(RemoteErrorKind.ACCESS_DENIED), out)
        val failed = listOf(item(1), item(2)).filter { it.syncState == SyncState.FAILED }
        assertEquals(1, failed.size)
        assertTrue(failed.single().lastErrorPermanent)
        assertTrue(failed.single().lastError!!.contains("Permisos"))
    }

    @Test fun `listing failure with wrong region blocks without touching items`() = runBlocking {
        env.index(env.scanned(1))
        env.enqueue(1)
        env.remote.listFailure = RemoteException(RemoteErrorKind.WRONG_REGION, expectedRegion = "eu-west-1")
        val out = env.engine().run(1)
        assertEquals(RunOutcome.Blocked(RemoteErrorKind.WRONG_REGION), out)
        assertEquals(SyncState.QUEUED, item(1).syncState)
    }

    @Test fun `failed integrity verification never marks the file as backed up`() = runBlocking {
        env.index(env.scanned(1))
        env.enqueue(1)
        env.remote.headChecksumOverride = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

        val out = env.engine(maxAttempts = 2).run(1)

        assertTrue(out is RunOutcome.RetryLater)
        val i = item(1)
        assertEquals(SyncState.QUEUED, i.syncState)
        assertNull(i.lastBackupAtMs)
        assertNull(i.remoteKey)
    }

    @Test fun `upload rejected by checksum is not accepted`() = runBlocking {
        env.index(env.scanned(1))
        env.enqueue(1)
        env.remote.uploadFailures += RemoteException(RemoteErrorKind.INTEGRITY_MISMATCH)
        env.engine().run(1)
        assertEquals(SyncState.QUEUED, item(1).syncState)
    }

    @Test fun `unreadable local file fails permanently`() = runBlocking {
        env.index(env.scanned(1))
        env.reader.files.clear()
        env.enqueue(1)
        env.engine().run(1)
        val i = item(1)
        assertEquals(SyncState.FAILED, i.syncState)
        assertTrue(i.lastErrorPermanent)
    }

    @Test fun `process death mid-upload is recovered to the queue and completed`() = runBlocking {
        env.index(env.scanned(1), env.scanned(2))
        env.enqueue(1, 2)
        // Simulate a killed process: rows frozen in-flight
        env.db.media().transition(1, SyncState.QUEUED, SyncState.UPLOADING, null, false, env.now)
        env.db.media().transition(2, SyncState.QUEUED, SyncState.UPLOADING, null, false, env.now)
        env.db.media().transition(2, SyncState.UPLOADING, SyncState.VERIFYING, null, false, env.now)

        // "Restarted app": a fresh engine + same database
        val out = env.engine().run(2)

        assertTrue(out is RunOutcome.Completed)
        assertEquals(listOf(SyncState.BACKED_UP, SyncState.BACKED_UP), listOf(item(1).syncState, item(2).syncState))
    }

    @Test fun `cancelling a running upload returns the item to the queue`() = runBlocking {
        env.index(env.scanned(1))
        env.enqueue(1)
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        env.remote.uploadGate = { started.complete(Unit); never.await() }

        val job = async(start = CoroutineStart.UNDISPATCHED) { runCatching { env.engine().run(1) } }
        while (!started.isCompleted) yield()
        job.cancel()
        job.join()

        assertEquals(SyncState.QUEUED, item(1).syncState)
        assertNull(item(1).lastBackupAtMs)
    }

    @Test fun `no secrets reach the stored error text`() = runBlocking {
        env.index(env.scanned(1))
        env.enqueue(1)
        val secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        env.remote.uploadFailures += IllegalStateException("request failed secret_access_key=$secret Credential=AKIAIOSFODNN7EXAMPLE/2026")
        env.engine().run(1)
        val err = item(1).lastError!!
        assertFalse(err, err.contains(secret))
        assertFalse(err, err.contains("AKIAIOSFODNN7EXAMPLE"))
        val log = env.db.events().observeRecent(10)
        assertNotNull(log)
    }

    @Test fun `files over the single PUT limit are rejected with a clear error`() = runBlocking {
        val big = env.scanned(1).copy(sizeBytes = BackupEngine.MAX_SINGLE_PUT_BYTES + 1)
        env.index(big)
        env.enqueue(1)
        env.engine().run(1)
        val i = item(1)
        assertEquals(SyncState.FAILED, i.syncState)
        assertTrue(i.lastError!!.contains("demasiado grande") && i.lastError!!.contains("4 GiB"))
    }

    @Test fun `successful uploads are logged and update the last transfer`() = runBlocking {
        env.index(env.scanned(1))
        env.enqueue(1)
        var last = 0L
        BackupEngine(env.db, env.provider, env.creds, env.reader, env.progress, env.events, env.catalog, { env.now }, onTransferSuccess = { last = it }).run(1)
        assertEquals(env.now, last)
        assertEquals(env.now, env.db.media().get(1)!!.lastBackupAtMs)
    }

    @Test fun `not configured`() = runBlocking {
        env.index(env.scanned(1))
        env.enqueue(1)
        env.provider = RemoteContextProvider { null }
        assertEquals(RunOutcome.NotConfigured, env.engine().run(1))
        assertEquals(SyncState.QUEUED, item(1).syncState)
    }
}
