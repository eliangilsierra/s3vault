package com.s3vault.app

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.library.DateKeys
import com.s3vault.app.domain.library.DateSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * Upgrading from an older release must keep every row and give the new columns safe defaults.
 *
 * Room's MigrationTestHelper cannot run here (the project pins kotlinx-serialization to 1.7.3 for the AWS SDK, while
 * the helper needs a newer one), so the old database is rebuilt from Room's own exported schema (`N.json`) and
 * then opened through Room with the real migrations; Room validates the migrated tables against the current version on open.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-test.db"

    @After fun cleanUp() { target.deleteDatabase(name) }

    private fun createDatabase(version: Int, seed: (SQLiteDatabase) -> Unit) {
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.s3vault.app.data.local.AppDatabase/$version.json").bufferedReader().use { JSONObject(it.readText()) }
            .getJSONObject("database")
        target.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(target.getDatabasePath(name), null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                val indices = e.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            seed(db)
            db.version = version
        }
    }

    @Test fun version1To2KeepsRowsAndAddsDefaults() = runBlocking {
        createDatabase(1) { db ->
            db.execSQL(
                "INSERT INTO media_items (mediaStoreId, uri, kind, displayName, mimeType, sizeBytes, dateModifiedSec, dateTakenMs, width, height, " +
                    "folderName, assetId, present, syncState, remoteKey, remoteVersionId, lastErrorPermanent, attempts) " +
                    "VALUES (7, 'content://media/7', 'IMAGE', 'a.jpg', 'image/jpeg', 10, 1, 2, 3, 4, 'Camera', 'asset', 1, 'BACKED_UP', 'k', 'v', 0, 0)",
            )
        }
        val db = Room.databaseBuilder(target, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS).build()
        try {
            val row = db.media().get(7)
            assertNotNull("the row survived", row)
            assertEquals("a.jpg", row!!.displayName)
            assertEquals(SyncState.BACKED_UP, row.syncState)
            assertEquals("k", row.remoteKey)
            assertFalse("previews of older backups are still to be created", row.thumbnailUploaded)
            assertFalse("nothing was deleted on purpose before", row.backupExcluded)
            assertEquals(1, db.media().thumbnailBacklog(10).size)
        } finally {
            db.close()
        }
    }

    @Test fun version2To3KeepsRowsAndComputesTheDateColumns() = runBlocking {
        val zone = ZoneId.systemDefault()
        val taken = java.time.ZonedDateTime.of(2023, 3, 15, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val uploaded = java.time.ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, zone).toInstant().toEpochMilli()
        val key = "p/photos/asset/version/IMG_20230315_101010.jpg"
        createDatabase(2) { db ->
            db.execSQL(
                "INSERT INTO media_items (mediaStoreId, uri, kind, displayName, mimeType, sizeBytes, dateModifiedSec, dateTakenMs, width, height, " +
                    "folderName, assetId, present, syncState, remoteKey, remoteVersionId, lastErrorPermanent, attempts, thumbnailUploaded, backupExcluded) " +
                    "VALUES (7, 'content://media/7', 'IMAGE', 'a.jpg', 'image/jpeg', 10, 1, $taken, 3, 4, 'Camera', 'asset', 1, 'BACKED_UP', 'k', 'v', 0, 0, 1, 0)",
            )
            db.execSQL(
                "INSERT INTO remote_objects (`key`, assetId, versionId, displayName, sizeBytes, lastModifiedMs, state) " +
                    "VALUES ('$key', 'asset', 'version', 'IMG_20230315_101010.jpg', 99, $uploaded, 'REMOTE_ONLY')",
            )
            db.execSQL(
                "INSERT INTO remote_objects (`key`, assetId, versionId, displayName, sizeBytes, lastModifiedMs, state) " +
                    "VALUES ('p/photos/b/v/clip.mp4', 'b', 'v', 'clip.mp4', 5, $uploaded, 'REMOTE_ONLY')",
            )
        }
        val db = Room.databaseBuilder(target, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS).build()
        try {
            val media = db.media().get(7)!!
            assertEquals("the row survived", "a.jpg", media.displayName)
            assertEquals(true, media.thumbnailUploaded)
            assertEquals("the day is the one the photo was taken, in the device zone", DateKeys.dayKey(taken, zone), media.dayKey)
            assertEquals("existing dates stay capture dates until the first scan says otherwise", DateSource.CAPTURE, media.dateSource)

            val remote = db.remote().get(key)!!
            assertEquals(99L, remote.sizeBytes)
            assertEquals("the upload time is all a listing knew", uploaded, remote.effectiveDateMs)
            assertEquals(DateSource.FALLBACK, remote.dateSource)
            assertEquals("an upload time is not a capture date: no place on the timeline", 0, remote.dayKey)
            assertEquals(DateKeys.dayKey(uploaded, zone), remote.uploadDayKey)
            assertEquals(MediaKind.IMAGE, remote.mediaKind)
            assertFalse(remote.metaChecked)
            assertEquals(MediaKind.VIDEO, db.remote().get("p/photos/b/v/clip.mp4")!!.mediaKind)
        } finally {
            db.close()
        }
    }

    @Test fun version3To4KeepsAlbumsAndAddsRoomForPhotosOnlyInS3() = runBlocking {
        createDatabase(3) { db ->
            db.execSQL("INSERT INTO albums (id, name, createdAtMs) VALUES (1, 'Amor', 5)")
            db.execSQL(
                "INSERT INTO media_items (mediaStoreId, uri, kind, displayName, mimeType, sizeBytes, dateModifiedSec, dateTakenMs, width, height, " +
                    "folderName, assetId, present, syncState, remoteKey, remoteVersionId, lastErrorPermanent, attempts, thumbnailUploaded, backupExcluded, dayKey, dateSource) " +
                    "VALUES (7, 'content://media/7', 'IMAGE', 'a.jpg', 'image/jpeg', 10, 1, 2, 3, 4, 'Camera', 'asset', 1, 'LOCAL_ONLY', NULL, NULL, 0, 0, 0, 0, 0, 'CAPTURE')",
            )
            db.execSQL("INSERT INTO album_items (albumId, mediaStoreId, addedAtMs) VALUES (1, 7, 6)")
        }
        val db = Room.databaseBuilder(target, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS).build()
        try {
            assertEquals("the album survived", listOf("Amor"), db.albums().all().map { it.name })
            assertEquals(listOf(7L), db.albums().memberIds(1))
            assertEquals("a migrated album has no S3-only members yet", emptyList<String>(), db.albums().cloudMemberVersions(1))

            db.albums().addRemoteItems(listOf(com.s3vault.app.data.local.AlbumRemoteItemEntity(1, "a".repeat(64), 9)))
            assertEquals(listOf("a".repeat(64)), db.albums().cloudMemberVersions(1))
            db.albums().delete(1)
            assertEquals("memberships go with their album", emptyList<String>(), db.albums().cloudMemberVersions(1))
        } finally {
            db.close()
        }
    }

    @Test fun version4To5KeepsEverythingAndAddsTheJobSummaryTable() = runBlocking {
        createDatabase(4) { db ->
            db.execSQL("INSERT INTO albums (id, name, createdAtMs) VALUES (1, 'Amor', 5)")
            db.execSQL(
                "INSERT INTO media_items (mediaStoreId, uri, kind, displayName, mimeType, sizeBytes, dateModifiedSec, dateTakenMs, width, height, " +
                    "folderName, assetId, present, syncState, remoteKey, remoteVersionId, lastErrorPermanent, attempts, thumbnailUploaded, backupExcluded, dayKey, dateSource) " +
                    "VALUES (7, 'content://media/7', 'IMAGE', 'a.jpg', 'image/jpeg', 10, 1, 2, 3, 4, 'Camera', 'asset', 1, 'QUEUED', NULL, NULL, 0, 0, 0, 0, 0, 'CAPTURE')",
            )
        }
        val db = Room.databaseBuilder(target, AppDatabase::class.java, name).addMigrations(*AppDatabase.ALL_MIGRATIONS).build()
        try {
            assertEquals("the queued photo is still queued", SyncState.QUEUED, db.media().get(7)!!.syncState)
            assertEquals(listOf("Amor"), db.albums().all().map { it.name })
            assertEquals("a migrated database has no run summary yet", null, db.jobs().get("backup"))
            db.jobs().upsert(com.s3vault.app.data.local.JobEntity("backup", com.s3vault.app.data.local.JobState.COMPLETED, startedAtMs = 1, updatedAtMs = 2))
            assertEquals(com.s3vault.app.data.local.JobState.COMPLETED, db.jobs().get("backup")!!.state)
        } finally {
            db.close()
        }
    }

    private val v5Media = "INSERT INTO media_items (mediaStoreId, uri, kind, displayName, mimeType, sizeBytes, dateModifiedSec, dateTakenMs, width, height, " +
        "folderName, assetId, present, syncState, remoteKey, remoteVersionId, lastErrorPermanent, attempts, thumbnailUploaded, backupExcluded, dayKey, dateSource) " +
        "VALUES (7, 'content://media/7', 'IMAGE', 'a.jpg', 'image/jpeg', 10, 1, 2, 3, 4, 'Camera', 'asset', 1, 'BACKED_UP', 'p/photos/asset/v/a.jpg', 'v', 0, 0, 0, 0, 0, 'CAPTURE')"

    @Test fun version5ToCurrentKeepsTheRowsAndGivesEveryLaterFeatureItsDefaults() = runBlocking {
        createDatabase(5) { db ->
            db.execSQL("INSERT INTO albums (id, name, createdAtMs) VALUES (1, 'Amor', 5)")
            db.execSQL(v5Media)
            db.execSQL(
                "INSERT INTO remote_objects (`key`, assetId, versionId, displayName, sizeBytes, lastModifiedMs, state, effectiveDateMs, dateSource, dayKey, uploadDayKey, metaChecked) " +
                    "VALUES ('p/photos/asset/v/a.jpg', 'asset', 'v', 'a.jpg', 10, 5, 'REMOTE_ONLY', 5, 'FALLBACK', 0, 0, 0)",
            )
        }
        val db = Room.databaseBuilder(target, AppDatabase::class.java, name).addMigrations(*AppDatabase.ALL_MIGRATIONS).build()
        try {
            val m = db.media().get(7)!!
            assertEquals("the backed-up photo is still backed up", SyncState.BACKED_UP, m.syncState)
            assertEquals("p/photos/asset/v/a.jpg", m.remoteKey)
            assertEquals(false, m.isFavorite); assertEquals(0L, m.dateAddedSec); assertEquals(null, m.remoteEtag)
            assertEquals(listOf("Amor"), db.albums().all().map { it.name })
            assertEquals(null, db.remote().getByKeys(listOf("p/photos/asset/v/a.jpg")).single().etag)
            // Every table added after version 5 is there and usable.
            assertEquals(0, db.privateVault().itemCount())
            assertEquals(null, db.privateVault().vault())
            assertEquals(emptyList<Any>(), db.smartAlbums().observeAll().first())
        } finally {
            db.close()
        }
    }

    @Test fun version10To11AddsThePrivateTablesAndTouchesNothingElse() = runBlocking {
        createDatabase(10) { db ->
            db.execSQL(v5Media.replace("dateSource) VALUES", "dateSource, remoteEtag, isFavorite, dateAddedSec) VALUES").replace("'CAPTURE')", "'CAPTURE', 'etag1', 1, 99)"))
            db.execSQL("INSERT INTO smart_albums (id, name, filterJson, createdAtMs) VALUES (1, 'Mis fotos', '{}', 3)")
            db.execSQL("INSERT INTO semantic_embeddings (mediaStoreId, modelId, sizeBytes, dateModifiedSec, ok, scale, vector, indexedAtMs) VALUES (7, 'm', 10, 1, 1, 0.5, x'0102', 9)")
        }
        val db = Room.databaseBuilder(target, AppDatabase::class.java, name).addMigrations(*AppDatabase.ALL_MIGRATIONS).build()
        try {
            val m = db.media().get(7)!!
            assertEquals("etag1", m.remoteEtag); assertEquals(true, m.isFavorite); assertEquals(99L, m.dateAddedSec)
            assertEquals(listOf("Mis fotos"), db.smartAlbums().observeAll().first().map { it.name })
            assertEquals("the vectors survived", 1, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM semantic_embeddings").use { it.moveToFirst(); it.getInt(0) })
            val dao = db.privateVault()
            assertEquals(null, dao.vault())
            dao.insertVault(com.s3vault.app.data.local.PrivateVaultEntity(wrappedKey = byteArrayOf(1), wrapIv = byteArrayOf(2), keyCheck = byteArrayOf(3), createdAtMs = 1))
            val album = dao.insertAlbum(com.s3vault.app.data.local.PrivateAlbumEntity(nameSealed = byteArrayOf(9), createdAtMs = 2))
            dao.insertItem(com.s3vault.app.data.local.PrivateItemEntity(albumId = album, fileName = "f", sealedBytes = 5, addedAtMs = 3, metaSealed = byteArrayOf(7)))
            assertEquals(1, dao.itemCount())
            dao.deleteAlbum(album)
            assertEquals("items go with their album", 0, dao.itemCount())
            assertEquals("the photo index was never touched", 1, db.media().get(7)?.let { 1 } ?: 0)
        } finally {
            db.close()
        }
    }

    @Test fun version11To12AddsTheFaceTablesAndTouchesNothingElse() = runBlocking {
        createDatabase(11) { db ->
            db.execSQL(v5Media.replace("dateSource) VALUES", "dateSource, remoteEtag, isFavorite, dateAddedSec) VALUES").replace("'CAPTURE')", "'CAPTURE', 'etag1', 1, 99)"))
            db.execSQL("INSERT INTO albums (id, name, createdAtMs) VALUES (1, 'Amor', 5)")
            db.execSQL("INSERT INTO album_items (albumId, mediaStoreId, addedAtMs) VALUES (1, 7, 6)")
            db.execSQL("INSERT INTO semantic_embeddings (mediaStoreId, modelId, sizeBytes, dateModifiedSec, ok, scale, vector, indexedAtMs) VALUES (7, 'm', 10, 1, 1, 0.5, x'0102', 9)")
        }
        val db = Room.databaseBuilder(target, AppDatabase::class.java, name).addMigrations(*AppDatabase.ALL_MIGRATIONS).build()
        try {
            val m = db.media().get(7)!!
            assertEquals("the photo and its backup state survived", SyncState.BACKED_UP, m.syncState)
            assertEquals("etag1", m.remoteEtag)
            assertEquals(listOf("Amor"), db.albums().all().map { it.name })
            assertEquals("the search vectors survived", 1, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM semantic_embeddings").use { it.moveToFirst(); it.getInt(0) })
            val dao = db.faces()
            // Nothing is analysed, grouped or ruled until the user turns the feature on.
            assertEquals(0, dao.people().size)
            assertEquals(0, dao.rules().size)
            assertEquals(1, dao.needAnalysisCount("model", 1))
            val now = 10L
            val person = dao.insertPerson(com.s3vault.app.data.local.PersonEntity(ordinal = 1, createdAtMs = now, updatedAtMs = now))
            val face = dao.insertFaces(listOf(com.s3vault.app.data.local.FaceEntity(
                mediaStoreId = 7, boxLeft = 0.1f, boxTop = 0.1f, boxRight = 0.4f, boxBottom = 0.5f, detScore = 0.9f, quality = 0.8f, modelId = "model",
                sealed = byteArrayOf(1, 2, 3), personId = person, assignment = com.s3vault.app.domain.faces.FaceAssignment.AUTO, createdAtMs = now,
            ))).single()
            dao.insertRejections(listOf(com.s3vault.app.data.local.FaceRejectionEntity(face, person, now)))
            dao.upsertRule(com.s3vault.app.data.local.FaceBackupRuleEntity(person, com.s3vault.app.domain.faces.RuleState.ACTIVE, com.s3vault.app.domain.faces.RuleScope.NEW_ONLY, com.s3vault.app.domain.faces.RulePolicy.STRICT, now, 5, false, now))
            dao.insertExclusions(listOf(com.s3vault.app.data.local.FaceRuleExclusionEntity(person, 7, now)))
            dao.upsertDecisions(listOf(com.s3vault.app.data.local.FaceBackupDecisionEntity(7, person, com.s3vault.app.domain.faces.DecisionOutcome.REVIEW, com.s3vault.app.domain.faces.DecisionReason.AMBIGUOUS, now)))
            // Deleting a group removes its rule, exclusions, decisions and rejections, frees its faces, and never touches the photo.
            dao.deletePerson(person)
            assertEquals(0, dao.rules().size)
            assertEquals(0, dao.exclusions(person).size)
            assertEquals(0, dao.decisions(person).size)
            assertEquals(0, dao.rejectionsOf(listOf(face)).size)
            assertEquals(null, dao.face(face)!!.personId)
            assertEquals("the photo was never touched", SyncState.BACKED_UP, db.media().get(7)!!.syncState)
        } finally {
            db.close()
        }
    }
}
