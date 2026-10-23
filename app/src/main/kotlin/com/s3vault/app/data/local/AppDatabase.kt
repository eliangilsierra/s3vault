package com.s3vault.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.s3vault.app.domain.library.DateKeys
import com.s3vault.app.domain.library.MediaKinds
import java.time.ZoneId

@Database(
    entities = [
        MediaItemEntity::class,
        RemoteObjectEntity::class,
        AlbumEntity::class,
        AlbumItemEntity::class,
        AlbumRemoteItemEntity::class,
        TransferEventEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun media(): MediaDao
    abstract fun remote(): RemoteDao
    abstract fun albums(): AlbumDao
    abstract fun events(): EventDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "s3vault.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()

        /** Albums can hold photos that exist only in S3 (imported from the bucket's album file). Existing albums are untouched. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `album_remote_items` (`albumId` INTEGER NOT NULL, `versionId` TEXT NOT NULL, " +
                        "`addedAtMs` INTEGER NOT NULL, PRIMARY KEY(`albumId`, `versionId`), " +
                        "FOREIGN KEY(`albumId`) REFERENCES `albums`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_remote_items_versionId` ON `album_remote_items` (`versionId`)")
            }
        }

        /**
         * Adds the date model of the smart gallery (day keys, where each date comes from, object kind) and the indexes that
         * make listing and grouping fast. Existing rows keep their date and get a day key in the current zone; the first
         * library scan after the update corrects the date source of local files.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN dayKey INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media_items ADD COLUMN dateSource TEXT NOT NULL DEFAULT 'CAPTURE'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_present_dateTakenMs ON media_items (present, dateTakenMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_present_dayKey ON media_items (present, dayKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_remoteKey ON media_items (remoteKey)")
                db.execSQL("ALTER TABLE remote_objects ADD COLUMN effectiveDateMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE remote_objects ADD COLUMN dateSource TEXT NOT NULL DEFAULT 'FALLBACK'")
                db.execSQL("ALTER TABLE remote_objects ADD COLUMN dayKey INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE remote_objects ADD COLUMN uploadDayKey INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE remote_objects ADD COLUMN mediaKind TEXT")
                db.execSQL("ALTER TABLE remote_objects ADD COLUMN metaChecked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_remote_objects_dayKey_effectiveDateMs ON remote_objects (dayKey, effectiveDateMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_remote_objects_uploadDayKey ON remote_objects (uploadDayKey)")

                val zone = ZoneId.systemDefault()
                val local = ArrayList<Pair<Long, Long>>()
                db.query("SELECT mediaStoreId, dateTakenMs FROM media_items").use { c -> while (c.moveToNext()) local += c.getLong(0) to c.getLong(1) }
                val setDay = db.compileStatement("UPDATE media_items SET dayKey = ? WHERE mediaStoreId = ?")
                for ((id, ms) in local) {
                    setDay.bindLong(1, DateKeys.dayKey(ms, zone).toLong()); setDay.bindLong(2, id); setDay.executeUpdateDelete()
                }
                setDay.close()

                db.execSQL("UPDATE remote_objects SET effectiveDateMs = lastModifiedMs")
                val remote = ArrayList<Triple<String, String, Long>>()
                db.query("SELECT `key`, displayName, lastModifiedMs FROM remote_objects").use { c ->
                    while (c.moveToNext()) remote += Triple(c.getString(0), c.getString(1), c.getLong(2))
                }
                val setRemote = db.compileStatement("UPDATE remote_objects SET uploadDayKey = ?, mediaKind = ? WHERE `key` = ?")
                for ((key, name, uploaded) in remote) {
                    setRemote.bindLong(1, DateKeys.dayKey(uploaded, zone).toLong())
                    MediaKinds.fromName(name)?.let { setRemote.bindString(2, it.name) } ?: setRemote.bindNull(2)
                    setRemote.bindString(3, key)
                    setRemote.executeUpdateDelete()
                }
                setRemote.close()
            }
        }

        /** Adds the preview-upload marker and the "deleted from S3 on purpose" flag. Existing rows keep both off. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN thumbnailUploaded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media_items ADD COLUMN backupExcluded INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
