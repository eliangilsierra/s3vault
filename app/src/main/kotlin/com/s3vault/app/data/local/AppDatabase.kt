package com.s3vault.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MediaItemEntity::class,
        RemoteObjectEntity::class,
        AlbumEntity::class,
        AlbumItemEntity::class,
        TransferEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun media(): MediaDao
    abstract fun remote(): RemoteDao
    abstract fun albums(): AlbumDao
    abstract fun events(): EventDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "s3vault.db").build()
    }
}
