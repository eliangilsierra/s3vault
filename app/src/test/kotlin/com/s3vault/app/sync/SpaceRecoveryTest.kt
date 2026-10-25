package com.s3vault.app.sync

import com.s3vault.app.data.local.IntegrityReason
import com.s3vault.app.data.local.IntegrityResultEntity
import com.s3vault.app.data.local.IntegrityVerdict
import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.data.local.SpaceRecoverySql
import com.s3vault.app.data.local.VerifyLevel
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.space.DropReason
import com.s3vault.app.domain.space.SafetyRules
import com.s3vault.app.domain.space.SpaceBucket
import com.s3vault.app.domain.space.SpaceGrouping
import com.s3vault.app.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpaceRecoveryTest {
    private lateinit var env: TestEnv
    private val day = 24L * 3600 * 1000
    private val now = 1_000 * day
    private var refreshed = 0

    @Before fun setUp() {
        env = TestEnv()
        env.now = now
        env.provider = RemoteContextProvider { RemoteContext(env.remote, env.keys, S3Config("eu-west-1", "bucket", "mi/prefijo/", AuthMethod.STATIC)) }
        env.catalog = RemoteCatalog(env.db, nowMs = { env.now })
    }
    @After fun tearDown() { env.close() }

    private fun auditor() = IntegrityAuditor(env.db, env.provider, env.creds, env.catalog, JobRecorder(env.db) { env.now }, lastListing = { null }, nowMs = { env.now })
    private fun controller() = SpaceRecoveryController(env.db, auditor(), refreshIndex = { refreshed++ }, nowMs = { env.now })

    private suspend fun item(id: Long) = env.db.media().get(id)!!

    /** Backs the photos up for real, then makes each one old enough and backed up long enough, unless a test says otherwise. */
    private suspend fun backUp(vararg ids: Long) {
        env.index(*ids.map { env.scanned(it) }.toTypedArray())
        env.enqueue(*ids)
        env.engine().run(2)
        ids.forEach { id -> change(id) { it.copy(dateTakenMs = now - 200 * day, lastBackupAtMs = now - 100 * day) } }
    }

    private suspend fun change(id: Long, f: (MediaItemEntity) -> MediaItemEntity) = env.db.media().update(f(item(id)))

    private suspend fun verdict(id: Long, v: IntegrityVerdict, checkedAt: Long = now - day, level: VerifyLevel = VerifyLevel.LISTING) {
        env.db.integrity().upsertResults(listOf(IntegrityResultEntity(item(id).remoteKey!!, v, level, IntegrityReason.NONE, checkedAt, expectedSize = item(id).sizeBytes)))
    }

    private suspend fun buckets(rules: SafetyRules = SafetyRules()) =
        env.db.media().observeSpaceBuckets(SpaceRecoverySql.buckets(rules, now)).first().associate { SpaceBucket.valueOf(it.cls) to it }

    private suspend fun idsOf(b: SpaceBucket, rules: SafetyRules = SafetyRules()) = env.db.media().idList(SpaceRecoverySql.ids(rules, now, b)).toSet()

    // ------------------------------------------------------------------ what is a candidate

    @Test fun `only verified backups are recommended and the rest explain why not`() = runBlocking {
        backUp(1, 2, 3, 4, 5, 6, 7, 8)
        verdict(1, IntegrityVerdict.VERIFIED)                              // 1: fine
        change(2) { it.copy(isFavorite = true) }; verdict(2, IntegrityVerdict.VERIFIED)           // 2: favourite
        change(3) { it.copy(dateTakenMs = now - 3 * day) }; verdict(3, IntegrityVerdict.VERIFIED) // 3: taken 3 days ago
        change(4) { it.copy(lastBackupAtMs = now - 2 * day) }; verdict(4, IntegrityVerdict.VERIFIED) // 4: backed up 2 days ago
        change(5) { it.copy(folderName = "Privado") }; verdict(5, IntegrityVerdict.VERIFIED)      // 5: excluded folder
        verdict(6, IntegrityVerdict.MISSING)                                                       // 6: gone from S3
        verdict(7, IntegrityVerdict.CHANGED)                                                       // 7: replaced in S3
        // 8: backed up long ago, no audit at all
        val rules = SafetyRules(excludedFolders = setOf("Privado"))

        val b = buckets(rules)

        assertEquals(setOf(1L), idsOf(SpaceBucket.VERIFIED, rules))
        assertEquals(setOf(2L), idsOf(SpaceBucket.EXCL_FAVORITE, rules))
        assertEquals(setOf(3L), idsOf(SpaceBucket.EXCL_RECENT, rules))
        assertEquals(setOf(4L), idsOf(SpaceBucket.EXCL_NEW_BACKUP, rules))
        assertEquals(setOf(5L), idsOf(SpaceBucket.EXCL_FOLDER, rules))
        assertEquals(setOf(6L), idsOf(SpaceBucket.MISSING, rules))
        assertEquals(setOf(7L), idsOf(SpaceBucket.OUTDATED, rules))
        assertEquals("backed up 100 days ago and never audited: the proof is too old", setOf(8L), idsOf(SpaceBucket.UNVERIFIED, rules))
        assertEquals(1, b.getValue(SpaceBucket.VERIFIED).n)
        assertEquals("estimated recoverable space is the size of the verified files", item(1).sizeBytes, b.getValue(SpaceBucket.VERIFIED).bytes)
    }

    @Test fun `a problem in S3 outranks any preference of the user`() = runBlocking {
        backUp(1)
        change(1) { it.copy(isFavorite = true, dateTakenMs = now - day) }
        verdict(1, IntegrityVerdict.MISSING)
        assertEquals("a favourite whose object is gone is reported as missing, not hidden as 'favourite'", setOf(1L), idsOf(SpaceBucket.MISSING))
    }

    @Test fun `a file edited after its backup is outdated, not recoverable`() = runBlocking {
        backUp(1)
        verdict(1, IntegrityVerdict.VERIFIED)
        change(1) { it.copy(sizeBytes = it.sizeBytes + 10) } // the scan has not run yet, but the sizes already disagree
        assertEquals(setOf(1L), idsOf(SpaceBucket.OUTDATED))
        assertTrue(idsOf(SpaceBucket.VERIFIED).isEmpty())
    }

    @Test fun `a recent verified backup counts as proof without a separate audit`() = runBlocking {
        backUp(1)
        change(1) { it.copy(lastBackupAtMs = now - 8 * day) } // backed up (and checksum-verified by the upload) 8 days ago, no audit
        assertEquals("older than the 7 day window: not enough", setOf(1L), idsOf(SpaceBucket.UNVERIFIED))
        val rules = SafetyRules(verifiedWithinHours = 24 * 30)
        assertEquals("with a 30 day window the upload's own verification counts", setOf(1L), idsOf(SpaceBucket.VERIFIED, rules))
    }

    @Test fun `queued, failed and unsent files are never candidates`() = runBlocking {
        env.index(env.scanned(1), env.scanned(2), env.scanned(3))
        env.enqueue(1)                    // queued, never uploaded
        env.db.media().transition(2, SyncState.LOCAL_ONLY, SyncState.QUEUED, null, false, now)
        env.db.media().transition(2, SyncState.QUEUED, SyncState.UPLOADING, null, false, now) // an attempt in flight
        val all = buckets(SafetyRules(excludeFavorites = false, keepRecentDays = 0, minBackedUpDays = 0))
        assertTrue("nothing is a candidate just because an upload was queued or attempted: $all", all.isEmpty())
    }

    @Test fun `turning rules off widens the list and never narrows it`() = runBlocking {
        backUp(1, 2)
        change(1) { it.copy(isFavorite = true) }; verdict(1, IntegrityVerdict.VERIFIED); verdict(2, IntegrityVerdict.VERIFIED)
        assertEquals(setOf(2L), idsOf(SpaceBucket.VERIFIED))
        assertEquals(setOf(1L, 2L), idsOf(SpaceBucket.VERIFIED, SafetyRules(excludeFavorites = false)))
    }

    @Test fun `videos and album photos can be kept by rule`() = runBlocking {
        env.index(env.scanned(1), env.scanned(2, kind = com.s3vault.app.domain.MediaKind.VIDEO))
        env.enqueue(1, 2); env.engine().run(2)
        listOf(1L, 2L).forEach { id -> change(id) { it.copy(dateTakenMs = now - 200 * day, lastBackupAtMs = now - 100 * day) }; verdict(id, IntegrityVerdict.VERIFIED) }
        val albumId = env.db.albums().insert(com.s3vault.app.data.local.AlbumEntity(name = "Boda", createdAtMs = 1))
        env.db.albums().addItems(listOf(com.s3vault.app.data.local.AlbumItemEntity(albumId, 1, 1)))
        assertEquals(setOf(SpaceBucket.EXCL_VIDEO), buckets(SafetyRules(includeVideos = false)).keys.filter { it.excluded }.toSet())
        assertEquals(setOf(1L), idsOf(SpaceBucket.EXCL_ALBUM, SafetyRules(keepAlbumPhotos = true)))
    }

    @Test fun `sections and select-a-section agree with the list`() = runBlocking {
        backUp(1, 2, 3)
        listOf(1L, 2L, 3L).forEach { verdict(it, IntegrityVerdict.VERIFIED) }
        change(1) { it.copy(dayKey = 20230315, folderName = "Camera") }
        change(2) { it.copy(dayKey = 20230322, folderName = "Camera") }
        change(3) { it.copy(dayKey = 20240105, folderName = "WhatsApp") }
        val rules = SafetyRules()
        val months = env.db.media().observeSpaceGroups(SpaceRecoverySql.groups(rules, now, SpaceBucket.VERIFIED, SpaceGrouping.MONTH)).first()
        assertEquals(listOf("202401" to 1, "202303" to 2), months.map { it.gkey to it.n })
        assertEquals(setOf(1L, 2L), env.db.media().idList(SpaceRecoverySql.ids(rules, now, SpaceBucket.VERIFIED, SpaceGrouping.MONTH, "202303")).toSet())
        assertEquals(setOf(3L), env.db.media().idList(SpaceRecoverySql.ids(rules, now, SpaceBucket.VERIFIED, SpaceGrouping.FOLDER, "WhatsApp")).toSet())
        val rows = env.db.media().observeSpaceRows(SpaceRecoverySql.rows(rules, now, SpaceBucket.VERIFIED, SpaceGrouping.YEAR, 2)).first()
        assertEquals("the limit is a real limit", 2, rows.size)
    }

    // ------------------------------------------------------------------ the last gate before deleting

    @Test fun `the gate lets through only what S3 just confirmed by checksum`() = runBlocking {
        backUp(1, 2)
        val s3Before = env.remote.objects.toMap()

        val batch = controller().verifyForDeletion(listOf(1, 2), SafetyRules())

        assertEquals(setOf(1L, 2L), batch.safeIds.toSet())
        assertEquals("the index was brought up to date first", 1, refreshed)
        assertEquals("S3 was asked for each object right now", 2, env.remote.headCalls - 2 /* two from the backup verification */)
        assertEquals("nothing in S3 was touched", s3Before, env.remote.objects.toMap())
        assertTrue(env.remote.deleteCalls.isEmpty() && env.remote.uploadCalls == 2)
    }

    @Test fun `a vanished or replaced object is dropped with its reason, the healthy one passes`() = runBlocking {
        backUp(1, 2, 3)
        env.remote.objects.remove(item(2).remoteKey!!)
        val k3 = item(3).remoteKey!!
        env.remote.objects[k3] = FakeRemote.Obj("longer-than-before-xx".toByteArray(), "x", "e")
        val batch = controller().verifyForDeletion(listOf(1, 2, 3), SafetyRules())
        assertEquals(listOf(1L), batch.safeIds)
        assertEquals(1, batch.dropped[DropReason.MISSING])
        assertEquals(1, batch.dropped[DropReason.CHANGED])
    }

    @Test fun `no connection means nothing may be deleted`() = runBlocking {
        backUp(1, 2)
        repeat(2) { env.remote.headFailures.addLast(RemoteException(RemoteErrorKind.NETWORK)) }
        val batch = controller().verifyForDeletion(listOf(1, 2), SafetyRules())
        assertTrue(batch.safeIds.isEmpty())
        assertEquals(2, batch.dropped[DropReason.NOT_CONFIRMED])
        assertFalse(batch.credentialsNeeded)
    }

    @Test fun `a refusal from S3 is a doubt and a doubt blocks the deletion`() = runBlocking {
        backUp(1)
        env.remote.headFailures.addLast(RemoteException(RemoteErrorKind.ACCESS_DENIED))
        val batch = controller().verifyForDeletion(listOf(1), SafetyRules())
        assertTrue(batch.safeIds.isEmpty())
        assertEquals(1, batch.dropped[DropReason.NOT_CONFIRMED])
    }

    @Test fun `expired credentials block everything and ask for new ones`() = runBlocking {
        backUp(1, 2)
        env.creds.failure = RemoteException(RemoteErrorKind.CREDENTIALS_EXPIRED)
        val batch = controller().verifyForDeletion(listOf(1, 2), SafetyRules())
        assertTrue(batch.safeIds.isEmpty())
        assertTrue(batch.credentialsNeeded)
    }

    @Test fun `a file that changed or left the device since the selection is dropped before any request`() = runBlocking {
        backUp(1, 2, 3)
        change(2) { it.copy(syncState = SyncState.LOCAL_ONLY) }   // edited and re-indexed as new content
        change(3) { it.copy(present = false) }                    // already gone
        val heads = env.remote.headCalls
        val batch = controller().verifyForDeletion(listOf(1, 2, 3, 999), SafetyRules())
        assertEquals(listOf(1L), batch.safeIds)
        assertEquals(1, batch.dropped[DropReason.LOCAL_CHANGED])
        assertEquals(2, batch.dropped[DropReason.GONE_FROM_DEVICE]) // the gone row and the id that does not exist
        assertEquals("only the survivor was asked about", heads + 1, env.remote.headCalls)
    }

    @Test fun `the rules are applied again at the last moment`() = runBlocking {
        backUp(1)
        change(1) { it.copy(isFavorite = true) } // marked favourite after it was selected
        val batch = controller().verifyForDeletion(listOf(1), SafetyRules())
        assertTrue(batch.safeIds.isEmpty())
        assertEquals(1, batch.dropped[DropReason.RULES])
    }

    @Test fun `deleting locally after the gate keeps the S3 copy and settles partial results`() = runBlocking {
        backUp(1, 2, 3)
        val batch = controller().verifyForDeletion(listOf(1, 2, 3), SafetyRules())
        val entries = batch.safeIds.map { id -> val r = item(id); DeleteEntry(r.displayName, id, r.uri, r.remoteKey, hasLocalCopy = true) }
        // Android deleted two of the three (the user cancelled the dialog for the rest, or the system refused one).
        val stillThere = setOf("content://media/3")
        val result = LocalDeletionController(env.db, object : LocalMediaGateway { override suspend fun exists(uri: String) = uri in stillThere }).settle(entries)

        assertEquals(2, result.deleted)
        assertEquals("both keep their S3 copy", 2, result.keptInCloud)
        assertEquals(1, result.remaining)
        assertEquals(SyncState.REMOTE_ONLY, item(1).syncState)
        assertFalse(item(1).present)
        assertNotNull("the link to S3 is kept so it can be restored", item(1).remoteKey)
        assertEquals(SyncState.BACKED_UP, item(3).syncState)
        assertTrue(item(3).present)
        assertTrue("S3 untouched", env.remote.deleteCalls.isEmpty())
        assertTrue("and nothing is a candidate any more for the files that left", idsOf(SpaceBucket.VERIFIED, SafetyRules(keepRecentDays = 0, minBackedUpDays = 0, verifiedWithinHours = 24 * 365)).none { it == 1L || it == 2L })
    }

    @Test fun `the rules survive a restart`() = runBlocking {
        val settings = AppSettings(env.context)
        val rules = SafetyRules(excludeFavorites = false, keepRecentDays = 90, minBackedUpDays = 14, excludedFolders = setOf("Camera", "Screenshots"), includeVideos = false, keepAlbumPhotos = true, verifiedWithinHours = 48)
        settings.saveSpaceRules(rules)
        assertEquals(rules, AppSettings(env.context).spaceRules.first())
    }
}
