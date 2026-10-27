package com.s3vault.app.sync

import com.s3vault.app.data.local.JobNames
import com.s3vault.app.data.local.JobState
import com.s3vault.app.data.local.ScannedMedia
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.faces.AesGcmFaceVault
import com.s3vault.app.domain.faces.DecisionOutcome
import com.s3vault.app.domain.faces.DecisionReason
import com.s3vault.app.domain.faces.FaceTestKit
import com.s3vault.app.domain.faces.MemoryDataKeyStore
import com.s3vault.app.domain.faces.PersonState
import com.s3vault.app.domain.faces.RulePolicy
import com.s3vault.app.domain.faces.RuleScope
import com.s3vault.app.domain.faces.RuleState
import com.s3vault.app.settings.AppSettings
import kotlinx.coroutines.async
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
import java.util.Random

/**
 * The automatic backup of a person's photos, end to end over the real Room tables, the real backup queue and (where it matters) the
 * real backup engine against a fake S3. The questions are the ones in the requirements: what is queued, what is not, what happens
 * when it goes wrong, and that nothing is ever deleted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceBackupRuleTest {
    private lateinit var env: TestEnv
    private lateinit var settings: AppSettings
    private lateinit var scheduler: FakeScheduler
    private lateinit var backup: BackupController
    private lateinit var service: FaceBackupService
    private lateinit var grouping: FaceGroupingStore
    private val vault = AesGcmFaceVault(MemoryDataKeyStore())
    private val script = FaceScript()
    private val lock = FaceIndexLock()
    private val rnd = Random(33)
    private val a = FaceTestKit.randomUnit(rnd)
    private val b = FaceTestKit.randomUnit(rnd)
    private val library = LinkedHashMap<Long, ScannedMedia>()
    private val dao get() = env.db.faces()
    private val media get() = env.db.media()

    /** The moment the user turns rules on: photos added to the library at or after it are "new". */
    private val activation get() = env.now / 1000

    @Before fun setUp() = runBlocking {
        env = TestEnv()
        settings = AppSettings(env.context)
        settings.saveS3Config(S3Config("eu-west-1", "bucket", "mi/prefijo/", AuthMethod.STATIC))
        scheduler = FakeScheduler()
        backup = BackupController(env.db, settings, scheduler) { env.now }
        grouping = FaceGroupingStore(env.db, vault, nowMs = { env.now })
        service = FaceBackupService(env.db, backup, settings, grouping, lock, JobRecorder(env.db) { env.now }, nowMs = { env.now })
    }

    @After fun tearDown() { env.close() }

    private fun clear(who: FloatArray) = FaceScript.Face(FaceTestKit.at(who, 0.9f, rnd), quality = 0.9f)

    private suspend fun add(id: Long, vararg faces: FaceScript.Face, addedSec: Long) {
        script.photos[id] = faces.toList()
        library[id] = env.scanned(id).copy(dateTakenMs = id * 1000, dateAddedSec = addedSec)
        env.index(*library.values.toTypedArray())
    }

    private fun indexer() = FaceIndexer(env.db, vault, { script.analyzer }, script.images, grouping, lock, JobRecorder(env.db) { env.now }, { service.evaluateAll() }, { env.now })

    /** Person A in photos 1..6 and person B in 11..16, all added "long ago" (second 100). */
    private suspend fun libraryOfTwo(): Pair<Long, Long> {
        for (i in 1L..6L) add(i, clear(a), addedSec = 100)
        for (i in 11L..16L) add(i, clear(b), addedSec = 100)
        indexer().run()
        return dao.facesOfPhoto(1).single().personId!! to dao.facesOfPhoto(11).single().personId!!
    }

    private suspend fun state(id: Long) = media.get(id)!!.syncState
    private suspend fun queuedIds() = library.keys.filter { state(it) == SyncState.QUEUED }

    // ------------------------------------------------------------------ rule off / on

    @Test fun `with no rule nothing is ever queued, however many people are found`() = runBlocking {
        libraryOfTwo()
        add(30, clear(a), addedSec = activation + 5)
        indexer().run()
        assertTrue(queuedIds().isEmpty())
        assertTrue(scheduler.backupKicks == 0)
    }

    @Test fun `a rule for new photos queues only photos that arrive after it, and only when they match`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        assertEquals(RuleResult.Applied(0, 0), service.activate(pa, includeExisting = false, RulePolicy.STRICT, emptySet()))
        assertTrue("old photos of the person are not touched", queuedIds().isEmpty())
        env.now += 10_000
        add(20, clear(a), addedSec = activation + 5)      // new, person A
        add(21, clear(b), addedSec = activation + 5)      // new, but person B has no rule
        add(22, FaceScript.Face(FaceTestKit.randomUnit(rnd)), addedSec = activation + 5) // new, a stranger
        indexer().run()
        assertEquals(listOf(20L), queuedIds())
        assertTrue("the existing backup worker was woken", scheduler.backupKicks > 0)
        // The group the user turned the rule on for counts as reviewed.
        assertEquals(PersonState.CONFIRMED, dao.person(pa)!!.state)
    }

    @Test fun `a rule that includes existing photos queues what the preview promised, and not the photos the user excluded`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        val preview = service.preview(pa, includeExisting = true, RulePolicy.STRICT, excluded = setOf(2L))
        // The group was not confirmed yet, so a preview must say that automatic matches would wait: confirm it as the screen does.
        service.activate(pa, false, RulePolicy.STRICT, emptySet()); service.deactivate(pa)
        val confirmed = service.preview(pa, includeExisting = true, RulePolicy.STRICT, excluded = setOf(2L))
        assertEquals(setOf(1L, 3L, 4L, 5L, 6L), confirmed.willQueue.map { it.mediaStoreId }.toSet())
        assertTrue("an unconfirmed group asks first: $preview", preview.willQueue.isEmpty() && preview.review.size == 5 && preview.excluded == 1)
        assertEquals(5L * "photo-1".length, confirmed.bytes)

        val r = service.activate(pa, includeExisting = true, RulePolicy.STRICT, excluded = setOf(2L)) as RuleResult.Applied
        assertEquals(5, r.queued)
        assertEquals(setOf(1L, 3L, 4L, 5L, 6L), queuedIds().toSet())
        assertEquals("the excluded photo is left alone", SyncState.LOCAL_ONLY, state(2))
        assertEquals(false, dao.rule(pa)!!.existingPending)
        assertEquals(RuleScope.INCLUDE_EXISTING, dao.rule(pa)!!.scope)
        assertEquals(JobState.COMPLETED, env.db.jobs().get(JobNames.FACE_RULES)!!.state)
    }

    @Test fun `evaluating again queues nothing twice`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        service.activate(pa, true, RulePolicy.STRICT, emptySet())
        val n = queuedIds().size
        val kicks = scheduler.backupKicks
        assertEquals(RuleResult.Applied(0, 0), service.evaluate(pa))
        service.evaluateAll(); service.evaluateAll()
        assertEquals(n, queuedIds().size)
        assertEquals("no needless wake-ups", kicks, scheduler.backupKicks)
        assertEquals(n, dao.queuedByRule(pa).size)
    }

    @Test fun `without S3 configured the rule is saved and nothing is queued, and it works once S3 is set up`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        var configured = false
        val svc = FaceBackupService(env.db, backup, settings, grouping, lock, JobRecorder(env.db) { env.now }, nowMs = { env.now }, s3Configured = { configured })
        assertEquals(RuleResult.NeedsS3, svc.activate(pa, true, RulePolicy.STRICT, emptySet()))
        assertNotNull("the user's choice is kept", dao.rule(pa))
        assertTrue(queuedIds().isEmpty())
        configured = true
        assertEquals(6, (svc.evaluate(pa) as RuleResult.Applied).queued)
    }

    // ------------------------------------------------------------------ what a rule must not do

    @Test fun `photos already backed up, queued, uploading or failed are never queued again`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        val states = mapOf(1L to SyncState.BACKED_UP, 2L to SyncState.QUEUED, 3L to SyncState.UPLOADING, 4L to SyncState.FAILED, 5L to SyncState.VERIFYING)
        for ((id, st) in states) env.db.openHelper.writableDatabase.execSQL("UPDATE media_items SET syncState = '${st.name}' WHERE mediaStoreId = $id")
        service.activate(pa, true, RulePolicy.STRICT, emptySet())
        for ((id, st) in states) assertEquals("photo $id", st, state(id))
        assertEquals(listOf(6L), dao.queuedByRule(pa))
    }

    @Test fun `a photo whose S3 copy the user deleted on purpose is not uploaded again`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        env.db.openHelper.writableDatabase.execSQL("UPDATE media_items SET backupExcluded = 1 WHERE mediaStoreId = 3")
        service.activate(pa, true, RulePolicy.STRICT, emptySet())
        assertEquals(SyncState.LOCAL_ONLY, state(3))
        assertTrue("the flag is still there", media.get(3)!!.backupExcluded)
        assertFalse(3L in queuedIds())
        assertEquals(5, queuedIds().size)
    }

    @Test fun `a photo that left the device is not queued`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        service.activate(pa, false, RulePolicy.STRICT, emptySet()); service.deactivate(pa)
        env.db.openHelper.writableDatabase.execSQL("UPDATE media_items SET present = 0 WHERE mediaStoreId = 2")
        service.activate(pa, true, RulePolicy.STRICT, emptySet())
        assertFalse(2L in queuedIds())
    }

    @Test fun `ambiguous matches go to review and are queued only when the user says yes`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        service.activate(pa, false, RulePolicy.STRICT, emptySet())
        env.now += 10_000
        // A face that is the person, but sits as close to the other person as to this one: the margin is gone.
        val mid = FaceTestKit.unit(FloatArray(a.size) { a[it] + b[it] })
        add(20, FaceScript.Face(FaceTestKit.near(mid, 0.1f, rnd), quality = 0.9f), addedSec = activation + 5)
        add(21, FaceScript.Face(FaceTestKit.at(a, 0.56f, rnd), quality = 0.9f), addedSec = activation + 5) // only a suggestion
        add(22, clear(a), addedSec = activation + 5)
        indexer().run()
        assertEquals("only the sure photo is queued", listOf(22L), queuedIds())
        val review = dao.decisions(pa).filter { it.outcome == DecisionOutcome.REVIEW }
        assertTrue("the suggestion waits for the user: ${dao.decisions(pa)}", review.any { it.mediaStoreId == 21L && it.reason == DecisionReason.LOW_CONFIDENCE })
        assertEquals(SyncState.LOCAL_ONLY, state(21))
        // The user looks at the review list.
        assertEquals(1, service.approve(pa, listOf(21L)))
        assertEquals(SyncState.QUEUED, state(21))
        service.dismiss(pa, listOf(20L))
        service.evaluate(pa)
        assertEquals("a dismissed photo is not offered again", SyncState.LOCAL_ONLY, state(20))
        assertTrue(dao.decisions(pa).none { it.mediaStoreId == 20L && it.outcome == DecisionOutcome.REVIEW })
    }

    @Test fun `a photo with two selected people is queued once, and a selected person next to a stranger is queued whole`() = runBlocking {
        val (pa, pb) = libraryOfTwo()
        service.activate(pa, false, RulePolicy.STRICT, emptySet()); service.activate(pb, false, RulePolicy.STRICT, emptySet())
        env.now += 10_000
        add(20, clear(a), clear(b), addedSec = activation + 5)
        indexer().run()
        assertEquals(listOf(20L), queuedIds())
        // The first rule to look at the photo queued it; the second found it already queued and left it alone.
        val queuedBy = listOf(pa, pb).filter { p -> dao.decisions(p).any { it.mediaStoreId == 20L && it.outcome == DecisionOutcome.QUEUED } }
        assertEquals(1, queuedBy.size)
        assertEquals("the file as it is: the whole photo, not a crop", "photo-20".length.toLong(), media.get(20)!!.sizeBytes)
        // Only A has a rule: a photo with A and a stranger is queued, as the whole photo.
        service.deactivate(pb)
        add(21, clear(a), FaceScript.Face(FaceTestKit.randomUnit(rnd)), addedSec = activation + 6)
        indexer().run()
        assertTrue(21L in queuedIds())
        assertEquals("photo-21".length.toLong(), media.get(21)!!.sizeBytes)
    }

    @Test fun `a hidden person cannot have a rule, and hiding one removes it`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        service.activate(pa, false, RulePolicy.STRICT, emptySet())
        FacePeopleController(env.db, grouping, vault, lock, nowMs = { env.now }).hide(pa, true)
        assertNull(dao.rule(pa))
        assertEquals(RuleResult.Inactive, service.activate(pa, true, RulePolicy.STRICT, emptySet()))
        assertTrue(queuedIds().isEmpty())
    }

    @Test fun `probable matches are queued only when the user accepted them`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        service.activate(pa, false, RulePolicy.STRICT, emptySet())
        env.now += 10_000
        // Exactly 0.59 to the centre of the group: placed in it automatically (0.58), but under the strict 0.60.
        val centre = com.s3vault.app.domain.faces.FaceMath.mean((1L..6L).map { script.photos[it]!!.single().vector })!!
        add(20, FaceScript.Face(FaceTestKit.at(centre, 0.59f, rnd), quality = 0.9f), addedSec = activation + 5)
        indexer().run()
        val placed = dao.facesOfPhoto(20).single()
        assertEquals(com.s3vault.app.domain.faces.FaceAssignment.AUTO, placed.assignment)
        assertEquals(pa, placed.personId)
        assertEquals("strict by default: it asks", SyncState.LOCAL_ONLY, state(20))
        assertEquals(DecisionReason.LOW_CONFIDENCE, dao.decisions(pa).single { it.mediaStoreId == 20L }.reason)
        service.setPolicy(pa, RulePolicy.INCLUDE_PROBABLE)
        assertEquals("the user accepted probable matches", SyncState.QUEUED, state(20))
        assertEquals(RulePolicy.INCLUDE_PROBABLE, dao.rule(pa)!!.policy)
    }

    // ------------------------------------------------------------------ pausing, undoing, removing

    @Test fun `a paused rule adds nothing, and resuming does not reach back to what arrived meanwhile`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        service.activate(pa, false, RulePolicy.STRICT, emptySet())
        service.pause(pa)
        env.now += 10_000
        add(20, clear(a), addedSec = activation + 1)
        indexer().run()
        assertTrue(queuedIds().isEmpty())
        env.now += 10_000
        service.resume(pa)
        assertEquals("what came while it was paused stays for the user to decide", SyncState.LOCAL_ONLY, state(20))
        env.now += 10_000
        add(21, clear(a), addedSec = activation + 1)
        indexer().run()
        assertEquals(listOf(21L), queuedIds())
        assertEquals(RuleState.ACTIVE, dao.rule(pa)!!.state)
    }

    @Test fun `turning the rule off while photos are being analysed queues nothing afterwards`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        service.activate(pa, false, RulePolicy.STRICT, emptySet())
        env.now += 10_000
        for (i in 20L..27L) add(i, clear(a), addedSec = activation + 5)
        val reached = java.util.concurrent.atomic.AtomicInteger()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        script.gate = { if (reached.incrementAndGet() == 4) release.await() }
        val run = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { indexer().run() }
        while (reached.get() < 4) kotlinx.coroutines.yield()
        service.deactivate(pa)          // the user turns the rule off in the middle of the analysis
        release.complete(Unit)
        run.await()
        assertEquals("all eight photos were still analysed", 8, (20L..27L).count { dao.photo(it) != null })
        assertTrue("but none was queued by a rule that no longer exists", queuedIds().isEmpty())
        assertNull(dao.rule(pa))
    }

    @Test fun `undo takes back what has not started uploading and leaves everything else exactly as it is`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        service.activate(pa, true, RulePolicy.STRICT, emptySet())
        env.db.openHelper.writableDatabase.execSQL("UPDATE media_items SET syncState = 'UPLOADING' WHERE mediaStoreId = 1")
        env.db.openHelper.writableDatabase.execSQL("UPDATE media_items SET syncState = 'BACKED_UP' WHERE mediaStoreId = 2")
        assertEquals(4, service.undo(pa))
        assertEquals(SyncState.UPLOADING, state(1))
        assertEquals(SyncState.BACKED_UP, state(2))
        assertEquals((3L..6L).toList(), library.keys.filter { it in 3L..6L && state(it) == SyncState.LOCAL_ONLY })
        assertTrue("back to how they were: not marked as excluded", (3L..6L).none { media.get(it)!!.backupExcluded })
        assertNull(dao.rule(pa))
        assertTrue(env.remote.deleteCalls.isEmpty())
    }

    @Test fun `turning a rule off or deleting the person never deletes a photo, a backup or an S3 object`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        service.activate(pa, true, RulePolicy.STRICT, emptySet())
        val before = library.keys.associateWith { state(it) }
        service.deactivate(pa)
        assertEquals("queued photos stay queued: that is the user's call (undo)", before, library.keys.associateWith { state(it) })
        FacePeopleController(env.db, grouping, vault, lock, nowMs = { env.now }).deletePerson(pa)
        assertEquals(before, library.keys.associateWith { state(it) })
        assertEquals(library.size, library.keys.count { media.get(it)!!.present })
        assertTrue(env.remote.deleteCalls.isEmpty())
        assertEquals(library.size, env.reader.files.size)
    }

    @Test fun `removing a photo from the group changes what a rule will do next time, not what it already did`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        service.activate(pa, false, RulePolicy.STRICT, emptySet())
        FacePeopleController(env.db, grouping, vault, lock, nowMs = { env.now }).removePhotos(pa, listOf(1L, 2L))
        assertTrue(dao.facesOfPhoto(1).single().personId == null)
        assertEquals(SyncState.LOCAL_ONLY, state(1))
    }

    // ------------------------------------------------------------------ the one-time pass over existing photos

    @Test fun `the one-time pass can be stopped in the middle and picks up where it left off, also after a restart`() = runBlocking {
        for (i in 1L..450L) add(i, clear(a), addedSec = 100)
        indexer().run(maxPhotos = 1000)
        val pa = dao.people().single().id
        service.activate(pa, false, RulePolicy.STRICT, emptySet()); service.deactivate(pa) // confirms the group, as the screen does
        // Stop after the first batch of 200.
        val interrupted = FaceBackupService(env.db, backup, settings, grouping, lock, JobRecorder(env.db) { env.now }, nowMs = { env.now }, afterBatch = { throw kotlinx.coroutines.CancellationException("stop") })
        try { interrupted.activate(pa, true, RulePolicy.STRICT, emptySet()) } catch (e: kotlinx.coroutines.CancellationException) { }
        assertEquals(200, queuedIds().size)
        assertEquals(true, dao.rule(pa)!!.existingPending)
        assertEquals(JobState.CANCELLED, env.db.jobs().get(JobNames.FACE_RULES)!!.state)
        // The process restarts: the rule is evaluated again and finishes the job, queuing the rest once.
        assertEquals(RuleResult.Applied(250, 0), service.evaluate(pa))
        assertEquals(450, queuedIds().size)
        assertEquals(false, dao.rule(pa)!!.existingPending)
    }

    @Test fun `stopping the one-time pass from the screen leaves what was queued and queues no more`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        service.activate(pa, false, RulePolicy.STRICT, emptySet()); service.deactivate(pa)
        env.db.faces().upsertRule(com.s3vault.app.data.local.FaceBackupRuleEntity(pa, RuleState.ACTIVE, RuleScope.INCLUDE_EXISTING, RulePolicy.STRICT, env.now, activation, existingPending = true, updatedAtMs = env.now))
        service.cancelExistingPass(pa)
        service.evaluate(pa)
        assertTrue(queuedIds().isEmpty())
    }

    // ------------------------------------------------------------------ the real backup engine behind the queue

    private fun engine() = env.engine()

    @Test fun `queued photos are uploaded by the existing engine, once, under the configured prefix, with nothing about faces`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        service.activate(pa, true, RulePolicy.STRICT, emptySet())
        val outcome = engine().run(maxConcurrency = 2)
        assertTrue(outcome is RunOutcome.Completed && outcome.uploaded == 6)
        assertEquals(6, library.keys.count { state(it) == SyncState.BACKED_UP })
        assertTrue(env.remote.uploadedKeys.all { it.startsWith("mi/prefijo/photos/") })
        assertEquals("one object per photo: nothing else was uploaded", 6, env.remote.objects.size)
        for (o in env.remote.objects.values) assertFalse("no biometric data in S3", String(o.bytes).contains("face") && String(o.bytes).length > 100)
        // The rule has nothing left to do and asks the queue for nothing.
        assertEquals(RuleResult.Applied(0, 0), service.evaluate(pa))
        assertEquals(RunOutcome.Completed(0, 0, 0), engine().run(2))
    }

    @Test fun `when the network drops the photos stay queued, the retry uploads each once, and the rule adds nothing`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        service.activate(pa, true, RulePolicy.STRICT, emptySet())
        env.remote.uploadFailures.addAll(List(40) { RemoteException(RemoteErrorKind.NETWORK) })
        val first = engine().run(2)
        assertTrue("$first", first is RunOutcome.RetryLater)
        assertTrue("nothing failed for good: the photos wait in the queue", (1L..6L).all { state(it) == SyncState.QUEUED })
        assertEquals(RuleResult.Applied(0, 0), service.evaluate(pa)) // evaluating during the outage changes nothing
        env.remote.uploadFailures.clear()
        val second = engine().run(2) as RunOutcome.Completed
        assertEquals(6, second.uploaded)
        assertEquals(6, env.remote.objects.size)
        assertEquals(6, library.keys.count { state(it) == SyncState.BACKED_UP })
    }

    @Test fun `a permission error from AWS stops the run with the queue intact and the rule does not requeue or hammer`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        service.activate(pa, true, RulePolicy.STRICT, emptySet())
        env.remote.uploadFailures.addAll(List(40) { RemoteException(RemoteErrorKind.ACCESS_DENIED) })
        val out = engine().run(2)
        assertTrue("$out", out is RunOutcome.Blocked)
        assertTrue("no photo was lost or marked backed up", library.keys.none { state(it) == SyncState.BACKED_UP })
        assertEquals(RuleResult.Applied(0, 0), service.evaluate(pa))
        assertTrue(env.remote.objects.isEmpty())
    }

    @Test fun `a rule evaluated while the user pauses the backup queues photos but wakes nothing`() = runBlocking {
        val (pa, _) = libraryOfTwo()
        backup.pause()
        val kicks = scheduler.backupKicks
        service.activate(pa, true, RulePolicy.STRICT, emptySet())
        assertEquals(6, queuedIds().size)
        assertEquals("a paused backup is not woken", kicks, scheduler.backupKicks)
    }
}
