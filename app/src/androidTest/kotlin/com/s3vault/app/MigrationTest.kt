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
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()
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
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()
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
}
