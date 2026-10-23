package com.s3vault.app.sync

import com.s3vault.app.domain.AlbumManifest
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
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
}
