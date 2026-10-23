package com.s3vault.app.sync

import com.s3vault.app.domain.EmbeddedThumbnailReader
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.RemoteObject
import com.s3vault.app.domain.SyncState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemotePreviewRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val keys = RemoteKeys("p/")
    private val remote = FakeRemote(keys)
    private val ctx = RemoteContext(remote, keys)
    private val version = "e".repeat(64)
    private var now = 1_000_000L
    private var embeddedResult: ByteArray? = null
    private var embeddedHeads = mutableListOf<Int>()
    private lateinit var repo: RemotePreviewRepository

    private fun obj(name: String = "IMG_1.jpg", v: String = version) = RemoteObject(
        key = keys.photoKey("a".repeat(32), v, name), assetId = "a".repeat(32), versionId = v, displayName = name,
        sizeBytes = 4_000_000, lastModifiedMs = 1, state = SyncState.REMOTE_ONLY, lastError = null, availableLocally = false,
    )

    @Before fun setUp() {
        repo = RemotePreviewRepository(
            dir = tmp.newFolder("cache"), remote = { ctx },
            embedded = EmbeddedThumbnailReader { head -> embeddedHeads += head.size; embeddedResult },
            nowMs = { now }, maxCacheBytes = 1_000,
        )
    }

    private fun original(o: RemoteObject, size: Int = 300_000) {
        remote.objects[o.key] = FakeRemote.Obj(ByteArray(size) { 7 }, "", "etag")
    }

    private fun preview(v: String = version, bytes: ByteArray = "small-jpeg".toByteArray()) {
        remote.objects[keys.thumbnailKey(v)] = FakeRemote.Obj(bytes, "", "etag-t")
    }

    @Test fun `the preview object is used and the original is never read`() = runBlocking {
        val o = obj(); original(o); preview()

        val r = repo.load(o) as PreviewResult.Ready

        assertEquals(PreviewQuality.PREVIEW, r.quality)
        assertEquals("small-jpeg", r.file.readText())
        assertEquals(listOf(keys.thumbnailKey(version) to null), remote.readRequests)
    }

    @Test fun `a second look is served from the cache with no request`() = runBlocking {
        val o = obj(); preview()
        repo.load(o)
        val requests = remote.readRequests.size

        val again = repo.load(o) as PreviewResult.Ready

        assertEquals(requests, remote.readRequests.size)
        assertEquals("small-jpeg", again.file.readText())
    }

    @Test fun `without a preview object a JPEG falls back to a ranged read of its head`() = runBlocking {
        val o = obj(); original(o)
        embeddedResult = "exif-thumb".toByteArray()

        val r = repo.load(o) as PreviewResult.Ready

        assertEquals(PreviewQuality.EMBEDDED, r.quality)
        assertEquals("exif-thumb", r.file.readText())
        assertEquals(keys.thumbnailKey(version) to null, remote.readRequests[0])
        assertEquals("only the first 128 KiB of the original are requested", o.key to RemotePreviewRepository.HEAD_BYTES, remote.readRequests[1])
        assertEquals("the reader never saw more than the head", listOf(RemotePreviewRepository.HEAD_BYTES.toInt()), embeddedHeads)
    }

    @Test fun `a 403 for a missing preview (prefix-scoped ListBucket) is treated as no preview, not as an error`() = runBlocking {
        val o = obj(); original(o)
        remote.missingKeysAnswerForbidden = true
        embeddedResult = "exif-thumb".toByteArray()

        assertEquals(PreviewQuality.EMBEDDED, (repo.load(o) as PreviewResult.Ready).quality)
    }

    @Test fun `an object deleted elsewhere is reported as gone, not as having no preview`() {
        val jpeg = obj(); val video = obj("clip.mp4", v = "f".repeat(64)) // neither exists in the bucket any more
        listOf(jpeg, video).forEach { o ->
            val e = assertThrows(RemoteException::class.java) { runBlocking { repo.load(o) } }
            assertEquals(o.displayName, RemoteErrorKind.OBJECT_NOT_FOUND, e.kind)
        }
    }

    @Test fun `non JPEG files do not trigger the ranged read`() = runBlocking {
        val o = obj("clip.mp4"); original(o)

        assertEquals(PreviewResult.Unavailable, repo.load(o))
        assertEquals("only the preview lookup", 1, remote.readRequests.size)
    }

    @Test fun `no preview and no embedded thumbnail is remembered so scrolling does not repeat requests`() = runBlocking {
        val o = obj(); original(o)

        assertEquals(PreviewResult.Unavailable, repo.load(o))
        val requests = remote.readRequests.size
        assertEquals(PreviewResult.Unavailable, repo.load(o))
        assertEquals(requests, remote.readRequests.size)

        // After the miss expires (e.g. the backfill created the preview meanwhile) it is looked up again.
        now += RemotePreviewRepository.MISS_TTL_MS + 1
        preview()
        assertEquals(PreviewQuality.PREVIEW, (repo.load(o) as PreviewResult.Ready).quality)
    }

    @Test fun `transient failures are thrown and never cached`() = runBlocking {
        val o = obj(); preview()
        remote.readFailures += RemoteException(RemoteErrorKind.NETWORK)

        val e = assertThrows(RemoteException::class.java) { runBlocking { repo.load(o) } }
        assertEquals(RemoteErrorKind.NETWORK, e.kind)

        assertEquals("the next attempt works", PreviewQuality.PREVIEW, (repo.load(o) as PreviewResult.Ready).quality)
    }

    @Test fun `expired credentials surface to the caller`() {
        val o = obj()
        remote.readFailures += RemoteException(RemoteErrorKind.CREDENTIALS_EXPIRED)
        val e = assertThrows(RemoteException::class.java) { runBlocking { repo.load(o) } }
        assertTrue(e.needsCredentials)
    }

    @Test fun `an unreadable embedded thumbnail counts as unavailable`() = runBlocking {
        val o = obj(); original(o)
        val broken = RemotePreviewRepository(
            tmp.newFolder("c2"), { ctx }, EmbeddedThumbnailReader { throw IllegalStateException("corrupt exif") }, { now },
        )
        assertEquals(PreviewResult.Unavailable, broken.load(o))
    }

    @Test fun `evicting removes every trace of a deleted photo`() = runBlocking {
        val o = obj(); preview()
        val file = (repo.load(o) as PreviewResult.Ready).file
        assertTrue(file.exists())

        repo.evict(listOf(version))

        assertFalse(file.exists())
    }

    @Test fun `the cache stays within its budget, dropping the least recently used files first`() = runBlocking {
        val versions = (0 until 40).map { "%064x".format(it) }
        versions.forEach { v ->
            preview(v, ByteArray(100) { 1 })
            now += 1_000
            repo.load(obj(v = v))
        }
        val dir = tmp.root.resolve("cache")
        val total = dir.listFiles()!!.sumOf { it.length() }
        assertTrue("cache is $total bytes", total <= 1_000)
        assertNotNull("the newest is kept", dir.listFiles()!!.firstOrNull { it.name == "${versions.last()}.jpg" })
    }

    @Test fun `no S3 configuration is an error, not a miss`() {
        val unconfigured = RemotePreviewRepository(tmp.newFolder("c3"), { null }, { null }, { now })
        val e = assertThrows(RemoteException::class.java) { runBlocking { unconfigured.load(obj()) } }
        assertEquals(RemoteErrorKind.NOT_CONFIGURED, e.kind)
    }
}
