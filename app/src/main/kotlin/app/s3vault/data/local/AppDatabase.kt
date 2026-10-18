package app.s3vault.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import app.s3vault.domain.model.ErrorKind
import app.s3vault.domain.model.SyncState

class Converters {
    @TypeConverter fun syncState(v: SyncState): String = v.name
    @TypeConverter fun syncState(v: String): SyncState = SyncState.valueOf(v)
    @TypeConverter fun errorKind(v: ErrorKind?): String? = v?.name
    @TypeConverter fun errorKind(v: String?): ErrorKind? = v?.let { runCatching { ErrorKind.valueOf(it) }.getOrNull() }
    @TypeConverter fun restoreStatus(v: RestoreStatus): String = v.name
    @TypeConverter fun restoreStatus(v: String): RestoreStatus = RestoreStatus.valueOf(v)
}

@Database(
    entities = [
        MediaItemEntity::class, AlbumEntity::class, AlbumItemEntity::class,
        RemoteObjectEntity::class, TransferLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun media(): MediaItemDao
    abstract fun albums(): AlbumDao
    abstract fun remoteObjects(): RemoteObjectDao
    abstract fun transferLog(): TransferLogDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "s3vault.db").build()

        /** Base en memoria para pruebas instrumentadas. */
        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }
}
