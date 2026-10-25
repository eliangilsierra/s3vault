package com.s3vault.app.sync

import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.SyncState
import com.s3vault.app.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IndexerAndControllersTest {
    private lateinit var env: TestEnv

    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() { env.close() }

    @Test fun `new files are indexed as local only with unique asset ids and are not hashed`() = runBlocking {
        val r = env.index(env.scanned(1), env.scanned(2))
        assertEquals(2, r.added)
        val a = env.db.media().get(1)!!
        val b = env.db.media().get(2)!!
        assertEquals(SyncState.LOCAL_ONLY, a.syncState)
        assertTrue(a.assetId != b.assetId)
        assertNull(a.contentSha256)
    }

    @Test fun `rescanning unchanged media changes nothing`() = runBlocking {
        env.index(env.scanned(1))
        val before = env.db.media().get(1)
        val r = env.index(env.scanned(1))
        assertEquals(0, r.updated)
        assertEquals(before, env.db.media().get(1))
    }

    @Test fun `a scan that finds nothing new is skipped unless forced`() = runBlocking {
        var scans = 0
        var mediaVersion = 1L
        var access = MediaAccess.FULL
        val repo = LibraryRepository(
            object : com.s3vault.app.data.local.MediaScanSource {
                override suspend fun scan() = run { scans++; listOf(env.scanned(1)) }
            },
            com.s3vault.app.data.local.MediaIndexer(env.db), { access },
            changes = object : ChangeSignal { override val version get() = mediaVersion },
        )

        repo.refresh(force = false)
        assertEquals("the first one always runs", 1, scans)
        repo.refresh(force = false)
        assertEquals("nothing changed since: no scan", 1, scans)
        repo.refresh(force = true)
        assertEquals("an explicit refresh always scans", 2, scans)

        mediaVersion++ // MediaStore changed
        repo.refresh(force = false)
        assertEquals(3, scans)

        access = MediaAccess.PARTIAL // the permission changed
        repo.refresh(force = false)
        assertEquals(4, scans)
    }

    @Test fun `the thin projection path applies the same changes as before`() = runBlocking {
        env.index(env.scanned(1), env.scanned(2), env.scanned(3))
        env.enqueue(1)
        env.engine().run(1)
        val backedUpRow = env.db.media().get(1)!!
        assertEquals(SyncState.BACKED_UP, backedUpRow.syncState)

        // 1 was edited (size and mtime differ), 2 is untouched, 3 was renamed, 4 is new.
        val r = env.index(env.scanned(1, bytes = "edited-bytes".toByteArray(), mtime = 999), env.scanned(2), env.scanned(3).copy(displayName = "renamed.jpg"), env.scanned(4))

        assertEquals(1, r.added)
        assertEquals(2, r.updated)
        assertEquals(1, r.modifiedSinceBackup)
        assertEquals("an edited backed-up file is queued for a new version, not lost", SyncState.LOCAL_ONLY, env.db.media().get(1)!!.syncState)
        assertEquals(backedUpRow.remoteKey, env.db.media().get(1)!!.remoteKey)
        assertEquals("renamed.jpg", env.db.media().get(3)!!.displayName)
    }

    @Test fun `queueing a large selection is one write per 400 photos and every photo is queued once`() = runBlocking {
        val ids = (1L..450L).toList()
        env.index(*ids.map { env.scanned(it) }.toTypedArray())
        val scheduler = FakeScheduler()
        val c = BackupController(env.db, AppSettings(env.context), scheduler) { env.now }

        assertEquals(450, c.enqueue(ids))
        assertEquals(450, env.db.media().queuedCount())
        assertEquals("already queued: nothing moves twice", 0, c.enqueue(ids))
    }

    @Test fun `deleting a backed up photo locally keeps its remote copy and marks it remote only`() = runBlocking {
        env.index(env.scanned(1), env.scanned(2))
        env.enqueue(1)
        env.engine().run(1)
        val key = env.db.media().get(1)!!.remoteKey!!
        val remoteBefore = env.remote.objects.keys.toSet()

        // The user deletes both photos from the gallery
        val r = env.index(fullAccess = true)

        assertEquals(2, r.missing)
        val backedUp = env.db.media().get(1)!!
        assertEquals(SyncState.REMOTE_ONLY, backedUp.syncState)
        assertFalse(backedUp.present)
        assertEquals(key, backedUp.remoteKey)
        assertNull("never-backed-up row is simply dropped from the index", env.db.media().get(2))
        assertEquals("S3 is untouched", remoteBefore, env.remote.objects.keys.toSet())
        assertTrue(env.remote.objects.containsKey(key))
    }

    @Test fun `an empty scan under partial access never marks anything as missing`() = runBlocking {
        env.index(env.scanned(1))
        env.enqueue(1)
        env.engine().run(1)
        val r = env.index(fullAccess = false)
        assertEquals(0, r.missing)
        assertEquals(SyncState.BACKED_UP, env.db.media().get(1)!!.syncState)
    }

    @Test fun `a remote only file that reappears becomes backed up again`() = runBlocking {
        env.index(env.scanned(1))
        env.enqueue(1)
        env.engine().run(1)
        env.index()
        assertEquals(SyncState.REMOTE_ONLY, env.db.media().get(1)!!.syncState)
        env.index(env.scanned(1))
        assertEquals(SyncState.BACKED_UP, env.db.media().get(1)!!.syncState)
        assertTrue(env.db.media().get(1)!!.present)
    }

    @Test fun `video selection is excluded from automatic pending list unless enabled`() = runBlocking {
        env.index(env.scanned(1), env.scanned(2, kind = com.s3vault.app.domain.MediaKind.VIDEO))
        assertEquals(listOf(1L), env.db.media().pendingLocalOnly(false).map { it.mediaStoreId })
        assertEquals(setOf(1L, 2L), env.db.media().pendingLocalOnly(true).map { it.mediaStoreId }.toSet())
    }

    @Test fun `deleting an album or removing from it never touches photos`() = runBlocking {
        env.index(env.scanned(1), env.scanned(2))
        val albums = AlbumController(env.db) { env.now }
        val id = albums.create("Viaje")
        albums.add(id, listOf(1, 2))
        assertEquals(2, env.db.albums().observeAlbumMedia(id).first().size)

        albums.remove(id, listOf(1))
        assertEquals(listOf(2L), env.db.albums().observeAlbumMedia(id).first().map { it.mediaStoreId })
        assertTrue(env.db.media().get(1)!!.present)

        albums.delete(id)
        assertEquals(0, env.db.albums().observeAlbums().first().size)
        assertTrue(env.db.media().get(1)!!.present)
        assertTrue(env.db.media().get(2)!!.present)
    }

    @Test fun `controller queues pending items, wakes the scheduler and respects pause`() = runBlocking {
        val settings = AppSettings(env.context)
        val scheduler = FakeScheduler()
        val c = BackupController(env.db, settings, scheduler) { env.now }
        env.index(env.scanned(1), env.scanned(2))

        assertEquals(2, c.enqueue(listOf(1, 2)))
        assertEquals(1, scheduler.backupKicks)
        assertEquals(0, c.enqueue(listOf(1, 2))) // already queued: no duplicates

        c.pause()
        assertEquals(1, scheduler.backupCancels)
        assertTrue(settings.currentBackup().paused)
        assertEquals("queue survives pause", 2, env.db.media().queuedCount())

        env.index(env.scanned(3))
        val kicksBefore = scheduler.backupKicks
        c.enqueue(listOf(3))
        assertEquals("paused: no worker is started", kicksBefore, scheduler.backupKicks)

        c.resume()
        assertEquals(kicksBefore + 1, scheduler.backupKicks)
    }

    @Test fun `retry failed re-queues only failed items`() = runBlocking {
        val settings = AppSettings(env.context)
        val scheduler = FakeScheduler()
        val c = BackupController(env.db, settings, scheduler) { env.now }
        env.index(env.scanned(1), env.scanned(2))
        env.enqueue(1)
        env.remote.uploadFailures += RemoteException(RemoteErrorKind.ACCESS_DENIED)
        env.engine().run(1)
        assertEquals(SyncState.FAILED, env.db.media().get(1)!!.syncState)
        assertEquals(1, c.retryFailed())
        assertEquals(SyncState.QUEUED, env.db.media().get(1)!!.syncState)
        assertEquals(SyncState.LOCAL_ONLY, env.db.media().get(2)!!.syncState)
    }

    @Test fun `renewed credentials restart pending work`() = runBlocking {
        val settings = AppSettings(env.context)
        val scheduler = FakeScheduler()
        val c = BackupController(env.db, settings, scheduler) { env.now }
        env.index(env.scanned(1))
        env.enqueue(1)
        settings.setNeedsCredentials(true)
        c.onCredentialsRenewed()
        assertFalse(settings.needsCredentials.first())
        assertEquals(1, scheduler.backupKicks)
    }

    @Test fun `remote library refresh caches the listing and restore queues objects`() = runBlocking {
        env.index(env.scanned(1))
        env.enqueue(1)
        env.engine().run(1)
        val settings = AppSettings(env.context)
        val scheduler = FakeScheduler()
        val lib = RemoteLibraryController(env.db, env.provider, env.catalog, scheduler, settings)

        assertTrue(lib.refresh())
        val cached = env.db.remote().getAll()
        assertEquals(1, cached.size)
        assertEquals(SyncState.REMOTE_ONLY, cached.single().state)

        assertEquals(1, lib.restore(listOf(cached.single().key)))
        assertEquals(SyncState.RESTORING, env.db.remote().getAll().single().state)
        assertEquals(1, scheduler.restoreKicks)

        lib.cancelRestore(listOf(cached.single().key))
        assertEquals(SyncState.REMOTE_ONLY, env.db.remote().getAll().single().state)
    }

    @Test fun `refreshing the remote cache never deletes remote objects`() = runBlocking {
        env.index(env.scanned(1))
        env.enqueue(1)
        env.engine().run(1)
        val before = env.remote.objects.size
        env.catalog.refresh(env.ctx)
        env.remote.objects.clear() // object vanishes remotely: only the cache is pruned
        env.catalog.refresh(env.ctx)
        assertEquals(0, env.db.remote().getAll().size)
        assertEquals(1, before)
    }
}
