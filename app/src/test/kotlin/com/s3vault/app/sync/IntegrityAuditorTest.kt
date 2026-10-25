package com.s3vault.app.sync

import com.s3vault.app.data.local.AuditStatus
import com.s3vault.app.data.local.AuditTarget
import com.s3vault.app.data.local.IntegrityReason
import com.s3vault.app.data.local.IntegrityResultEntity
import com.s3vault.app.data.local.IntegrityVerdict
import com.s3vault.app.data.local.VerifyLevel
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.RemoteObjectMetadata
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.integrity.AuditCosts
import com.s3vault.app.domain.integrity.IntegrityAnalyzer
import com.s3vault.app.domain.integrity.IntegrityPlanner
import com.s3vault.app.domain.integrity.ListedObject
import com.s3vault.app.domain.integrity.ListingFacts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntegrityAuditorTest {
    private lateinit var env: TestEnv
    private lateinit var jobs: JobRecorder
    private var lastListing: ListingResult? = null

    @Before fun setUp() {
        env = TestEnv()
        jobs = JobRecorder(env.db) { env.now }
        env.provider = RemoteContextProvider { RemoteContext(env.remote, env.keys, S3Config("eu-west-1", "bucket", "mi/prefijo/", AuthMethod.STATIC)) }
        env.catalog = RemoteCatalog(env.db, nowMs = { env.now }, onListed = { lastListing = it })
    }
    @After fun tearDown() { env.close() }

    private fun auditor() = IntegrityAuditor(env.db, env.provider, env.creds, env.catalog, jobs, lastListing = { lastListing }, nowMs = { env.now })

    private suspend fun backUp(vararg ids: Long) {
        env.index(*ids.map { env.scanned(it) }.toTypedArray())
        env.enqueue(*ids)
        env.engine().run(2)
    }

    private suspend fun keyOf(id: Long) = env.db.media().get(id)!!.remoteKey!!
    private suspend fun result(id: Long) = env.db.integrity().resultsFor(listOf(keyOf(id))).single()
    private val untouchedRemote get() = env.remote.deleteCalls.isEmpty()

    // ------------------------------------------------------------------ listing audit

    @Test fun `a healthy backup is verified by the listing and the backup recorded its version marker`() = runBlocking {
        backUp(1, 2)
        assertNotNull("the verified backup recorded the object's version marker", env.db.media().get(1)!!.remoteEtag)
        val uploads = env.remote.uploadCalls

        val out = auditor().runListing() as AuditOutcome.Completed

        assertEquals(2, out.audit.verified)
        assertEquals(0, out.audit.missing + out.audit.changed + out.audit.unknown)
        assertEquals(IntegrityVerdict.VERIFIED, result(1).verdict)
        assertEquals("the listing proves existence and size, not content", VerifyLevel.LISTING, result(1).level)
        assertEquals("an audit reads: it never writes or deletes in S3", uploads, env.remote.uploadCalls)
        assertTrue(untouchedRemote)
        assertEquals("one LIST page", 1, out.audit.requests)
    }

    @Test fun `an object that vanished is missing, the rest stays verified`() = runBlocking {
        backUp(1, 2)
        env.remote.objects.remove(keyOf(1))
        val out = auditor().runListing() as AuditOutcome.Completed
        assertEquals(1, out.audit.missing)
        assertEquals(IntegrityVerdict.MISSING, result(1).verdict)
        assertEquals(IntegrityReason.NOT_LISTED, result(1).reason)
        assertEquals(IntegrityVerdict.VERIFIED, result(2).verdict)
        assertEquals("nothing was removed or changed in the local state by the audit", SyncState.BACKED_UP, env.db.media().get(1)!!.syncState)
    }

    @Test fun `a replaced object is flagged by its version marker even when the size is identical`() = runBlocking {
        backUp(1)
        val key = keyOf(1)
        val old = env.remote.objects.getValue(key)
        env.remote.objects[key] = FakeRemote.Obj(old.bytes.reversedArray(), old.sha256B64, "someone-else")
        val out = auditor().runListing() as AuditOutcome.Completed
        assertEquals(1, out.audit.changed)
        assertEquals(IntegrityReason.ETAG_CHANGED, result(1).reason)
    }

    @Test fun `a size change is a changed object`() = runBlocking {
        backUp(1)
        val key = keyOf(1)
        env.remote.objects[key] = FakeRemote.Obj("tr".toByteArray(), "", env.remote.objects.getValue(key).etag)
        auditor().runListing()
        assertEquals(IntegrityVerdict.CHANGED, result(1).verdict)
        assertEquals(IntegrityReason.SIZE_MISMATCH, result(1).reason)
        assertEquals(2L, result(1).remoteSize)
    }

    @Test fun `a failed listing concludes nothing, old verdicts stay and no object is called missing`() = runBlocking {
        backUp(1, 2)
        auditor().runListing()
        env.remote.objects.remove(keyOf(1)) // really gone, but the next listing will fail
        env.remote.listFailure = RemoteException(RemoteErrorKind.NETWORK)

        val out = auditor().runListing(reuseListingYoungerThanMs = 0)

        assertTrue(out is AuditOutcome.RetryLater)
        val audit = env.db.integrity().latestAudit()!!
        assertEquals(AuditStatus.FAILED, audit.status)
        assertEquals(RemoteErrorKind.NETWORK.userMessage, audit.error)
        assertEquals("a failed verification is not a confirmed loss", IntegrityVerdict.VERIFIED, result(1).verdict)
        assertEquals(0, env.db.integrity().allResults().count { it.verdict == IntegrityVerdict.MISSING })
    }

    @Test fun `expired credentials stop the audit before any request`() = runBlocking {
        backUp(1)
        env.creds.failure = RemoteException(RemoteErrorKind.CREDENTIALS_EXPIRED)
        val calls = env.remote.listCalls
        assertTrue(auditor().runListing() is AuditOutcome.NeedsCredentials)
        assertEquals(calls, env.remote.listCalls)
        assertNull("no audit row for a run that never started", env.db.integrity().latestAudit())
    }

    @Test fun `an empty listing is not proof that everything is gone`() = runBlocking {
        backUp(1, 2)
        env.remote.objects.clear() // a different bucket, or a policy that hides everything
        val out = auditor().runListing() as AuditOutcome.Completed
        assertEquals(0, out.audit.missing)
        assertEquals(2, out.audit.unknown)
        assertEquals(IntegrityReason.LISTING_EMPTY, result(1).reason)
    }

    @Test fun `records that cannot describe an object of this bucket are stale, not missing`() = runBlocking {
        backUp(1)
        // The user switched to another prefix: the old record points outside it.
        env.provider = RemoteContextProvider { RemoteContext(env.remote, com.s3vault.app.domain.RemoteKeys("otro/"), S3Config("eu-west-1", "bucket", "otro/", AuthMethod.STATIC)) }
        val out = auditor().runListing() as AuditOutcome.Completed
        assertEquals(1, out.audit.stale)
        assertEquals(IntegrityReason.KEY_OUTSIDE_PREFIX, result(1).reason)
    }

    @Test fun `an older backup gets a starting marker on its first audit and later replacement is caught`() = runBlocking {
        backUp(1)
        env.db.openHelper.writableDatabase.execSQL("UPDATE media_items SET remoteEtag = NULL")
        auditor().runListing()
        assertNotNull("adopted as the starting point", env.db.media().get(1)!!.remoteEtag)
        assertEquals(IntegrityVerdict.VERIFIED, result(1).verdict)

        val key = keyOf(1)
        val old = env.remote.objects.getValue(key)
        env.remote.objects[key] = FakeRemote.Obj(old.bytes, old.sha256B64, "replaced")
        auditor().runListing(reuseListingYoungerThanMs = 0)
        assertEquals(IntegrityReason.ETAG_CHANGED, result(1).reason)
    }

    @Test fun `a recent listing of the same bucket is reused without a request, another bucket's is not`() = runBlocking {
        backUp(1)
        auditor().runListing() // lists and reports where
        val lists = env.remote.listCalls
        env.now += 60_000
        auditor().runListing(reuseListingYoungerThanMs = IntegrityAuditor.REUSE_LISTING_MS)
        assertEquals("reused: no new LIST", lists, env.remote.listCalls)
        assertEquals(0, env.db.integrity().latestAudit()!!.requests)

        lastListing = lastListing!!.copy(source = "us-east-1/other/")
        auditor().runListing(reuseListingYoungerThanMs = IntegrityAuditor.REUSE_LISTING_MS)
        assertEquals("a listing of a different bucket is never trusted", lists + 1, env.remote.listCalls)
    }

    // ------------------------------------------------------------------ deep audit

    @Test fun `the deep audit confirms the stored checksum without downloading anything`() = runBlocking {
        backUp(1, 2)
        val out = auditor().runDeep() as AuditOutcome.Completed
        assertEquals(2, out.audit.verified)
        assertEquals(2, out.audit.requests)
        assertEquals(VerifyLevel.CHECKSUM, result(1).level)
        assertNotNull(result(1).deepCheckedAtMs)
        assertTrue("no object body was requested", env.remote.downloadOffsets.isEmpty() && env.remote.readRequests.isEmpty())
    }

    @Test fun `a different checksum is a changed object`() = runBlocking {
        backUp(1)
        env.remote.headChecksumOverride = "AAAA"
        auditor().runDeep()
        assertEquals(IntegrityVerdict.CHANGED, result(1).verdict)
        assertEquals(IntegrityReason.CHECKSUM_MISMATCH, result(1).reason)
        assertNull("the earlier confirmation no longer counts", result(1).deepCheckedAtMs)
    }

    @Test fun `HEAD not found is a confirmed missing object but a refusal is only unknown`() = runBlocking {
        backUp(1, 2)
        env.remote.objects.remove(keyOf(1))
        env.remote.headFailures.addLast(RemoteException(RemoteErrorKind.ACCESS_DENIED)) // the first HEAD is refused
        val out = auditor().runDeep(freshForMs = 0) as AuditOutcome.Completed
        val verdicts = env.db.integrity().allResults().associate { it.remoteKey to it }
        // Which of the two requests got the refusal is not fixed (they run concurrently); what matters is the classification.
        assertEquals(1, verdicts.values.count { it.verdict == IntegrityVerdict.UNKNOWN && it.reason == IntegrityReason.FORBIDDEN })
        assertEquals(0, verdicts.values.count { it.verdict == IntegrityVerdict.MISSING && it.reason == IntegrityReason.FORBIDDEN })
        assertEquals(2, out.audit.checked)
    }

    @Test fun `a 404 is confirmed missing`() = runBlocking {
        backUp(1)
        env.remote.objects.remove(keyOf(1))
        auditor().runDeep()
        assertEquals(IntegrityVerdict.MISSING, result(1).verdict)
        assertEquals(IntegrityReason.HEAD_NOT_FOUND, result(1).reason)
    }

    @Test fun `repeated network errors stop the run as retry-later and never claim loss`() = runBlocking {
        backUp(1, 2, 3, 4, 5, 6, 7, 8)
        repeat(8) { env.remote.headFailures.addLast(RemoteException(RemoteErrorKind.NETWORK)) }
        val out = auditor().runDeep(limit = 8)
        assertTrue(out is AuditOutcome.RetryLater)
        assertEquals(0, env.db.integrity().allResults().count { it.verdict == IntegrityVerdict.MISSING })
        assertEquals(AuditStatus.FAILED, env.db.integrity().latestAudit()!!.status)
        assertTrue(env.db.integrity().allResults().all { it.reason == IntegrityReason.TEMPORARY_ERROR })
    }

    @Test fun `expired credentials during a deep audit stop it and keep what was confirmed`() = runBlocking {
        backUp(1, 2)
        env.remote.headFailures.addLast(RemoteException(RemoteErrorKind.CREDENTIALS_EXPIRED))
        env.remote.headFailures.addLast(RemoteException(RemoteErrorKind.CREDENTIALS_EXPIRED))
        assertTrue(auditor().runDeep() is AuditOutcome.NeedsCredentials)
        assertEquals(0, env.db.integrity().allResults().count { it.verdict == IntegrityVerdict.MISSING })
    }

    @Test fun `incremental deep audits take the objects confirmed longest ago and skip fresh ones`() = runBlocking {
        backUp(1, 2, 3)
        val first = auditor().runDeep(limit = 2) as AuditOutcome.Completed
        assertEquals(2, first.audit.checked)
        env.now += 1_000
        val second = auditor().runDeep(limit = 2) as AuditOutcome.Completed
        assertEquals("only the one never confirmed is left", 1, second.audit.checked)
        env.now += 1_000
        val third = auditor().runDeep(limit = 2) as AuditOutcome.Completed
        assertEquals("everything is fresh: no request at all", 0, third.audit.checked)
        assertEquals(0, third.audit.requests)
        val headsBefore = env.remote.headCalls
        auditor().runDeep(limit = 2)
        assertEquals(headsBefore, env.remote.headCalls)
    }

    @Test fun `a recheck of chosen objects ignores freshness`() = runBlocking {
        backUp(1, 2)
        auditor().runDeep()
        val heads = env.remote.headCalls
        auditor().runDeep(onlyKeys = setOf(keyOf(2)))
        assertEquals(heads + 1, env.remote.headCalls)
    }

    @Test fun `cancelling a deep audit keeps the batches already confirmed`() = runBlocking {
        backUp(1, 2, 3, 4)
        val entered = CompletableDeferred<Unit>()
        env.remote.headGate = { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        val job = async(start = CoroutineStart.UNDISPATCHED) { auditor().runDeep() }
        entered.await()
        job.cancelAndJoin()
        env.remote.headGate = null
        assertEquals(AuditStatus.CANCELLED, env.db.integrity().latestAudit()!!.status)
        assertEquals(0, env.db.integrity().allResults().count { it.verdict == IntegrityVerdict.MISSING })
    }

    @Test fun `a run left running by a dead process is shown as interrupted`() = runBlocking {
        val id = env.db.integrity().insertAudit(com.s3vault.app.data.local.IntegrityAuditEntity(mode = com.s3vault.app.data.local.AuditMode.DEEP, status = AuditStatus.RUNNING, startedAtMs = 100))
        auditor().recoverInterrupted(processStartMs = 200)
        assertEquals(AuditStatus.INTERRUPTED, env.db.integrity().audit(id)!!.status)
    }

    // ------------------------------------------------------------------ repairs the user chooses

    @Test fun `re-upload sends a changed file through the verified queue and restores the object`() = runBlocking {
        backUp(1)
        val key = keyOf(1)
        // S3 stores the checksum of what it holds, so a replaced object carries the checksum of the replacement.
        val badSum = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest("corrupt".toByteArray()))
        env.remote.objects[key] = FakeRemote.Obj("corrupt".toByteArray(), badSum, "x")
        auditor().runListing()
        assertEquals(IntegrityVerdict.CHANGED, result(1).verdict)
        val repair = IntegrityRepair(env.db, BackupController(env.db, com.s3vault.app.settings.AppSettings(env.context), FakeScheduler()) { env.now })

        assertEquals(1, repair.reupload(listOf(key)))
        assertEquals(SyncState.QUEUED, env.db.media().get(1)!!.syncState)
        assertNull("the old verdict is gone with the old link", env.db.integrity().resultsFor(listOf(key)).singleOrNull())
        assertEquals("nothing happens in S3 until the queue runs", "corrupt", String(env.remote.objects.getValue(key).bytes))

        env.engine().run(2)
        assertEquals(SyncState.BACKED_UP, env.db.media().get(1)!!.syncState)
        assertEquals("photo-1", String(env.remote.objects.getValue(env.db.media().get(1)!!.remoteKey!!).bytes))
        auditor().runDeep()
        assertEquals(IntegrityVerdict.VERIFIED, result(1).verdict)
        assertTrue("the repair never deletes", untouchedRemote)
    }

    @Test fun `discarding forgets only records the audit found gone and whose file is gone too`() = runBlocking {
        backUp(1, 2, 3)
        // 1: file left the device and the object is gone. 2: file left the device, object fine. 3: object gone, file still here.
        env.index(env.scanned(3)) // 1 and 2 are no longer in MediaStore -> REMOTE_ONLY
        env.remote.objects.remove(keyOf(1)); env.remote.objects.remove(keyOf(3))
        auditor().runListing()
        val repair = IntegrityRepair(env.db, BackupController(env.db, com.s3vault.app.settings.AppSettings(env.context), FakeScheduler()) { env.now })

        val n = repair.discardRecords(listOf(keyOf(1), keyOf(2), keyOf(3)))

        assertEquals("only the gone record with no file", 1, n)
        assertNull(env.db.media().get(1))
        assertNotNull("the record of a healthy object stays", env.db.media().get(2))
        assertNotNull("a photo still on the device is never touched", env.db.media().get(3))
        assertTrue(untouchedRemote)
    }

    // ------------------------------------------------------------------ pure rules

    private val keys get() = env.keys
    private fun target(size: Long = 10, etag: String? = "e1", backedUpAt: Long? = 1_000_000, version: String = "a".repeat(64), key: String = keys.photoKey("b".repeat(32), version, "x.jpg")) =
        AuditTarget(key, version, size, etag, backedUpAt, anyPresent = true)

    @Test fun `without a recorded marker only a clearly later modification time is suspicious`() {
        val f = ListingFacts(complete = true, empty = false)
        val t = target(etag = null, backedUpAt = 1_000_000)
        assertEquals(IntegrityVerdict.VERIFIED, IntegrityAnalyzer.fromListing(t, ListedObject(10, 1_000_000 + 5 * 60_000, "z"), f, keys).verdict)
        val late = IntegrityAnalyzer.fromListing(t, ListedObject(10, 1_000_000 + 11 * 60_000, "z"), f, keys)
        assertEquals(IntegrityVerdict.CHANGED, late.verdict)
        assertEquals(IntegrityReason.MODIFIED_AFTER_BACKUP, late.reason)
    }

    @Test fun `a partial listing proves presence but never absence`() {
        val partial = ListingFacts(complete = false, empty = false)
        assertEquals(IntegrityVerdict.UNKNOWN, IntegrityAnalyzer.fromListing(target(), null, partial, keys).verdict)
        assertEquals(IntegrityVerdict.VERIFIED, IntegrityAnalyzer.fromListing(target(), ListedObject(10, 1, "e1"), partial, keys).verdict)
    }

    @Test fun `a record whose version disagrees with its key is stale`() {
        val t = target(version = "c".repeat(64), key = keys.photoKey("b".repeat(32), "a".repeat(64), "x.jpg"))
        assertEquals(IntegrityReason.VERSION_MISMATCH, IntegrityAnalyzer.fromListing(t, ListedObject(10, 1, "e1"), ListingFacts(true, false), keys).reason)
    }

    private fun meta(size: Long = 10, sum: String? = null, user: Map<String, String> = emptyMap()) = RemoteObjectMetadata("k", size, 1, "e", sum, "image/jpeg", user)

    @Test fun `a multipart style composite checksum is not compared as a content hash`() {
        val t = target()
        val f = IntegrityAnalyzer.fromHead(t, meta(sum = "abc=-12"), keys)
        assertEquals(IntegrityVerdict.UNKNOWN, f.verdict)
        assertEquals(IntegrityReason.NO_REMOTE_CHECKSUM, f.reason)
    }

    @Test fun `with no checksum in S3 the uploader's own metadata is weaker evidence and says so`() {
        val v = "a".repeat(64)
        val f = IntegrityAnalyzer.fromHead(target(version = v), meta(user = mapOf("sha256-hex" to v)), keys)
        assertEquals(IntegrityVerdict.VERIFIED, f.verdict)
        assertEquals(VerifyLevel.METADATA, f.level)
        assertEquals(IntegrityReason.NO_REMOTE_CHECKSUM, f.reason)
        assertEquals(IntegrityVerdict.UNKNOWN, IntegrityAnalyzer.fromHead(target(version = v), meta(), keys).verdict)
    }

    @Test fun `the planner prefers never confirmed, then oldest, and honours a selection`() {
        val a = target(key = keys.photoKey("1".repeat(32), "a".repeat(64), "a.jpg"))
        val b = target(key = keys.photoKey("2".repeat(32), "b".repeat(64), "b.jpg"), version = "b".repeat(64))
        val c = target(key = keys.photoKey("3".repeat(32), "c".repeat(64), "c.jpg"), version = "c".repeat(64))
        fun res(t: AuditTarget, deep: Long?) = IntegrityResultEntity(t.key, IntegrityVerdict.VERIFIED, VerifyLevel.CHECKSUM, checkedAtMs = 1, deepCheckedAtMs = deep)
        val results = mapOf(a.key to res(a, 500), b.key to res(b, null), c.key to res(c, 100))
        assertEquals(listOf(b, c), IntegrityPlanner.nextDeep(listOf(a, b, c), results, freshAfterMs = 400, limit = 5))
        assertEquals("selection ignores freshness", listOf(a), IntegrityPlanner.nextDeep(listOf(a, b, c), results, 400, 5, onlyKeys = setOf(a.key)))
        assertEquals(listOf(b), IntegrityPlanner.nextDeep(listOf(a, b, c), results, 400, 1))
    }

    @Test fun `costs are in requests and priced from the shipped table`() {
        assertEquals(3, AuditCosts.listing(2_500, "eu-west-1", reusedListing = false).requests)
        assertEquals(0, AuditCosts.listing(2_500, "eu-west-1", reusedListing = true).requests)
        val deep = AuditCosts.deep(1_000, "us-east-1")
        assertEquals(1_000, deep.requests)
        assertTrue("1000 HEADs cost a fraction of a cent to a few cents, never nothing", deep.usd!! in 0.0001..0.05)
        assertNull("no price for an unknown region: no number rather than a guess", AuditCosts.deep(10, "nowhere-1").usd)
        assertFalse(AuditCosts.listing(0, "eu-west-1", false).requests == 0)
    }
}
