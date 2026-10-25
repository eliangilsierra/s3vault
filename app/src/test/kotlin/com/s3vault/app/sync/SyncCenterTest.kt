package com.s3vault.app.sync

import com.s3vault.app.data.local.JobNames
import com.s3vault.app.data.local.JobState
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.TransferEventType
import com.s3vault.app.domain.sync.CloudStatus
import com.s3vault.app.domain.sync.FailureAdviser
import com.s3vault.app.domain.sync.FailureFix
import com.s3vault.app.domain.sync.RowAction
import com.s3vault.app.domain.sync.SyncCounts
import com.s3vault.app.domain.sync.SyncFilter
import com.s3vault.app.domain.sync.SyncPresentation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

/** The sync centre reads and drives the existing pipelines; these tests pin what it may claim and what it may change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncCenterTest {
    private lateinit var env: TestEnv
    private lateinit var jobs: JobRecorder

    @Before fun setUp() { env = TestEnv(); jobs = JobRecorder(env.db) { env.now } }
    @After fun tearDown() { env.close() }

    private suspend fun item(id: Long) = env.db.media().get(id)!!

    // ------------------------------------------------------------------ counts and lists

    @Test fun `local counts describe the files on the device only`() = runBlocking {
        env.index(env.scanned(1), env.scanned(2), env.scanned(3))
        env.enqueue(1)
        env.engine().run(2) // 1 -> BACKED_UP
        env.index(env.scanned(2), env.scanned(3)) // 1 vanished from the device but keeps its S3 copy (REMOTE_ONLY, present = 0)
        val counts = env.db.media().observeLocalStateCounts().first().associate { it.syncState to it.n }
        assertEquals("a file that left the device is an S3 object, not a local one", null, counts[SyncState.BACKED_UP])
        assertEquals(2, counts[SyncState.LOCAL_ONLY])
    }

    @Test fun `the activity list shows work in flight before the queue and errors, newest last resort`() = runBlocking {
        env.index(env.scanned(1), env.scanned(2), env.scanned(3), env.scanned(4))
        env.db.media().transition(1, SyncState.LOCAL_ONLY, SyncState.QUEUED, null, false, 10)
        env.db.media().transition(2, SyncState.LOCAL_ONLY, SyncState.QUEUED, null, false, 5)
        env.db.media().transition(2, SyncState.QUEUED, SyncState.UPLOADING, null, false, 6)
        env.db.media().transition(3, SyncState.LOCAL_ONLY, SyncState.QUEUED, null, false, 1)
        env.db.media().transition(3, SyncState.QUEUED, SyncState.FAILED, "boom", false, 2)
        val rows = env.db.media().observeLocalByStates(SyncFilter.ACTIVITY.localStates, 10).first()
        assertEquals(listOf(2L, 1L, 3L), rows.map { it.mediaStoreId })
        assertEquals("a file nobody touched is not activity", 3, rows.size)
        assertEquals("the page size is a real limit", 1, env.db.media().observeLocalByStates(SyncFilter.ACTIVITY.localStates, 1).first().size)
    }

    @Test fun `S3 side counts separate cloud-only, restoring and failed restores`() = runBlocking {
        env.index(env.scanned(1))
        env.enqueue(1)
        env.engine().run(2)
        val key1 = item(1).remoteKey!!
        // 2 and 3 exist only in S3.
        env.remote.objects["mi/prefijo/photos/${"b".repeat(32)}/${"c".repeat(64)}/two.jpg"] = FakeRemote.Obj(ByteArray(4), "", "e")
        env.remote.objects["mi/prefijo/photos/${"d".repeat(32)}/${"e".repeat(64)}/three.jpg"] = FakeRemote.Obj(ByteArray(4), "", "e")
        env.catalog.refresh(env.ctx)
        val two = env.db.remote().allKeys().first { it.endsWith("two.jpg") }
        val three = env.db.remote().allKeys().first { it.endsWith("three.jpg") }
        env.db.remote().transition(two, SyncState.REMOTE_ONLY, SyncState.RESTORING, null)
        env.db.remote().transition(three, SyncState.REMOTE_ONLY, SyncState.REMOTE_ONLY, "no se pudo")

        val c = env.db.remote().observeSyncCounts().first()
        assertEquals("the object that also lives on the device is not 'only in S3', and one being restored is listed as restoring", 1, c.cloudOnly)
        assertEquals(1, c.restoring)
        assertEquals(1, c.restoreFailed)
        assertEquals(listOf(three), env.db.remote().observeCloudOnly(10).first().map { it.key })
        assertEquals(listOf(two), env.db.remote().observeRestoring(10).first().map { it.key })
        assertTrue(key1 !in env.db.remote().observeCloudOnly(10).first().map { it.key })
    }

    @Test fun `domain counts map every state and never double count verifying`() {
        val c = SyncCounts.of(
            mapOf(SyncState.LOCAL_ONLY to 4, SyncState.QUEUED to 3, SyncState.UPLOADING to 1, SyncState.VERIFYING to 2, SyncState.BACKED_UP to 10, SyncState.FAILED to 5),
            remoteOnly = 7, restoring = 2, restoreFailed = 1,
        )
        assertEquals(3, c.uploading)
        assertEquals(3 + 3 + 5, c.activity)
        assertEquals(25, c.localTotal)
        assertEquals(10, c.of(SyncFilter.BACKED_UP))
        assertEquals(7, c.of(SyncFilter.REMOTE_ONLY))
        SyncFilter.entries.forEach { c.of(it) } // exhaustive
        assertTrue(SyncFilter.REMOTE_ONLY.isRemote && SyncFilter.RESTORING.isRemote && !SyncFilter.QUEUED.isRemote)
    }

    // ------------------------------------------------------------------ what the centre may claim

    @Test fun `only a verified backup is confirmed in S3`() {
        assertEquals(CloudStatus.CONFIRMED, SyncPresentation.cloudStatus(SyncState.BACKED_UP))
        assertEquals("verifying is not confirmed", CloudStatus.VERIFYING, SyncPresentation.cloudStatus(SyncState.VERIFYING))
        assertEquals(CloudStatus.NOT_YET, SyncPresentation.cloudStatus(SyncState.UPLOADING))
        assertEquals(CloudStatus.NOT_YET, SyncPresentation.cloudStatus(SyncState.QUEUED))
        assertEquals(CloudStatus.NONE, SyncPresentation.cloudStatus(SyncState.LOCAL_ONLY))
        assertEquals(CloudStatus.FAILED, SyncPresentation.cloudStatus(SyncState.FAILED))
        assertEquals("an object known only from the listing is 'listed', not 'confirmed'", CloudStatus.LISTED, SyncPresentation.cloudStatus(SyncState.REMOTE_ONLY))
    }

    @Test fun `row actions never offer what the engines would refuse`() = runBlocking {
        env.index(env.scanned(1))
        val local = item(1).toDomain()
        assertEquals(setOf(RowAction.BACK_UP, RowAction.OPEN), SyncPresentation.actionsFor(local))
        assertEquals(setOf(RowAction.CANCEL, RowAction.OPEN), SyncPresentation.actionsFor(local.copy(syncState = SyncState.QUEUED)))
        assertEquals(setOf(RowAction.RETRY, RowAction.OPEN), SyncPresentation.actionsFor(local.copy(syncState = SyncState.FAILED)))
        assertEquals("an upload in flight cannot be cancelled from the list", setOf(RowAction.OPEN), SyncPresentation.actionsFor(local.copy(syncState = SyncState.UPLOADING)))
        assertEquals(setOf(RowAction.OPEN), SyncPresentation.actionsFor(local.copy(syncState = SyncState.BACKED_UP)))
    }

    @Test fun `failure advice is recovered from the stored message`() {
        val denied = RemoteException(RemoteErrorKind.ACCESS_DENIED, detail = "403").message
        val a = FailureAdviser.of(denied, permanent = true)
        assertEquals(FailureFix.PERMISSIONS, a.fix())
        assertEquals(RemoteErrorKind.ACCESS_DENIED, a.kind)
        assertFalse("the technical tag stays out of the summary", a.summary.contains("403"))

        assertEquals(FailureFix.CREDENTIALS, FailureAdviser.of(RemoteErrorKind.CREDENTIALS_EXPIRED.userMessage, false).fix())
        assertEquals(FailureFix.S3_SETTINGS, FailureAdviser.of(RemoteErrorKind.WRONG_REGION.userMessage + " Región del bucket: eu-west-1.", true).fix())
        assertEquals("not retryable", FailureFix.NOTHING, FailureAdviser.of(RemoteErrorKind.FILE_TOO_LARGE.userMessage, true).fix())
        val network = FailureAdviser.of(RemoteErrorKind.NETWORK.userMessage, permanent = false, attempts = 6)
        assertEquals(FailureFix.RETRY, network.fix())
        assertTrue(network.hint.contains("6"))
        assertEquals("unknown text is shown as it is", "algo raro", FailureAdviser.of("algo raro", true).summary)
        assertEquals("Error desconocido", FailureAdviser.of(null, false).summary)
    }

    private fun com.s3vault.app.domain.sync.FailureAdvice.fix() = action

    // ------------------------------------------------------------------ operations reuse the existing state machine

    @Test fun `cancelling queued uploads is explicit, partial and keeps them out of the automatic backup`() = runBlocking {
        env.index(env.scanned(1), env.scanned(2), env.scanned(3))
        env.enqueue(1, 2, 3)
        env.db.media().transition(3, SyncState.QUEUED, SyncState.UPLOADING, null, false, env.now) // a worker already took it
        val controller = BackupController(env.db, com.s3vault.app.settings.AppSettings(env.context), FakeScheduler()) { env.now }

        val n = controller.cancelQueued(listOf(1, 3, 99))

        assertEquals("only what was still waiting is cancelled", 1, n)
        assertEquals(SyncState.LOCAL_ONLY, item(1).syncState)
        assertTrue("flagged so the scan does not bring it back", item(1).backupExcluded)
        assertEquals(SyncState.QUEUED, item(2).syncState)
        assertEquals("an upload in flight is not cancelled behind the worker's back", SyncState.UPLOADING, item(3).syncState)
        assertTrue("the automatic backup leaves it alone", env.db.media().pendingLocalOnly(includeVideos = true).none { it.mediaStoreId == 1L })

        // Choosing it again by hand clears the flag.
        assertEquals(1, controller.retry(listOf(1)))
        assertFalse(item(1).backupExcluded)
        assertEquals(SyncState.QUEUED, item(1).syncState)
    }

    @Test fun `retrying a failed item goes back through the queue, not around verification`() = runBlocking {
        env.index(env.scanned(1))
        env.reader.files.clear() // the file cannot be read: permanent failure
        env.enqueue(1)
        env.engine().run(2)
        assertEquals(SyncState.FAILED, item(1).syncState)
        assertTrue(item(1).lastErrorPermanent)

        env.reader.files["content://media/1"] = "photo-1".toByteArray()
        val controller = BackupController(env.db, com.s3vault.app.settings.AppSettings(env.context), FakeScheduler()) { env.now }
        assertEquals(1, controller.retry(listOf(1)))
        assertEquals(SyncState.QUEUED, item(1).syncState)
        assertNull("the old error is cleared when the user retries", item(1).lastError)
        env.engine().run(2)
        assertEquals(SyncState.BACKED_UP, item(1).syncState)
    }

    // ------------------------------------------------------------------ the persistent run summary

    @Test fun `a run with a failure is partial and never reported as complete`() = runBlocking {
        env.index(env.scanned(1), env.scanned(2))
        env.reader.files.remove("content://media/2") // 2 will fail permanently
        env.enqueue(1, 2)
        val progress = mutableListOf<RunProgress>()

        var started = false
        val outcome = env.engine().run(2) { p -> progress += p; if (!started) { jobs.start(JobNames.BACKUP, p.total); started = true }; jobs.progress(JobNames.BACKUP, p.total, p.verified, p.failed, p.done) }
        jobs.finishBackup(outcome)

        assertEquals("the first report says how much work there is before any of it is done", RunProgress(2, 0, 0, 0), progress.first())
        val job = env.db.jobs().get(JobNames.BACKUP)!!
        assertEquals(JobState.PARTIAL, job.state)
        assertEquals(1, job.succeededItems)
        assertEquals(1, job.failedItems)
        assertNotNull(job.lastError)
        assertEquals(SyncState.BACKED_UP, item(1).syncState)
        assertEquals(SyncState.FAILED, item(2).syncState)
    }

    @Test fun `a clean run is completed and counts only verified items`() = runBlocking {
        env.index(env.scanned(1), env.scanned(2))
        env.enqueue(1, 2)
        jobs.start(JobNames.BACKUP, 2)
        jobs.finishBackup(env.engine().run(2))
        val job = env.db.jobs().get(JobNames.BACKUP)!!
        assertEquals(JobState.COMPLETED, job.state)
        assertEquals(2, job.succeededItems)
        assertNull(job.lastError)
        assertEquals(2, env.db.media().observeLocalStateCounts().first().first { it.syncState == SyncState.BACKED_UP }.n)
    }

    @Test fun `outcomes that need the user are never summarised as success`() = runBlocking {
        jobs.start(JobNames.BACKUP, 5)
        jobs.finishBackup(RunOutcome.NeedsCredentials(RemoteErrorKind.CREDENTIALS_EXPIRED))
        assertEquals(JobState.NEEDS_ATTENTION, env.db.jobs().get(JobNames.BACKUP)!!.state)
        assertEquals(RemoteErrorKind.CREDENTIALS_EXPIRED.userMessage, env.db.jobs().get(JobNames.BACKUP)!!.lastError)

        jobs.start(JobNames.BACKUP, 5)
        jobs.finishBackup(RunOutcome.RetryLater(1, RemoteErrorKind.NETWORK))
        assertEquals(JobState.RETRY_LATER, env.db.jobs().get(JobNames.BACKUP)!!.state)

        jobs.start(JobNames.BACKUP, 5)
        jobs.finishBackup(RunOutcome.Blocked(RemoteErrorKind.ACCESS_DENIED))
        assertEquals(JobState.NEEDS_ATTENTION, env.db.jobs().get(JobNames.BACKUP)!!.state)
    }

    @Test fun `a late progress report cannot reopen a finished run`() = runBlocking {
        jobs.start(JobNames.BACKUP, 3)
        jobs.finish(JobNames.BACKUP, JobState.CANCELLED)
        jobs.progress(JobNames.BACKUP, 3, 1, 0, 1)
        val job = env.db.jobs().get(JobNames.BACKUP)!!
        assertEquals(JobState.CANCELLED, job.state)
        assertEquals(0, job.succeededItems)
    }

    @Test fun `a run that died with the process is shown as interrupted, one started after it is not`() = runBlocking {
        env.now = 1_000
        jobs.start(JobNames.BACKUP, 10)
        jobs.start(JobNames.RESTORE, 0)
        env.now = 5_000 // the new process starts here...
        jobs.start(JobNames.RESTORE, 2) // ...and a worker already began again

        jobs.recoverInterrupted(processStartMs = 4_000)

        assertEquals(JobState.INTERRUPTED, env.db.jobs().get(JobNames.BACKUP)!!.state)
        assertEquals("work of the current process is left alone", JobState.RUNNING, env.db.jobs().get(JobNames.RESTORE)!!.state)
    }

    @Test fun `process death mid upload returns the item to the queue and the next run finishes it`() = runBlocking {
        env.index(env.scanned(1))
        env.enqueue(1)
        // The process died after taking the item and after the first progress report.
        env.db.media().transition(1, SyncState.QUEUED, SyncState.UPLOADING, null, false, env.now)
        jobs.start(JobNames.BACKUP, 1)
        jobs.recoverInterrupted(processStartMs = env.now + 1)

        assertEquals(JobState.INTERRUPTED, env.db.jobs().get(JobNames.BACKUP)!!.state)
        assertEquals(1, env.db.media().recoverInterrupted())
        assertEquals(SyncState.QUEUED, item(1).syncState)
        jobs.start(JobNames.BACKUP, 1)
        jobs.finishBackup(env.engine().run(2))
        assertEquals(SyncState.BACKED_UP, item(1).syncState)
        assertEquals(JobState.COMPLETED, env.db.jobs().get(JobNames.BACKUP)!!.state)
    }

    @Test fun `last failure is the most recent one that needs attention and ignores successes`() = runBlocking {
        env.events.log(TransferEventType.UPLOAD_FAILED, "a.jpg", "Permisos insuficientes")
        env.now += 10
        val later = EventLog(env.db) { env.now }
        later.log(TransferEventType.UPLOADED, "b.jpg")
        later.log(TransferEventType.CLOUD_DELETED, "3 elementos")
        val last = env.db.events().observeLastFailure().first()
        assertEquals("a.jpg", last!!.name)
        assertEquals(TransferEventType.UPLOAD_FAILED, last.type)
    }
}
