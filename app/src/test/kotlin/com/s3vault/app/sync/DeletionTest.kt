package com.s3vault.app.sync

import com.s3vault.app.data.local.RemoteObjectEntity
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.SyncState
import com.s3vault.app.settings.AppSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeThumbs(var bytes: ByteArray? = "jpeg-preview".toByteArray(), var failure: Exception? = null) : com.s3vault.app.domain.ThumbnailMaker {
    val calls = mutableListOf<String>()
    override suspend fun create(uri: String, kind: com.s3vault.app.domain.MediaKind): ByteArray? {
        calls += uri
        failure?.let { throw it }
        return bytes
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudDeletionTest {
    private lateinit var env: TestEnv

    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() { env.close() }

    /** Backs photo [id] up (with a preview) and refreshes the cached S3 listing, like the real flow does. */
    private suspend fun backedUp(id: Long, thumbs: FakeThumbs = FakeThumbs()): String {
        env.index(env.scanned(id))
        env.enqueue(id)
        env.engine(thumbnails = thumbs).run(1)
        env.catalog.refresh(env.ctx)
        return env.db.media().get(id)!!.remoteKey!!
    }

    /** An S3 object that no local row points to (another device, or this one after reinstalling). */
    private suspend fun foreignObject(n: Int, version: String = "%064x".format(n)): String {
        val key = env.keys.photoKey("%032x".format(n + 1000), version, "other_$n.jpg")
        env.remote.objects[key] = FakeRemote.Obj("bytes-$n".toByteArray(), "", "etag-f$n")
        env.db.remote().upsertAll(listOf(RemoteObjectEntity(key, "%032x".format(n + 1000), version, "other_$n.jpg", 10, 1_000)))
        return key
    }

    @Test fun `deleting from S3 removes the object and its preview but never the local photo`() = runBlocking {
        val key = backedUp(1)
        val version = env.db.media().get(1)!!.remoteVersionId!!
        val thumbKey = env.keys.thumbnailKey(version)
        assertTrue(env.remote.objects.containsKey(thumbKey))

        val r = env.cloudDeletion().delete(listOf(key))

        assertEquals(listOf(key), r.deleted)
        assertTrue(r.failed.isEmpty())
        assertFalse(env.remote.objects.containsKey(key))
        assertFalse(env.remote.objects.containsKey(thumbKey))
        assertNull(env.db.remote().get(key))
        val row = env.db.media().get(1)!!
        assertTrue("the local file is untouched", row.present)
        assertEquals(SyncState.LOCAL_ONLY, row.syncState)
        assertNull(row.remoteKey)
        assertTrue(row.backupExcluded)
        assertTrue(env.reader.files.containsKey(row.uri))
    }

    @Test fun `a photo whose S3 copy was deleted on purpose is not uploaded again by automatic backup`() = runBlocking {
        val key = backedUp(1)
        env.cloudDeletion().delete(listOf(key))

        assertEquals("excluded from the pending list", emptyList<Long>(), env.db.media().pendingLocalOnly(true).map { it.mediaStoreId })
        val c = BackupController(env.db, AppSettings(env.context), FakeScheduler()) { env.now }
        assertEquals("not queued by 'back up pending'", 0, c.enqueuePending(true))

        // The user can still back it up again on purpose.
        assertEquals(1, c.enqueue(listOf(1)))
        assertFalse(env.db.media().get(1)!!.backupExcluded)
    }

    @Test fun `deleting a photo that only exists in S3 drops its index row and album membership`() = runBlocking {
        val key = backedUp(1)
        val albums = AlbumController(env.db) { env.now }
        val album = albums.create("Viaje")
        albums.add(album, listOf(1))
        env.index() // the file vanished from the device: now only S3 has it
        assertEquals(SyncState.REMOTE_ONLY, env.db.media().get(1)!!.syncState)

        env.cloudDeletion().delete(listOf(key))

        assertNull(env.db.media().get(1))
        assertEquals(emptyList<Long>(), env.db.albums().observeAlbumMedia(album).first().map { it.mediaStoreId })
        assertEquals("the album itself stays", 1, env.db.albums().observeAlbums().first().size)
    }

    @Test fun `a preview shared by another object with the same content is kept`() = runBlocking {
        val version = "c".repeat(64)
        val k1 = foreignObject(1, version)
        val k2 = foreignObject(2, version)
        val thumbKey = env.keys.thumbnailKey(version)
        env.remote.objects[thumbKey] = FakeRemote.Obj("p".toByteArray(), "", "e")
        val evicted = mutableListOf<String>()
        val controller = env.cloudDeletion { evicted += it }

        controller.delete(listOf(k1))
        assertTrue("still used by the second object", env.remote.objects.containsKey(thumbKey))
        assertTrue(evicted.isEmpty())

        controller.delete(listOf(k2))
        assertFalse(env.remote.objects.containsKey(thumbKey))
        assertEquals(listOf(version), evicted)
    }

    @Test fun `failures are reported per key and a retry deletes only what is left`() = runBlocking {
        val k1 = foreignObject(1)
        val k2 = foreignObject(2)
        env.remote.deleteKeyFailures[k2] = RemoteErrorKind.THROTTLED

        val first = env.cloudDeletion().delete(listOf(k1, k2))
        assertEquals(listOf(k1), first.deleted)
        assertEquals(mapOf(k2 to RemoteErrorKind.THROTTLED), first.failed)
        assertNull(env.db.remote().get(k1))
        assertNotNull("the failed one stays listed", env.db.remote().get(k2))
        assertTrue(env.remote.objects.containsKey(k2))

        env.remote.deleteKeyFailures.clear()
        val retry = env.cloudDeletion().delete(first.failedKeys)
        assertEquals(listOf(k2), retry.deleted)
        assertTrue(retry.failed.isEmpty())
        assertFalse(env.remote.objects.containsKey(k2))
    }

    @Test fun `an IAM denial is reported once and later batches are not attempted`() = runBlocking {
        val keys = (1..101).map { foreignObject(it) }
        env.remote.deleteRequestFailures += RemoteException(RemoteErrorKind.ACCESS_DENIED)

        val r = env.cloudDeletion().delete(keys)

        assertTrue(r.deleted.isEmpty())
        assertEquals(101, r.failed.size)
        assertTrue(r.failed.values.all { it == RemoteErrorKind.ACCESS_DENIED })
        assertEquals("only the first batch was sent", 1, env.remote.deleteCalls.size)
        assertEquals(keys.toSet(), env.remote.objects.keys.toSet())
        assertEquals(101, env.db.remote().getAll().size)
    }

    @Test fun `a per-key access denied also stops the remaining batches`() = runBlocking {
        val keys = (1..101).map { foreignObject(it) }
        keys.take(100).forEach { env.remote.deleteKeyFailures[it] = RemoteErrorKind.ACCESS_DENIED }

        val r = env.cloudDeletion().delete(keys)

        assertEquals(1, env.remote.deleteCalls.size)
        assertEquals(101, r.failed.size)
        assertEquals(RemoteErrorKind.ACCESS_DENIED, r.failed[keys.last()])
    }

    @Test fun `objects being restored are not deleted`() = runBlocking {
        val k = foreignObject(1)
        env.db.remote().transition(k, SyncState.REMOTE_ONLY, SyncState.RESTORING, null)

        val r = env.cloudDeletion().delete(listOf(k))

        assertEquals(1, r.skippedRestoring)
        assertTrue(r.deleted.isEmpty())
        assertTrue(env.remote.deleteCalls.isEmpty())
        assertTrue(env.remote.objects.containsKey(k))
    }

    @Test fun `only photo keys can be deleted`() = runBlocking {
        val photo = foreignObject(1)
        val thumb = env.keys.thumbnailKey("d".repeat(64))
        env.remote.objects[thumb] = FakeRemote.Obj(ByteArray(1), "", "e")

        val r = env.cloudDeletion().delete(listOf(thumb, "${env.keys.prefix}.s3vault/albums.json", "elsewhere/photos/x", photo))

        assertEquals(listOf(photo), r.deleted)
        assertEquals(listOf(listOf(photo)), env.remote.deleteCalls.take(1))
        assertTrue("albums manifest and other prefixes are untouched", env.remote.objects.containsKey(thumb))
    }

    @Test fun `a failed whole request does not touch the index`() = runBlocking {
        val key = backedUp(1)
        env.remote.deleteRequestFailures += RemoteException(RemoteErrorKind.NETWORK)

        val r = env.cloudDeletion().delete(listOf(key))

        assertEquals(mapOf(key to RemoteErrorKind.NETWORK), r.failed)
        assertEquals(SyncState.BACKED_UP, env.db.media().get(1)!!.syncState)
        assertEquals(key, env.db.media().get(1)!!.remoteKey)
        assertNotNull(env.db.remote().get(key))
    }

    @Test fun `without an S3 configuration nothing is attempted`() {
        env.provider = RemoteContextProvider { null }
        val e = assertThrows(RemoteException::class.java) { runBlocking { env.cloudDeletion().delete(listOf("x")) } }
        assertEquals(RemoteErrorKind.NOT_CONFIGURED, e.kind)
    }

    @Test fun `cancelling between batches keeps the finished batches consistent`() = runBlocking {
        val keys = (1..150).map { foreignObject(it) }
        val controller = env.cloudDeletion()
        lateinit var job: kotlinx.coroutines.Job
        job = launch(start = CoroutineStart.LAZY) {
            controller.delete(keys) { done, _ -> if (done >= 100) job.cancel() }
        }
        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(100, env.remote.deletedKeys.count { env.keys.isPhotoKey(it) })
        assertEquals("cache matches S3 after the cancelled run", 50, env.db.remote().getAll().size)
        assertEquals(50, env.remote.objects.size)
    }

    @Test fun `progress is reported per batch`() = runBlocking {
        val keys = (1..120).map { foreignObject(it) }
        val seen = mutableListOf<Pair<Int, Int>>()
        env.cloudDeletion().delete(keys) { d, t -> seen += d to t }
        assertEquals(listOf(0 to 120, 100 to 120, 120 to 120), seen)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalDeletionTest {
    private lateinit var env: TestEnv
    private val existing = mutableSetOf<String>()

    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() { env.close() }

    private fun controller() = LocalDeletionController(env.db) { uri -> uri in existing }

    private suspend fun entry(id: Long): DeleteEntry {
        val row = env.db.media().get(id)!!
        return DeleteEntry(row.displayName, id, row.uri, row.remoteKey, hasLocalCopy = row.present)
    }

    private suspend fun setUpTwoPhotos() {
        env.index(env.scanned(1), env.scanned(2), env.scanned(3))
        env.enqueue(1)
        env.engine().run(1) // only photo 1 has an S3 copy
        listOf(1L, 2L, 3L).forEach { existing += env.db.media().get(it)!!.uri }
    }

    @Test fun `files Android removed are settled and the S3 copy is kept`() = runBlocking {
        setUpTwoPhotos()
        val albums = AlbumController(env.db) { env.now }
        val album = albums.create("A")
        albums.add(album, listOf(1, 2))
        val remoteBefore = env.remote.objects.keys.toSet()
        val entries = listOf(entry(1), entry(2))
        existing -= entries.map { it.uri!! }.toSet()

        val r = controller().settle(entries)

        assertEquals(LocalDeleteResult(deleted = 2, keptInCloud = 1, lost = 1, remaining = 0), r)
        val backedUp = env.db.media().get(1)!!
        assertEquals(SyncState.REMOTE_ONLY, backedUp.syncState)
        assertFalse(backedUp.present)
        assertNotNull("the pointer to the S3 copy is kept", backedUp.remoteKey)
        assertNull("a file with no S3 copy has nothing left to point to", env.db.media().get(2))
        assertEquals(emptyList<Long>(), env.db.albums().observeAlbumMedia(album).first().map { it.mediaStoreId })
        assertEquals("S3 was not touched", remoteBefore, env.remote.objects.keys.toSet())
        assertTrue(env.remote.deleteCalls.isEmpty())
        assertEquals("the other photo is untouched", SyncState.LOCAL_ONLY, env.db.media().get(3)!!.syncState)
    }

    @Test fun `when the user cancels Android's dialog nothing changes`() = runBlocking {
        setUpTwoPhotos()
        val before = env.db.media().getEverything()

        val r = controller().settle(listOf(entry(1), entry(2)))

        assertEquals(LocalDeleteResult(deleted = 0, keptInCloud = 0, lost = 0, remaining = 2), r)
        assertEquals(before, env.db.media().getEverything())
    }

    @Test fun `a partial deletion settles only the files that are really gone`() = runBlocking {
        setUpTwoPhotos()
        val entries = listOf(entry(1), entry(2))
        existing -= entries[1].uri!!

        val r = controller().settle(entries)

        assertEquals(LocalDeleteResult(deleted = 1, keptInCloud = 0, lost = 1, remaining = 1), r)
        assertEquals(SyncState.BACKED_UP, env.db.media().get(1)!!.syncState)
        assertNull(env.db.media().get(2))
    }

    @Test fun `entries without a local file are ignored`() = runBlocking {
        setUpTwoPhotos()
        val r = controller().settle(listOf(DeleteEntry("x.jpg", null, null, "some/key", hasLocalCopy = false)))
        assertEquals(LocalDeleteResult(0, 0, 0, 0), r)
    }

    @Test fun `delete impact tells where the copies are`() {
        val entries = listOf(
            DeleteEntry("both", 1, "u1", "k1", true),
            DeleteEntry("device only", 2, "u2", null, true),
            DeleteEntry("cloud only", null, null, "k3", false),
        )
        assertEquals(DeleteImpact(total = 3, onDevice = 2, inCloud = 2, deviceOnly = 1, cloudOnly = 1), DeleteImpact.of(entries))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewUploadTest {
    private lateinit var env: TestEnv

    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() { env.close() }

    private suspend fun backUp(id: Long, thumbs: com.s3vault.app.domain.ThumbnailMaker?, previewsEnabled: Boolean = true) {
        env.index(env.scanned(id))
        env.enqueue(id)
        env.engine(thumbnails = thumbs, previewsEnabled = { previewsEnabled }).run(1)
    }

    @Test fun `a successful backup also stores the preview next to the photo`() = runBlocking {
        val thumbs = FakeThumbs()
        backUp(1, thumbs)

        val row = env.db.media().get(1)!!
        assertEquals(SyncState.BACKED_UP, row.syncState)
        val thumbKey = env.keys.thumbnailKey(row.remoteVersionId!!)
        assertEquals("jpeg-preview", String(env.remote.objects.getValue(thumbKey).bytes))
        assertTrue(row.thumbnailUploaded)
        assertTrue("never listed as a photo", env.remote.listPhotos().none { it.key == thumbKey })
    }

    @Test fun `a failing preview never fails the backup and is retried by the backfill`() = runBlocking {
        val thumbs = FakeThumbs(failure = java.io.IOException("decoder"))
        backUp(1, thumbs)

        val row = env.db.media().get(1)!!
        assertEquals(SyncState.BACKED_UP, row.syncState)
        assertFalse(row.thumbnailUploaded)
        assertEquals(1, env.db.media().thumbnailBacklog(10).size)

        thumbs.failure = null
        assertEquals(1, env.engine(thumbnails = thumbs).backfillPreviews())
        assertTrue(env.db.media().get(1)!!.thumbnailUploaded)
        assertTrue(env.remote.objects.containsKey(env.keys.thumbnailKey(row.remoteVersionId!!)))
        assertEquals("nothing left to do", 0, env.engine(thumbnails = thumbs).backfillPreviews())
    }

    @Test fun `previews can be turned off`() = runBlocking {
        val thumbs = FakeThumbs()
        backUp(1, thumbs, previewsEnabled = false)

        assertEquals(0, thumbs.calls.size)
        assertEquals(0, env.remote.putThumbnailCalls)
        assertEquals(0, env.engine(thumbnails = thumbs, previewsEnabled = { false }).backfillPreviews())
    }

    @Test fun `a file that cannot be decoded is marked done instead of retried forever`() = runBlocking {
        val thumbs = FakeThumbs(bytes = null)
        backUp(1, thumbs)

        assertEquals(0, env.remote.putThumbnailCalls)
        assertTrue(env.db.media().get(1)!!.thumbnailUploaded)
        assertEquals(0, env.engine(thumbnails = thumbs).backfillPreviews())
    }

    @Test fun `the backfill stops at the first error that would repeat for every file`() = runBlocking {
        val thumbs = FakeThumbs(failure = java.io.IOException("offline-ish"))
        env.index(env.scanned(1), env.scanned(2), env.scanned(3))
        env.enqueue(1, 2, 3)
        env.engine(thumbnails = thumbs).run(1)
        assertEquals(3, env.db.media().thumbnailBacklog(10).size)

        thumbs.failure = null
        env.remote.putThumbnailFailures += RemoteException(RemoteErrorKind.ACCESS_DENIED)
        val created = env.engine(thumbnails = thumbs).backfillPreviews()

        assertEquals(0, created)
        assertEquals("one attempt, then it stopped", 1, env.remote.putThumbnailCalls)
        assertEquals(3, env.db.media().thumbnailBacklog(10).size)
    }

    @Test fun `without credentials the backfill does nothing`() = runBlocking {
        val thumbs = FakeThumbs(failure = java.io.IOException("x"))
        backUp(1, thumbs)
        thumbs.failure = null
        env.creds.failure = RemoteException(RemoteErrorKind.CREDENTIALS_EXPIRED)
        assertEquals(0, env.engine(thumbnails = thumbs).backfillPreviews())
        assertEquals(0, env.remote.putThumbnailCalls)
    }

    @Test fun `a new version of the file needs its own preview`() = runBlocking {
        val thumbs = FakeThumbs()
        backUp(1, thumbs)
        val v1 = env.db.media().get(1)!!.remoteVersionId!!

        // The photo is edited: same MediaStore row, new bytes and mtime.
        env.index(env.scanned(1, bytes = "edited".toByteArray(), mtime = 200))
        assertEquals(SyncState.LOCAL_ONLY, env.db.media().get(1)!!.syncState)
        env.enqueue(1)
        env.engine(thumbnails = thumbs).run(1)

        val v2 = env.db.media().get(1)!!.remoteVersionId!!
        assertTrue(v1 != v2)
        assertTrue(env.remote.objects.containsKey(env.keys.thumbnailKey(v1)))
        assertTrue(env.remote.objects.containsKey(env.keys.thumbnailKey(v2)))
    }
}
