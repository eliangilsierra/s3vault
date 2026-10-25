package com.s3vault.app.sync

import com.s3vault.app.data.local.AnalysisStatus
import com.s3vault.app.data.local.DuplicateIgnoredEntity
import com.s3vault.app.data.local.DuplicateKind
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.duplicates.PerceptualHash
import com.s3vault.app.domain.duplicates.Synth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DuplicateScannerTest {
    private lateinit var env: TestEnv
    private val lumas = mutableMapOf<String, FloatArray>()
    private val decoded: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() { env.close() }

    private val signatures = ImageSignatureSource { uri -> decoded += uri; lumas[uri] }
    private fun scanner() = DuplicateScanner(env.db, env.reader, signatures, JobRecorder(env.db) { env.now }, nowMs = { env.now }, concurrency = 2)

    /** The library as MediaStore reports it: indexing a list drops what is not in it, so every call re-reads the whole library. */
    private val library = LinkedHashMap<Long, com.s3vault.app.data.local.ScannedMedia>()

    private suspend fun add(id: Long, bytes: ByteArray, name: String = "IMG_$id.jpg", mtime: Long = 100, scene: Int? = null, kind: MediaKind = MediaKind.IMAGE) {
        library[id] = env.scanned(id, name, bytes, mtime, kind)
        env.index(*library.values.toTypedArray())
        if (scene != null) lumas["content://media/$id"] = Synth.scene(scene)
    }

    private suspend fun groups(kind: DuplicateKind) = env.db.duplicates().observeGroups(kind, 100).first()
    private suspend fun membersOf(groupId: Long) = env.db.duplicates().observeMembers(listOf(groupId)).first().map { it.media.mediaStoreId }.toSet()

    // ------------------------------------------------------------------ exact duplicates

    @Test fun `identical bytes are found even with different names and dates, different bytes are not`() = runBlocking {
        val same = ByteArray(2000) { (it % 251).toByte() }
        add(1, same, "IMG_0001.jpg", mtime = 100)
        add(2, same.copyOf(), "Copia de foto (1).jpg", mtime = 999)
        add(3, ByteArray(2000) { (it % 13).toByte() }, "IMG_0001.jpg", mtime = 100) // same size, same name, other content
        add(4, "short".toByteArray(), "IMG_0001.jpg")                                   // same name, other size

        scanner().run()

        val exact = groups(DuplicateKind.EXACT)
        assertEquals(1, exact.size)
        assertEquals(setOf(1L, 2L), membersOf(exact.single().id))
        assertEquals("only files that share a size were read", setOf("content://media/1", "content://media/2", "content://media/3"), env.reader.opens.toSet())
        assertFalse("a lone file is never hashed", env.reader.opens.contains("content://media/4"))
    }

    @Test fun `an unchanged file is never hashed twice and a changed one is hashed again`() = runBlocking {
        val bytes = ByteArray(500) { it.toByte() }
        add(1, bytes); add(2, bytes.copyOf())
        scanner().run()
        val first = env.reader.opens.size
        scanner().run()
        assertEquals("the second scan read nothing", first, env.reader.opens.size)

        // File 2 is edited: same size, new modification time.
        add(2, ByteArray(500) { 7 }, mtime = 200)
        scanner().run()
        assertEquals("only the edited file was read again", first + 1, env.reader.opens.size)
        assertTrue("and it is no longer a duplicate", groups(DuplicateKind.EXACT).isEmpty())
    }

    @Test fun `a hash computed by the backup is reused and not recomputed`() = runBlocking {
        val bytes = ByteArray(500) { it.toByte() }
        add(1, bytes); add(2, bytes.copyOf())
        env.enqueue(1); env.engine().run(2) // the backup hashed file 1
        val opens = env.reader.opens.count { it == "content://media/1" }
        scanner().run()
        assertEquals("file 1 was not read again by the duplicate scan", opens, env.reader.opens.count { it == "content://media/1" })
        assertEquals(1, groups(DuplicateKind.EXACT).size)
    }

    @Test fun `an unreadable file is skipped without stopping the scan`() = runBlocking {
        val bytes = ByteArray(300) { it.toByte() }
        add(1, bytes); add(2, bytes.copyOf()); add(3, bytes.copyOf())
        env.reader.files.remove("content://media/3")
        val r = scanner().run()
        assertEquals(2, r.hashed)
        assertEquals(setOf(1L, 2L), membersOf(groups(DuplicateKind.EXACT).single().id))
    }

    @Test fun `the run is bounded and the next one continues where it stopped`() = runBlocking {
        repeat(10) { i -> val b = ByteArray(100) { (i / 2).toByte() }; add(i + 1L, b, kind = MediaKind.VIDEO) } // five identical pairs, videos: hashing only
        val first = scanner().run(maxItems = 4)
        assertEquals(4, first.hashed)
        assertEquals(6, first.hashLeft)
        val second = scanner().run(maxItems = 100)
        assertEquals(6, second.hashed)
        assertEquals(0, second.hashLeft)
        assertEquals(5, groups(DuplicateKind.EXACT).size)
    }

    @Test fun `a member that leaves the device stops counting and its group disappears`() = runBlocking {
        val bytes = ByteArray(300) { it.toByte() }
        add(1, bytes); add(2, bytes.copyOf())
        scanner().run()
        val id = groups(DuplicateKind.EXACT).single().id
        env.db.media().deleteLocalRows(listOf(2))
        assertTrue("a group of one is not a group", groups(DuplicateKind.EXACT).isEmpty())
        assertEquals(setOf(1L), membersOf(id))
    }

    @Test fun `the user can say a group is not a duplicate and it stays dismissed after a rescan`() = runBlocking {
        val bytes = ByteArray(300) { it.toByte() }
        add(1, bytes); add(2, bytes.copyOf())
        scanner().run()
        val g = groups(DuplicateKind.EXACT).single()
        env.db.duplicates().ignore(DuplicateIgnoredEntity(g.signature, env.now))
        assertTrue(groups(DuplicateKind.EXACT).isEmpty())
        scanner().rebuildGroups()
        assertTrue("same set, still dismissed", groups(DuplicateKind.EXACT).isEmpty())
        add(3, bytes.copyOf())
        scanner().run()
        assertEquals("a new copy changes the set: it is offered again", 1, groups(DuplicateKind.EXACT).size)
    }

    // ------------------------------------------------------------------ similar pictures

    @Test fun `resized and brightened copies are probable matches, other pictures are not`() = runBlocking {
        add(1, "a-original".toByteArray(), scene = 11)
        add(2, "a-resized!!".toByteArray(), scene = 11)
        add(3, "b-other".toByteArray(), scene = 77)
        lumas["content://media/2"] = Synth.scene(11, resolution = 128, brightness = 15f)
        scanner().run()
        val similar = groups(DuplicateKind.SIMILAR)
        assertEquals(1, similar.size)
        assertEquals(setOf(1L, 2L), membersOf(similar.single().id))
        assertTrue(groups(DuplicateKind.EXACT).isEmpty())
    }

    @Test fun `same filename and same date are not evidence of similarity`() = runBlocking {
        add(1, "x".toByteArray(), "IMG_0001.jpg", mtime = 100, scene = 11)
        add(2, "yy".toByteArray(), "IMG_0001.jpg", mtime = 100, scene = 55)
        scanner().run()
        assertTrue(groups(DuplicateKind.SIMILAR).isEmpty())
        assertTrue(groups(DuplicateKind.EXACT).isEmpty())
    }

    @Test fun `identical copies are one exact group, not also a similar group`() = runBlocking {
        val bytes = ByteArray(400) { (it * 3).toByte() }
        add(1, bytes, scene = 21); add(2, bytes.copyOf(), scene = 21)
        scanner().run()
        assertEquals(1, groups(DuplicateKind.EXACT).size)
        assertTrue(groups(DuplicateKind.SIMILAR).isEmpty())
    }

    @Test fun `identical copies that also match a third picture show up in a similar group with all three`() = runBlocking {
        val bytes = ByteArray(400) { (it * 3).toByte() }
        add(1, bytes, scene = 21); add(2, bytes.copyOf(), scene = 21)
        add(3, "resized".toByteArray(), scene = 21)
        lumas["content://media/3"] = Synth.scene(21, resolution = 128)
        scanner().run()
        assertEquals(1, groups(DuplicateKind.EXACT).size)
        assertEquals(setOf(1L, 2L, 3L), membersOf(groups(DuplicateKind.SIMILAR).single().id))
    }

    @Test fun `flat pictures and undecodable files are recorded and never matched`() = runBlocking {
        add(1, "a".toByteArray()); lumas["content://media/1"] = Synth.flat(0f)
        add(2, "bb".toByteArray()); lumas["content://media/2"] = Synth.flat(0f)
        add(3, "ccc".toByteArray()) // no luma: cannot be decoded
        scanner().run()
        assertTrue(groups(DuplicateKind.SIMILAR).isEmpty())
        val status = env.db.openHelper.readableDatabase.query("SELECT mediaStoreId, status FROM media_analysis ORDER BY mediaStoreId").use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1)) }
        }
        assertEquals(mapOf(1L to "FLAT", 2L to "FLAT", 3L to "UNREADABLE"), status)
    }

    @Test fun `an unchanged picture is decoded once and a changed one again`() = runBlocking {
        add(1, "one".toByteArray(), scene = 5); add(2, "two!".toByteArray(), scene = 6)
        scanner().run()
        assertEquals(2, decoded.size)
        scanner().run()
        assertEquals("nothing new: no decoding", 2, decoded.size)
        add(2, "two!".toByteArray(), mtime = 300, scene = 6)
        scanner().run()
        assertEquals(3, decoded.size)
    }

    @Test fun `videos are not decoded for similarity`() = runBlocking {
        add(1, "vid".toByteArray(), kind = MediaKind.VIDEO)
        scanner().run()
        assertTrue(decoded.isEmpty())
    }

    @Test fun `recent pictures are analysed first when the budget is small`() = runBlocking {
        env.index(env.scanned(1, "old.jpg", "a".toByteArray()), env.scanned(2, "new.jpg", "bb".toByteArray()))
        env.db.media().update(env.db.media().get(1)!!.copy(dateTakenMs = 1_000))
        env.db.media().update(env.db.media().get(2)!!.copy(dateTakenMs = 9_000_000))
        lumas["content://media/1"] = Synth.scene(1); lumas["content://media/2"] = Synth.scene(2)
        scanner().run(maxItems = 1)
        assertEquals(listOf("content://media/2"), decoded)
    }

    @Test fun `stopping a scan keeps what was done and marks the run cancelled`() = runBlocking {
        add(1, "one".toByteArray(), scene = 1); add(2, "two!".toByteArray(), scene = 2)
        val entered = CompletableDeferred<Unit>()
        val blocking = ImageSignatureSource { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        val job = async(start = CoroutineStart.UNDISPATCHED) { DuplicateScanner(env.db, env.reader, blocking, JobRecorder(env.db) { env.now }, nowMs = { env.now }).run() }
        entered.await()
        job.cancelAndJoin()
        assertEquals(com.s3vault.app.data.local.JobState.CANCELLED, env.db.jobs().get(com.s3vault.app.data.local.JobNames.DUPLICATES)!!.state)
        assertNotNull(env.db.media().get(1))
        // A later run finishes the work.
        scanner().run()
        assertEquals(0, env.db.duplicates().needAnalysisCount())
    }

    @Test fun `the perceptual hash of the analysed picture is stored with its validity signature`() = runBlocking {
        add(1, "one".toByteArray(), scene = 9)
        scanner().run()
        val rows = env.db.duplicates().fingerprints()
        assertEquals(1, rows.size)
        assertEquals(PerceptualHash.compute(Synth.scene(9)), rows.single().hash)
        assertNull(env.db.duplicates().needAnalysis(10).firstOrNull())
    }
}
