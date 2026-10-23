package com.s3vault.app.sync

import com.s3vault.app.domain.AlbumManifest
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumImporterTest {
    private lateinit var env: TestEnv
    private lateinit var importer: AlbumImporter

    @Before fun setUp() {
        env = TestEnv()
        importer = AlbumImporter(env.db, env.provider) { env.now }
    }

    @After fun tearDown() { env.close() }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun photo(id: Long) = "photo-$id".toByteArray()

    private fun publish(json: String) {
        env.remote.objects[env.keys.albumManifestKey] = FakeRemote.Obj(json.toByteArray(), "x", "etag-m")
    }

    private fun manifest(vararg albums: Pair<String, List<String>>) =
        """{"version":1,"albums":[${albums.joinToString(",") { (n, items) -> """{"name":"$n","items":[${items.joinToString(",") { "\"$it\"" }}]}""" }}]}"""

    private suspend fun album(name: String) = env.db.albums().all().first { it.name == name }

    @Test fun `photos linked by a backup are added to albums created from the manifest`() = runBlocking {
        env.index(env.scanned(1, bytes = photo(1)), env.scanned(2, bytes = photo(2)), env.scanned(3, bytes = photo(3)))
        env.enqueue(1, 2)
        env.engine().run(1)
        publish(manifest("Amor" to listOf(sha(photo(1)), sha(photo(2))), "Otros" to listOf(sha(photo(3)))))

        val r = importer.importFromRemote() as AlbumImporter.Result.Imported

        assertEquals(1, r.albumsCreated)
        assertEquals(2, r.photosAdded)
        assertEquals(1, r.albumsSkipped) // photo 3 was never hashed or linked, so "Otros" has nothing to show
        assertEquals(1, r.photosUnmatched)
        assertEquals(setOf(1L, 2L), env.db.albums().memberIds(album("Amor").id).toSet())
    }

    @Test fun `matches both a computed hash and a linked remote version`() = runBlocking {
        env.index(env.scanned(1, bytes = photo(1)), env.scanned(2, bytes = photo(2)))
        env.db.media().saveHash(1, sha(photo(1)), 10, 100) // hashed only
        env.enqueue(2)
        env.engine().run(1) // linked through a real backup run
        assertEquals(null, env.db.media().get(1)!!.remoteVersionId)
        assertEquals(sha(photo(2)), env.db.media().get(2)!!.remoteVersionId)

        val r = importer.apply(AlbumManifest.parse(manifest("Mix" to listOf(sha(photo(1)), sha(photo(2))))))

        assertEquals(2, r.photosAdded)
    }

    @Test fun `running it again adds nothing and reuses albums regardless of letter case`() = runBlocking {
        env.index(env.scanned(1, bytes = photo(1)))
        env.enqueue(1)
        env.engine().run(1)
        publish(manifest("Amor" to listOf(sha(photo(1)))))
        importer.importFromRemote()

        publish(manifest("AMOR" to listOf(sha(photo(1)))))
        val again = importer.importFromRemote() as AlbumImporter.Result.Imported

        assertEquals(0, again.albumsCreated)
        assertEquals(0, again.photosAdded)
        assertEquals(1, env.db.albums().all().size)
    }

    @Test fun `an existing album receives new members without losing the old ones`() = runBlocking {
        env.index(env.scanned(1, bytes = photo(1)), env.scanned(2, bytes = photo(2)))
        env.enqueue(1, 2)
        env.engine().run(1)
        publish(manifest("Amor" to listOf(sha(photo(1)))))
        importer.importFromRemote()
        publish(manifest("Amor" to listOf(sha(photo(1)), sha(photo(2)))))

        val r = importer.importFromRemote() as AlbumImporter.Result.Imported

        assertEquals(1, r.albumsUpdated)
        assertEquals(1, r.photosAdded)
        assertEquals(setOf(1L, 2L), env.db.albums().memberIds(album("Amor").id).toSet())
    }

    @Test fun `identical photos on the device all join the album`() = runBlocking {
        env.index(env.scanned(1, bytes = photo(9)), env.scanned(2, bytes = photo(9)))
        env.db.media().saveHash(1, sha(photo(9)), 10, 100)
        env.db.media().saveHash(2, sha(photo(9)), 10, 100)
        val r = importer.apply(AlbumManifest.parse(manifest("Copias" to listOf(sha(photo(9))))))
        assertEquals(2, r.photosAdded)
    }

    @Test fun `photos deleted from the device are ignored`() = runBlocking {
        env.index(env.scanned(1, bytes = photo(1)))
        env.db.media().saveHash(1, sha(photo(1)), 10, 100)
        env.index() // nothing scanned any more: the file is gone
        val r = importer.apply(AlbumManifest.parse(manifest("Amor" to listOf(sha(photo(1))))))
        assertEquals(0, r.photosAdded)
        assertEquals(1, r.albumsSkipped)
        assertTrue(env.db.albums().all().isEmpty())
    }

    @Test fun `nothing happens without a manifest, a permission or a configuration`() = runBlocking {
        assertEquals(AlbumImporter.Result.NoManifest, importer.importFromRemote())

        publish(manifest("Amor" to emptyList()))
        env.remote.headFailures += RemoteException(RemoteErrorKind.ACCESS_DENIED)
        assertEquals(AlbumImporter.Result.NoManifest, importer.importFromRemote())

        assertEquals(AlbumImporter.Result.NotConfigured, AlbumImporter(env.db, { null }) { env.now }.importFromRemote())
        assertTrue(env.db.albums().all().isEmpty())
    }

    @Test fun `a malformed manifest is reported and changes nothing`() = runBlocking {
        publish("""{"version":1,"albums":[{"name":"x","items":["no"]}]}""")
        val r = importer.importFromRemote()
        assertTrue(r is AlbumImporter.Result.Invalid)
        assertTrue(env.db.albums().all().isEmpty())
    }

    // ---- Photos that exist only in S3 (a library migrated straight into the bucket).

    private var listings = 0
    private fun cloudImporter() = AlbumImporter(env.db, env.provider, refreshListing = { listings++; env.catalog.refresh(env.ctx) }) { env.now }

    /** Puts a photo in the fake bucket under the app's own key layout, as the migration script does. */
    private fun inBucket(id: Long, name: String = "IMG_$id.jpg"): String {
        val bytes = photo(id)
        val hash = sha(bytes)
        env.remote.objects[env.keys.photoKey("%032x".format(id), hash, name)] = FakeRemote.Obj(bytes, "x", "etag-$id")
        return hash
    }

    @Test fun `photos only in S3 join the album after listing the bucket once`() = runBlocking {
        val a = inBucket(1)
        val b = inBucket(2)
        publish(manifest("Viaje" to listOf(a, b)))

        val r = cloudImporter().importFromRemote() as AlbumImporter.Result.Imported

        assertEquals(1, r.albumsCreated)
        assertEquals(0, r.photosAdded)
        assertEquals(2, r.cloudAdded)
        assertEquals(0, r.photosUnmatched)
        assertTrue(r.listedBucket)
        assertEquals(1, listings)
        val id = album("Viaje").id
        assertEquals(setOf(a, b), env.db.albums().cloudMemberVersions(id).toSet())
        assertEquals(2, env.db.albums().observeAlbumCloud(id).first().size)
        assertEquals(2, env.db.albums().observeAlbums().first().single().itemCount)
    }

    @Test fun `an already cached listing is not requested again`() = runBlocking {
        val a = inBucket(1)
        env.catalog.refresh(env.ctx)
        publish(manifest("Viaje" to listOf(a)))

        val r = cloudImporter().importFromRemote() as AlbumImporter.Result.Imported

        assertEquals(1, r.cloudAdded)
        assertEquals(false, r.listedBucket)
        assertEquals(0, listings)
    }

    @Test fun `photos found nowhere are reported and no empty album is created`() = runBlocking {
        val here = inBucket(1)
        publish(manifest("Viaje" to listOf(here, sha(photo(99))), "Fantasma" to listOf(sha(photo(98)))))

        val r = cloudImporter().importFromRemote() as AlbumImporter.Result.Imported

        assertEquals(1, r.cloudAdded)
        assertEquals(2, r.photosUnmatched)
        assertEquals(1, r.albumsSkipped)
        assertEquals(3, r.manifestPhotos)
        assertEquals(listOf("Viaje"), env.db.albums().all().map { it.name })
    }

    @Test fun `a device copy wins over the S3 one and replaces an earlier S3 membership`() = runBlocking {
        val hash = inBucket(1)
        publish(manifest("Viaje" to listOf(hash)))
        cloudImporter().importFromRemote()
        val id = album("Viaje").id
        assertEquals(listOf(hash), env.db.albums().cloudMemberVersions(id))

        env.index(env.scanned(1, bytes = photo(1)))
        env.db.media().saveHash(1, hash, 10, 100)
        val again = cloudImporter().importFromRemote() as AlbumImporter.Result.Imported

        assertEquals(1, again.photosAdded)
        assertEquals(0, again.cloudAdded)
        assertEquals(listOf(1L), env.db.albums().memberIds(id))
        assertTrue(env.db.albums().cloudMemberVersions(id).isEmpty())
        assertEquals(1, env.db.albums().observeAlbums().first().single().itemCount)
    }

    @Test fun `an S3 member is not listed twice when the photo is also on the device`() = runBlocking {
        val hash = inBucket(1)
        publish(manifest("Viaje" to listOf(hash)))
        cloudImporter().importFromRemote()
        val id = album("Viaje").id

        env.index(env.scanned(1, bytes = photo(1)))
        env.db.media().saveHash(1, hash, 10, 100)
        env.db.albums().addItems(listOf(com.s3vault.app.data.local.AlbumItemEntity(id, 1, env.now)))

        assertTrue(env.db.albums().observeAlbumCloud(id).first().isEmpty())
        assertEquals(1, env.db.albums().observeAlbums().first().single().itemCount)
    }

    @Test fun `removing an S3 member leaves the object in the bucket`() = runBlocking {
        val hash = inBucket(1)
        publish(manifest("Viaje" to listOf(hash)))
        cloudImporter().importFromRemote()
        val id = album("Viaje").id

        AlbumController(env.db) { env.now }.removeCloud(id, listOf(hash))

        assertTrue(env.db.albums().cloudMemberVersions(id).isEmpty())
        assertEquals(1, env.remote.objects.keys.count { it.startsWith(env.keys.photosPrefix) })
    }

    @Test fun `an S3 member disappears from the album when the object leaves the listing, and a deletion forgets it`() = runBlocking {
        val a = inBucket(1)
        val b = inBucket(2)
        publish(manifest("Viaje" to listOf(a, b)))
        cloudImporter().importFromRemote()
        val id = album("Viaje").id
        val keyOfA = env.db.remote().getAll().first { it.versionId == a }.key

        env.cloudDeletion().delete(listOf(keyOfA))

        assertEquals(listOf(b), env.db.albums().cloudMemberVersions(id))
        assertEquals(1, env.db.albums().observeAlbums().first().single().itemCount)
    }

    @Test fun `the cover of an album with no photo on the device is an S3 photo`() = runBlocking {
        val hash = inBucket(1)
        publish(manifest("Viaje" to listOf(hash)))
        cloudImporter().importFromRemote()

        val row = env.db.albums().observeAlbums().first().single()

        assertEquals(null, row.coverUri)
        assertEquals(env.db.remote().getAll().single().key, row.coverRemoteKey)
    }
}
