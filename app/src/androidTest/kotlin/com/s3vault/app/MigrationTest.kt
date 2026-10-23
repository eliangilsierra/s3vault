package com.s3vault.app

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.domain.SyncState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upgrading from the first release must keep every row and give the new columns safe defaults.
 *
 * Room's MigrationTestHelper cannot run here (the project pins kotlinx-serialization to 1.7.3 for the AWS SDK, while
 * the helper needs a newer one), so the version 1 database is rebuilt from Room's own exported schema (`1.json`) and
 * then opened through Room with the real migration; Room validates the migrated tables against version 2 on open.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-test.db"

    @After fun cleanUp() { target.deleteDatabase(name) }

    private fun createVersion1Database() {
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.s3vault.app.data.local.AppDatabase/1.json").bufferedReader().use { JSONObject(it.readText()) }
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
            db.execSQL(
                "INSERT INTO media_items (mediaStoreId, uri, kind, displayName, mimeType, sizeBytes, dateModifiedSec, dateTakenMs, width, height, " +
                    "folderName, assetId, present, syncState, remoteKey, remoteVersionId, lastErrorPermanent, attempts) " +
                    "VALUES (7, 'content://media/7', 'IMAGE', 'a.jpg', 'image/jpeg', 10, 1, 2, 3, 4, 'Camera', 'asset', 1, 'BACKED_UP', 'k', 'v', 0, 0)",
            )
            db.version = 1
        }
    }

    @Test fun version1To2KeepsRowsAndAddsDefaults() = runBlocking {
        createVersion1Database()
        val db = Room.databaseBuilder(target, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_1_2).build()
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
}
