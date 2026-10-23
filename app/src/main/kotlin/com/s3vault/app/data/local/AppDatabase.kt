package com.s3vault.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MediaItemEntity::class,
        RemoteObjectEntity::class,
        AlbumEntity::class,
        AlbumItemEntity::class,
        TransferEventEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun media(): MediaDao
    abstract fun remote(): RemoteDao
    abstract fun albums(): AlbumDao
    abstract fun events(): EventDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "s3vault.db").addMigrations(MIGRATION_1_2).build()

        /** Adds the preview-upload marker and the "deleted from S3 on purpose" flag. Existing rows keep both off. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN thumbnailUploaded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media_items ADD COLUMN backupExcluded INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
