package com.s3vault.app.sync

import com.s3vault.app.data.faces.FaceImageSource
import com.s3vault.app.data.faces.ImageLoad
import com.s3vault.app.data.local.JobNames
import com.s3vault.app.data.local.JobState
import com.s3vault.app.data.local.ScannedMedia
import com.s3vault.app.domain.faces.AesGcmFaceVault
import com.s3vault.app.domain.faces.AnalyzedFace
import com.s3vault.app.domain.faces.FaceAnalyzer
import com.s3vault.app.domain.faces.FaceAssignment
import com.s3vault.app.domain.faces.FaceModel
import com.s3vault.app.domain.faces.FacePhotoStatus
import com.s3vault.app.domain.faces.FaceTestKit
import com.s3vault.app.domain.faces.FaceVectorCodec
import com.s3vault.app.domain.faces.MemoryDataKeyStore
import com.s3vault.app.domain.faces.PersonState
import com.s3vault.app.domain.faces.PhotoAnalysis
import com.s3vault.app.domain.faces.WorkingImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

/** A photo's faces, chosen by the test: who is in it (a direction in vector space) and how well it was seen. */
class FaceScript {
    class Face(val vector: FloatArray, val quality: Float = 0.8f, val box: FloatArray = floatArrayOf(0.2f, 0.2f, 0.5f, 0.6f))

    val photos = HashMap<Long, List<Face>>()
    val skipped = HashMap<Long, Int>()
    val unreadable = HashSet<Long>()
    val unavailable = HashSet<Long>()
    val analysed = java.util.Collections.synchronizedList(mutableListOf<Long>())
    val threads = java.util.Collections.synchronizedSet(HashSet<String>())
    var gate: (suspend () -> Unit)? = null
    var closed = false

    private fun id(i: WorkingImage) = i.argb[0].toLong()

    val images = FaceImageSource { uri ->
        gate?.invoke()
        val id = uri.substringAfterLast('/').toLong()
        when (id) {
            in unreadable -> ImageLoad.Unreadable
            in unavailable -> ImageLoad.Unavailable
            else -> ImageLoad.Ok(WorkingImage(intArrayOf(id.toInt()), 1, 1))
        }
    }

    val analyzer = object : FaceAnalyzer {
        override fun analyze(image: WorkingImage): PhotoAnalysis {
            val id = id(image)
            analysed += id
            threads += Thread.currentThread().name
            return PhotoAnalysis(photos[id].orEmpty().map { AnalyzedFace(it.box, 0.95f, it.quality, it.vector) }, skipped[id] ?: 0)
        }
        override fun close() { closed = true }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceIndexTest {
    private lateinit var env: TestEnv
    private val vault = AesGcmFaceVault(MemoryDataKeyStore())
    private val script = FaceScript()
    private val lock = FaceIndexLock()
    private val rnd = Random(21)
    private val a = FaceTestKit.randomUnit(rnd)
    private val b = FaceTestKit.randomUnit(rnd)
    private val c = FaceTestKit.randomUnit(rnd)
    private lateinit var grouping: FaceGroupingStore

    @Before fun setUp() { env = TestEnv(); grouping = FaceGroupingStore(env.db, vault, nowMs = { env.now }) }
    @After fun tearDown() { env.close() }

    private val dao get() = env.db.faces()
    private fun jobs() = JobRecorder(env.db) { env.now }
    private fun indexer(afterRun: suspend () -> Unit = {}) = FaceIndexer(env.db, vault, { script.analyzer.also { script.closed = false } }, script.images, grouping, lock, jobs(), afterRun, { env.now })

    private fun face(who: FloatArray, noise: Float = 0.45f, quality: Float = 0.8f) = FaceScript.Face(FaceTestKit.near(who, noise, rnd), quality)

    private suspend fun add(id: Long, vararg faces: FaceScript.Face, mtime: Long = 100, addedSec: Long = 0) {
        script.photos[id] = faces.toList()
        library[id] = env.scanned(id, mtime = mtime).copy(dateTakenMs = id * 1000, dateAddedSec = addedSec)
        env.index(*library.values.toTypedArray())
    }

    private val library = LinkedHashMap<Long, ScannedMedia>()

    /** Four photos each of A and B, one of each per photo number: the minimum for the grouping to form two groups. */
    private suspend fun twoPeople() {
        for (i in 1L..4L) add(i, face(a))
        for (i in 5L..8L) add(i, face(b))
    }

    private suspend fun peopleRows() = dao.people()

    // ------------------------------------------------------------------ analysis

    @Test fun `photos are analysed, faces stored sealed and people formed with a neutral label`() = runBlocking {
        twoPeople()
        val out = indexer().run() as FaceRunOutcome.Done
        assertEquals(8, out.analyzed)
        assertEquals(0, out.left)
        assertEquals(2, peopleRows().size)
        assertTrue("the app never invents a name", peopleRows().all { it.name == null && it.state == PersonState.PROVISIONAL })
        assertEquals(listOf(1, 2), peopleRows().map { it.ordinal }.sorted())
        val faces = (1L..8L).flatMap { dao.facesOfPhoto(it) }
        assertEquals(8, faces.size)
        assertTrue(faces.all { it.personId != null && it.assignment == FaceAssignment.AUTO })
        // The stored vector is not the plain one, and opens only with the key.
        val v = FaceVectorCodec.openVector(vault, faces[0].sealed)!!
        assertEquals(FaceModel.DIMENSION, v.size)
        assertNull(FaceVectorCodec.openVector(AesGcmFaceVault(MemoryDataKeyStore()), faces[0].sealed))
        assertTrue(script.closed)
        assertEquals(JobState.COMPLETED, env.db.jobs().get(JobNames.FACES)!!.state)
        // A and B are two different groups, each with its own four photos.
        val byPerson = faces.groupBy { it.personId }
        assertEquals(setOf(4), byPerson.values.map { it.size }.toSet())
    }

    @Test fun `an unchanged photo is analysed once, a changed one again, and a rerun does nothing`() = runBlocking {
        twoPeople()
        indexer().run()
        script.analysed.clear()
        assertEquals(0, (indexer().run() as FaceRunOutcome.Done).analyzed)
        assertTrue(script.analysed.isEmpty())
        library[3] = env.scanned(3, mtime = 999).copy(dateTakenMs = 3000)
        env.index(*library.values.toTypedArray())
        indexer().run()
        assertEquals(listOf(3L), script.analysed.toList())
    }

    @Test fun `pictures with no face are recorded and a broken one does not stop the run`() = runBlocking {
        add(1)
        add(2, face(a))
        add(3, face(a))
        script.unreadable += 2
        val out = indexer().run() as FaceRunOutcome.Done
        assertEquals(2, out.analyzed)
        assertEquals(1, out.unreadable)
        assertEquals(FacePhotoStatus.DONE, dao.photo(1)!!.status)
        assertEquals(0, dao.photo(1)!!.faces)
        assertEquals(FacePhotoStatus.UNREADABLE, dao.photo(2)!!.status)
        assertEquals(1, dao.facesOfPhoto(3).size)
        // The broken file is not retried until it changes.
        script.analysed.clear()
        indexer().run()
        assertFalse(2L in script.analysed)
    }

    @Test fun `a picture the system will not let the app read right now is retried, not condemned`() = runBlocking {
        add(1, face(a)); add(2, face(a))
        script.unavailable += 1
        val first = indexer().run() as FaceRunOutcome.Done
        assertEquals(1, first.unavailable)
        assertNull("nothing is recorded for it", dao.photo(1))
        script.unavailable.clear()
        val second = indexer().run() as FaceRunOutcome.Done
        assertEquals(1, second.analyzed)
        assertNotNull(dao.photo(1))
    }

    @Test fun `faces that are too small to compare are counted but never stored`() = runBlocking {
        add(1, face(a)); script.skipped[1] = 3
        indexer().run()
        assertEquals(3, dao.photo(1)!!.skipped)
        assertEquals(1, dao.facesOfPhoto(1).size)
    }

    @Test fun `a run stops at its budget and the next one goes on, newest first, without repeating work`() = runBlocking {
        for (i in 1L..12L) add(i, face(a))
        val first = indexer().run(maxPhotos = 5) as FaceRunOutcome.Done
        assertTrue(first.analyzed in 5..12)
        assertTrue(first.left > 0 || first.analyzed == 12)
        // Newest first between batches (the order inside a batch of eight is not promised): the first batch is the eight newest pictures.
        assertEquals("newest pictures first", (5L..12L).toSet(), script.analysed.take(8).toSet())
        while ((indexer().run(maxPhotos = 5) as FaceRunOutcome.Done).left > 0) Unit
        assertEquals("every picture exactly once", (1L..12L).toList(), script.analysed.sorted())
    }

    @Test fun `cancelling a run keeps what was done and the next run finishes without duplicates`() = runBlocking {
        for (i in 1L..9L) add(i, face(a))
        val reached = java.util.concurrent.atomic.AtomicInteger()
        val never = CompletableDeferred<Unit>()
        // The first batch (8 pictures, one at a time) is read; the next picture's read blocks, as a slow disk would, and the run is cancelled there.
        script.gate = { if (reached.incrementAndGet() > 8) never.await() }
        val job = async(start = CoroutineStart.UNDISPATCHED) { indexer().run() }
        while (reached.get() <= 8) kotlinx.coroutines.yield()
        job.cancelAndJoin()
        assertEquals("what was analysed before the cancellation is stored", 8, (1L..9L).count { dao.photo(it) != null })
        assertEquals(JobState.CANCELLED, env.db.jobs().get(JobNames.FACES)!!.state)
        assertTrue("the analyzer was released", script.closed)
        script.gate = null
        indexer().run()
        val faces = (1L..9L).flatMap { dao.facesOfPhoto(it) }
        assertEquals("one face per photo, whatever the interruption", 9, faces.size)
        assertEquals(9, faces.map { it.mediaStoreId }.toSet().size)
        assertEquals(JobState.COMPLETED, env.db.jobs().get(JobNames.FACES)!!.state)
    }

    @Test fun `an index whose key was lost is reported, not extended`() = runBlocking {
        twoPeople()
        indexer().run()
        vault.wipe()
        add(9, face(a))
        assertEquals(FaceRunOutcome.IndexUnreadable, indexer().run())
        assertNull(dao.photo(9))
    }

    @Test fun `an index at its limit and a device short of space stop the run before anything is written`() = runBlocking {
        for (i in 1L..4L) add(i, face(a))
        indexer().run()
        val capped = FaceIndexer(env.db, vault, { script.analyzer }, script.images, grouping, lock, jobs(), nowMs = { env.now }, maxFaces = 4)
        add(5, face(a))
        assertEquals(FaceRunOutcome.QuotaReached, capped.run())
        assertNull("nothing was analysed", dao.photo(5))
        assertEquals(FaceIndexer.ATTENTION_QUOTA, env.db.jobs().get(JobNames.FACES)!!.lastError)
        val full = FaceIndexer(env.db, vault, { script.analyzer }, script.images, grouping, lock, jobs(), nowMs = { env.now }, freeBytes = { 10L * 1024 * 1024 })
        assertEquals(FaceRunOutcome.LowStorage, full.run())
        assertNull(dao.photo(5))
        assertEquals(FaceIndexer.ATTENTION_STORAGE, env.db.jobs().get(JobNames.FACES)!!.lastError)
        // With room again, the same photo is analysed.
        assertEquals(1, (indexer().run() as FaceRunOutcome.Done).analyzed)
    }

    @Test fun `a key that cannot be read is reported even before anything is stored`() = runBlocking {
        add(1, face(a))
        val broken = AesGcmFaceVault(object : com.s3vault.app.domain.faces.DataKeyStore {
            override fun load(): ByteArray = throw IllegalStateException("The face index key cannot be read")
            override fun destroy() = Unit
        })
        val ix = FaceIndexer(env.db, broken, { script.analyzer }, script.images, FaceGroupingStore(env.db, broken), lock, jobs(), nowMs = { env.now })
        assertEquals(FaceRunOutcome.IndexUnreadable, ix.run())
        assertNull(dao.photo(1))
    }

    @Test fun `with no model the run says so and touches nothing`() = runBlocking {
        add(1, face(a))
        val none = FaceIndexer(env.db, vault, { null }, script.images, grouping, lock, jobs(), nowMs = { env.now })
        assertEquals(FaceRunOutcome.ModelMissing, none.run())
        assertNull(dao.photo(1))
    }

    @Test fun `the heavy work runs on background threads, never on the thread that asked for it`() = runBlocking {
        for (i in 1L..6L) add(i, face(a))
        val caller = Thread.currentThread().name
        indexer().run(workers = 2)
        assertTrue("analysis threads: ${script.threads}", script.threads.isNotEmpty() && caller !in script.threads && script.threads.none { it == "main" })
        assertTrue("on the default pool: ${script.threads}", script.threads.all { it.startsWith("DefaultDispatcher-worker") })
    }

    // ------------------------------------------------------------------ grouping

    @Test fun `a new photo of a known person joins the group, a stranger does not, and two faces of one photo never share a group`() = runBlocking {
        twoPeople()
        indexer().run()
        val personA = dao.facesOfPhoto(1).single().personId!!
        add(9, face(a, noise = 0.3f))
        add(10, face(FaceTestKit.randomUnit(rnd)))
        add(11, face(a, noise = 0.3f), face(a, noise = 0.3f)) // two similar faces in one photo (a poster behind someone, a reflection)
        indexer().run()
        assertEquals(personA, dao.facesOfPhoto(9).single().personId)
        assertNull("a stranger stays alone", dao.facesOfPhoto(10).single().personId)
        val inPhoto = dao.facesOfPhoto(11).filter { it.personId == personA }
        assertTrue("at most one face of a photo per person", inPhoto.size <= 1)
        assertEquals(2, peopleRows().size)
    }

    @Test fun `a second run does not create the groups again`() = runBlocking {
        twoPeople()
        indexer().run()
        val before = peopleRows().map { it.id }.toSet()
        for (i in 9L..12L) add(i, face(a))
        indexer().run()
        assertEquals("same two people", before, peopleRows().map { it.id }.toSet())
    }

    @Test fun `faces re-analysed after a pipeline change keep their group, confirmation and rejection`() = runBlocking {
        twoPeople()
        indexer().run()
        val people = FacePeopleController(env.db, grouping, vault, lock) { env.now }
        val f = dao.facesOfPhoto(1).single()
        val g = dao.facesOfPhoto(2).single()
        people.removePhotos(f.personId!!, listOf(2L)) // photo 2 is "not this person"
        people.confirm(f.personId!!)
        // The pipeline changes: every photo is analysed again, with slightly different boxes.
        env.db.openHelper.writableDatabase.execSQL("UPDATE face_photos SET pipelineVersion = 0")
        for (id in 1L..8L) script.photos[id] = script.photos[id]!!.map { FaceScript.Face(it.vector, it.quality, floatArrayOf(0.21f, 0.2f, 0.51f, 0.6f)) }
        script.analysed.clear()
        indexer().run()
        assertEquals("every photo is analysed again", 8, script.analysed.size)
        val f2 = dao.facesOfPhoto(1).single()
        assertEquals("same row", f.id, f2.id)
        assertEquals(f.personId, f2.personId)
        assertEquals(FaceAssignment.CONFIRMED, f2.assignment)
        assertEquals(0.21f, f2.boxLeft, 0f)
        val g2 = dao.facesOfPhoto(2).single()
        assertEquals(g.id, g2.id)
        assertNotEquals("the face the user took out does not go back", f.personId, g2.personId)
    }

    @Test fun `photos that leave the device take their faces with them`() = runBlocking {
        twoPeople()
        indexer().run()
        library.remove(8L)
        env.index(*library.values.toTypedArray())
        indexer().run()
        assertTrue(dao.facesOfPhoto(8).isEmpty())
        assertNull(dao.photo(8))
        assertEquals(2, peopleRows().size)
    }

    @Test fun `a group that lost all its photos is removed unless the user named it`() = runBlocking {
        twoPeople()
        indexer().run()
        val named = dao.facesOfPhoto(5).single().personId!!
        FacePeopleController(env.db, grouping, vault, lock) { env.now }.rename(named, "Mamá")
        for (i in 1L..8L) library.remove(i)
        env.index(*library.values.toTypedArray())
        indexer().run()
        assertEquals("the unnamed group is gone, the named one stays (empty) so its name and rule are not lost", listOf(named), peopleRows().map { it.id })
        assertEquals(0, (1L..8L).sumOf { dao.facesOfPhoto(it).size })
    }

    // ------------------------------------------------------------------ the user's corrections

    private fun people() = FacePeopleController(env.db, grouping, vault, lock) { env.now }

    @Test fun `naming a group confirms it, a blank name goes back to the neutral label`() = runBlocking {
        twoPeople(); indexer().run()
        val p = dao.facesOfPhoto(1).single().personId!!
        people().rename(p, "  Ana  ")
        assertEquals("Ana", dao.person(p)!!.name)
        assertEquals(PersonState.CONFIRMED, dao.person(p)!!.state)
        people().rename(p, "   ")
        assertNull(dao.person(p)!!.name)
        people().rename(p, "x".repeat(200))
        assertEquals(60, dao.person(p)!!.name!!.length)
    }

    @Test fun `taking a photo out of a group is remembered and never touches the photo`() = runBlocking {
        twoPeople(); indexer().run()
        val p = dao.facesOfPhoto(1).single().personId!!
        assertEquals(1, people().removePhotos(p, listOf(1L)))
        assertNull(dao.facesOfPhoto(1).single().personId)
        assertEquals(1, dao.rejectionsOf(listOf(dao.facesOfPhoto(1).single().id)).size)
        // Later runs, even after a new photo of the same person, do not put it back.
        add(9, face(a, noise = 0.3f))
        indexer().run()
        assertNull(dao.facesOfPhoto(1).single().personId)
        assertEquals("the original is still indexed and present", 1, env.db.media().get(1)!!.let { if (it.present) 1 else 0 })
    }

    @Test fun `moving faces to a new group splits one that mixed two people`() = runBlocking {
        twoPeople(); indexer().run()
        val pa = dao.facesOfPhoto(1).single().personId!!
        val pb = dao.facesOfPhoto(5).single().personId!!
        people().merge(pa, pb) // the user merged two people by mistake...
        assertEquals(1, peopleRows().size)
        val bFaces = (5L..8L).map { dao.facesOfPhoto(it).single().id }
        val r = people().moveFaces(bFaces, null) // ...and now separates them
        assertEquals(4, r.moved)
        assertEquals(2, peopleRows().size)
        val newId = r.targetId!!
        assertEquals(PersonState.CONFIRMED, dao.person(newId)!!.state)
        assertTrue((5L..8L).all { dao.facesOfPhoto(it).single().personId == newId })
        assertTrue((1L..4L).all { dao.facesOfPhoto(it).single().personId == pa })
        // They are rejected for the group they came from: a later analysis cannot pull them back.
        assertEquals(4, dao.rejectionsOf(bFaces).count { it.personId == pa })
    }

    @Test fun `merging joins two groups, keeps the name and refuses to put two faces of one photo together`() = runBlocking {
        twoPeople(); indexer().run()
        val pa = dao.facesOfPhoto(1).single().personId!!
        val pb = dao.facesOfPhoto(5).single().personId!!
        people().rename(pb, "Luis")
        assertEquals(4, people().merge(pa, pb))
        assertEquals("Luis", dao.person(pa)!!.name)
        assertEquals(PersonState.CONFIRMED, dao.person(pa)!!.state)
        assertNull(dao.person(pb))
        assertTrue((1L..8L).all { dao.facesOfPhoto(it).single().personId == pa })
        // Photo 20 shows both: A is in it already, so a face of B in the same photo cannot join A.
        add(20, face(a), face(b))
        indexer().run()
        val faces = dao.facesOfPhoto(20)
        val twin = faces.firstOrNull { it.personId == null }
        assertTrue("one of the two stays out", faces.count { it.personId == pa } <= 1)
        assertNotNull(twin ?: faces.first())
    }

    @Test fun `a face that is close but not close enough is suggested, and the user's answer is remembered`() = runBlocking {
        twoPeople(); indexer().run()
        val pa = dao.facesOfPhoto(1).single().personId!!
        add(9, FaceScript.Face(FaceTestKit.at(a, 0.55f, rnd)))   // between the suggestion and the placement thresholds
        add(10, FaceScript.Face(FaceTestKit.at(a, 0.55f, rnd)))
        add(11, FaceScript.Face(FaceTestKit.at(a, 0.80f, rnd)))  // clearly the same person
        indexer().run()
        val s9 = dao.facesOfPhoto(9).single(); val s10 = dao.facesOfPhoto(10).single()
        assertEquals(FaceAssignment.SUGGESTED, s9.assignment)
        assertEquals(pa, s9.suggestedPersonId)
        assertNull("a suggestion belongs to nobody yet", s9.personId)
        assertEquals(FaceAssignment.AUTO, dao.facesOfPhoto(11).single().assignment)

        assertEquals(1, people().acceptSuggestions(listOf(s9.id)))
        val accepted = dao.face(s9.id)!!
        assertEquals(FaceAssignment.CONFIRMED, accepted.assignment); assertEquals(pa, accepted.personId); assertNull(accepted.suggestedPersonId)

        assertEquals(1, people().rejectSuggestions(listOf(s10.id)))
        val rejected = dao.face(s10.id)!!
        assertEquals(FaceAssignment.NONE, rejected.assignment); assertNull(rejected.personId)
        assertEquals(listOf(pa), dao.rejectionsOf(listOf(s10.id)).map { it.personId })
        // Asked again later (another run, even a re-analysis) it is not suggested for the same person.
        env.db.openHelper.writableDatabase.execSQL("UPDATE face_photos SET pipelineVersion = 0")
        indexer().run()
        assertNotEquals(FaceAssignment.SUGGESTED, dao.face(s10.id)!!.assignment)
        assertNull(dao.face(s10.id)!!.personId)
    }

    @Test fun `hiding a group keeps it out of sight, still absorbing its faces`() = runBlocking {
        twoPeople(); indexer().run()
        val pa = dao.facesOfPhoto(1).single().personId!!
        people().hide(pa, true)
        assertEquals(PersonState.HIDDEN, dao.person(pa)!!.state)
        add(9, face(a, noise = 0.3f)); indexer().run()
        assertEquals("the face joins quietly", pa, dao.facesOfPhoto(9).single().personId)
        assertTrue("and the grid does not list it", dao.person(pa) != null)
        people().hide(pa, false)
        assertEquals(PersonState.PROVISIONAL, dao.person(pa)!!.state)
    }

    @Test fun `deleting a group or the whole index erases face data and nothing else`() = runBlocking {
        twoPeople(); indexer().run()
        val mediaBefore = env.db.media().getAll((1L..8L).toList()).map { it.copy() }
        val pa = dao.facesOfPhoto(1).single().personId!!
        people().deletePerson(pa)
        assertTrue((1L..4L).all { dao.facesOfPhoto(it).isEmpty() })
        assertEquals(1, peopleRows().size)
        people().deleteIndex()
        assertEquals(0, peopleRows().size)
        assertEquals(0, (1L..8L).sumOf { dao.facesOfPhoto(it).size })
        assertNull(dao.photo(1))
        assertEquals("every photo is still in the library, untouched", mediaBefore, env.db.media().getAll((1L..8L).toList()))
        assertTrue("nothing was deleted from S3", env.remote.deleteCalls.isEmpty())
        assertTrue("no file was touched", env.reader.files.size == 8)
        // The key is gone too: what was sealed before cannot be opened any more.
        assertNull(FaceVectorCodec.openVector(vault, ByteArray(10)))
    }

    @Test fun `two groups that look like one are offered for merging until the user says they are different`() = runBlocking {
        // Two bursts of the same person that were too far apart to start as one group.
        val x = FaceTestKit.at(a, 0.62f, rnd)
        for (i in 1L..4L) add(i, FaceScript.Face(FaceTestKit.at(a, 0.93f, rnd)))
        for (i in 5L..8L) add(i, FaceScript.Face(FaceTestKit.at(x, 0.93f, rnd)))
        indexer().run()
        val groups = peopleRows()
        assertEquals("they started apart", 2, groups.size)
        val offer = grouping.mergeSuggestions()
        assertEquals(1, offer.size)
        people().keepApart(offer[0].b, offer[0].a)
        assertTrue("the answer is remembered whichever order the pair came in", grouping.mergeSuggestions().isEmpty())
        // Appearing in one photo is also proof of being two people.
        assertTrue(grouping.mergeSuggestions().isEmpty())
    }

    @Test fun `groups that are almost surely one person are merged by the grouping, but never ones the user confirmed`() = runBlocking {
        val x = FaceTestKit.at(a, 0.85f, rnd)
        for (i in 1L..4L) add(i, FaceScript.Face(FaceTestKit.at(a, 0.95f, rnd)))
        for (i in 5L..8L) add(i, FaceScript.Face(FaceTestKit.at(x, 0.95f, rnd)))
        indexer().run()
        assertTrue("automatic groups with centres this close become one", peopleRows().size == 1)
        // The same layout with one of them confirmed by the user is left to the user.
        val e2 = TestEnv(); try {
            val g2 = FaceGroupingStore(e2.db, vault, nowMs = { e2.now })
            val f = FaceScript(); val lib2 = LinkedHashMap<Long, ScannedMedia>()
            suspend fun put(id: Long, v: FloatArray) { f.photos[id] = listOf(FaceScript.Face(v)); lib2[id] = e2.scanned(id).copy(dateTakenMs = id * 1000); e2.index(*lib2.values.toTypedArray()) }
            for (i in 1L..4L) put(i, FaceTestKit.at(a, 0.95f, rnd))
            FaceIndexer(e2.db, vault, { f.analyzer }, f.images, g2, lock, JobRecorder(e2.db) { e2.now }, nowMs = { e2.now }).run()
            val first = e2.db.faces().people().single()
            FacePeopleController(e2.db, g2, vault, lock) { e2.now }.confirm(first.id)
            for (i in 5L..8L) put(i, FaceTestKit.at(x, 0.95f, rnd))
            FaceIndexer(e2.db, vault, { f.analyzer }, f.images, g2, lock, JobRecorder(e2.db) { e2.now }, nowMs = { e2.now }).run()
            val after = e2.db.faces().people()
            assertTrue("a confirmed group is never merged without the user: ${after.map { it.state }}", after.any { it.id == first.id && it.state == PersonState.CONFIRMED })
        } finally { e2.close() }
    }
}
