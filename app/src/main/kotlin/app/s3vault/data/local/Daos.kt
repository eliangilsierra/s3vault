package app.s3vault.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.s3vault.domain.model.SyncState
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MediaItemDao {
    /** La galería solo muestra lo que existe en el dispositivo. */
    @Query(
        "SELECT * FROM media_items WHERE uri IS NOT NULL AND state NOT IN ('REMOTE_ONLY','RESTORING') " +
            "ORDER BY sortDate DESC, assetId",
    )
    abstract fun observeGallery(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE assetId = :assetId")
    abstract fun observeOne(assetId: String): Flow<MediaItemEntity?>

    @Query("SELECT * FROM media_items WHERE assetId = :assetId")
    abstract suspend fun get(assetId: String): MediaItemEntity?

    @Query("SELECT * FROM media_items")
    abstract suspend fun getAll(): List<MediaItemEntity>

    @Query(
        "SELECT m.* FROM media_items m JOIN album_items a ON a.assetId = m.assetId " +
            "WHERE a.albumId = :albumId AND m.uri IS NOT NULL AND m.state NOT IN ('REMOTE_ONLY','RESTORING') " +
            "ORDER BY m.sortDate DESC, m.assetId",
    )
    abstract fun observeAlbum(albumId: Long): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE state = 'QUEUED' ORDER BY dateModifiedMs ASC LIMIT :limit")
    abstract suspend fun queued(limit: Int): List<MediaItemEntity>

    @Upsert
    abstract suspend fun upsert(item: MediaItemEntity)

    @Upsert
    abstract suspend fun upsertAll(items: List<MediaItemEntity>)

    @Query("DELETE FROM media_items WHERE assetId IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<String>)

    /** Compare-and-set: solo escribe si el estado actual coincide con el esperado. */
    @Transaction
    open suspend fun compareAndSet(expected: SyncState, updated: MediaItemEntity): Boolean {
        val current = get(updated.assetId) ?: return false
        if (current.state != expected) return false
        upsert(updated)
        return true
    }

    @Transaction
    open suspend fun applyScan(upserts: List<MediaItemEntity>, deletes: List<String>) {
        if (upserts.isNotEmpty()) upsertAll(upserts)
        if (deletes.isNotEmpty()) deletes.chunked(500).forEach { deleteByIds(it) }
    }

    /** Arranque en frío: lo que estaba subiéndose o verificándose vuelve a la cola. */
    @Query("UPDATE media_items SET state = 'QUEUED' WHERE state IN ('UPLOADING','VERIFYING')")
    abstract suspend fun recoverInterrupted(): Int

    @Query(
        "UPDATE media_items SET state = 'QUEUED', lastError = NULL, lastErrorKind = NULL, attempts = 0 " +
            "WHERE assetId IN (:ids) AND state IN ('LOCAL_ONLY','FAILED')",
    )
    abstract suspend fun enqueue(ids: List<String>): Int

    @Query("UPDATE media_items SET state = 'QUEUED', lastError = NULL, lastErrorKind = NULL, attempts = 0 WHERE state = 'FAILED'")
    abstract suspend fun retryAllFailed(): Int

    @Query("UPDATE media_items SET state = 'LOCAL_ONLY' WHERE state = 'QUEUED'")
    abstract suspend fun cancelQueued(): Int

    @Query("SELECT assetId FROM media_items WHERE state = 'LOCAL_ONLY' AND uri IS NOT NULL AND (:includeVideos = 1 OR isVideo = 0)")
    abstract suspend fun pendingAssetIds(includeVideos: Boolean): List<String>

    @Query("SELECT state AS state, COUNT(*) AS count FROM media_items GROUP BY state")
    abstract fun observeCounts(): Flow<List<StateCount>>

    @Query("SELECT * FROM media_items WHERE state IN ('UPLOADING','VERIFYING') ORDER BY sortDate DESC")
    abstract fun observeInFlight(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE state = 'QUEUED' ORDER BY dateModifiedMs ASC LIMIT :limit")
    abstract fun observeQueued(limit: Int): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE state = 'FAILED' ORDER BY sortDate DESC LIMIT :limit")
    abstract fun observeFailed(limit: Int): Flow<List<MediaItemEntity>>

    @Query("SELECT MAX(lastBackupAtMs) FROM media_items")
    abstract fun observeLastBackup(): Flow<Long?>

    /** Vínculos con objetos remotos, para saber qué entradas de la biblioteca remota ya están en el dispositivo. */
    @Query("SELECT remoteKey AS remoteKey, state AS state, (uri IS NOT NULL) AS hasLocalFile FROM media_items WHERE remoteKey IS NOT NULL")
    abstract fun observeRemoteLinks(): Flow<List<RemoteLink>>

    /**
     * El destino (bucket/prefijo) cambió: los estados de respaldo dejan de ser ciertos. Se reinician
     * solo los metadatos locales; los objetos en el bucket anterior no se tocan.
     */
    @Transaction
    open suspend fun resetForNewDestination() {
        clearSyncMetadata()
        deleteRemoteOnlyRows()
    }

    @Query(
        "UPDATE media_items SET state = 'LOCAL_ONLY', remoteKey = NULL, backedUpVersionId = NULL, lastBackupAtMs = NULL, " +
            "lastError = NULL, lastErrorKind = NULL, attempts = 0 WHERE state != 'REMOTE_ONLY' AND state != 'RESTORING'",
    )
    abstract suspend fun clearSyncMetadata()

    @Query("DELETE FROM media_items WHERE state IN ('REMOTE_ONLY','RESTORING')")
    abstract suspend fun deleteRemoteOnlyRows()

    @Query("SELECT COUNT(*) FROM media_items WHERE state = 'QUEUED' AND lastErrorKind IS NOT NULL")
    abstract fun observeQueuedWithErrors(): Flow<Int>
}

@Dao
abstract class AlbumDao {
    @Query(
        "SELECT a.id AS id, a.name AS name, a.createdAtMs AS createdAtMs, " +
            "(SELECT COUNT(*) FROM album_items x JOIN media_items m ON m.assetId = x.assetId " +
            "  WHERE x.albumId = a.id AND m.uri IS NOT NULL AND m.state NOT IN ('REMOTE_ONLY','RESTORING')) AS itemCount, " +
            "(SELECT m.uri FROM album_items x JOIN media_items m ON m.assetId = x.assetId " +
            "  WHERE x.albumId = a.id AND m.uri IS NOT NULL AND m.state NOT IN ('REMOTE_ONLY','RESTORING') " +
            "  ORDER BY x.addedAtMs DESC LIMIT 1) AS coverUri " +
            "FROM albums a ORDER BY a.name COLLATE NOCASE",
    )
    abstract fun observeSummaries(): Flow<List<AlbumSummary>>

    @Query("SELECT * FROM albums WHERE id = :id")
    abstract fun observe(id: Long): Flow<AlbumEntity?>

    @Insert
    abstract suspend fun insert(album: AlbumEntity): Long

    @Query("UPDATE albums SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String)

    /** Borrar un álbum solo borra la agrupación (ON DELETE CASCADE sobre `album_items`), nunca las fotos. */
    @Query("DELETE FROM albums WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addItems(items: List<AlbumItemEntity>)

    @Query("DELETE FROM album_items WHERE albumId = :albumId AND assetId IN (:assetIds)")
    abstract suspend fun removeItems(albumId: Long, assetIds: List<String>)

    @Query("SELECT albumId FROM album_items WHERE assetId = :assetId")
    abstract fun observeAlbumIdsOf(assetId: String): Flow<List<Long>>
}

@Dao
abstract class RemoteObjectDao {
    @Query("SELECT * FROM remote_objects ORDER BY COALESCE(lastModifiedMs, 0) DESC, key")
    abstract fun observeAll(): Flow<List<RemoteObjectEntity>>

    @Query("SELECT * FROM remote_objects")
    abstract suspend fun getAll(): List<RemoteObjectEntity>

    @Query("SELECT * FROM remote_objects WHERE key = :key")
    abstract suspend fun get(key: String): RemoteObjectEntity?

    @Query("SELECT key FROM remote_objects WHERE versionId = :versionId LIMIT 1")
    abstract suspend fun findKeyByVersion(versionId: String): String?

    @Upsert
    abstract suspend fun upsertAll(items: List<RemoteObjectEntity>)

    @Upsert
    abstract suspend fun upsert(item: RemoteObjectEntity)

    @Query("DELETE FROM remote_objects WHERE key IN (:keys)")
    abstract suspend fun deleteKeys(keys: List<String>)

    /** Sustituye la caché por el resultado de un LIST completo (en una sola transacción). */
    @Transaction
    open suspend fun replaceAll(rows: List<RemoteObjectEntity>) {
        val keep = rows.map { it.key }.toSet()
        val stale = getAll().map { it.key }.filter { it !in keep }
        stale.chunked(500).forEach { deleteKeys(it) }
        upsertAll(rows)
    }

    @Query("DELETE FROM remote_objects")
    abstract suspend fun clear()

    @Query("UPDATE remote_objects SET restoreStatus = :status, restoreError = :error WHERE key = :key")
    abstract suspend fun setRestoreStatus(key: String, status: RestoreStatus, error: String?)

    /** Tras morir el proceso, lo que figuraba "en curso" vuelve a estar en cola. */
    @Query("UPDATE remote_objects SET restoreStatus = 'QUEUED' WHERE restoreStatus = 'RUNNING'")
    abstract suspend fun requeueRunningRestores(): Int

    @Query("SELECT MAX(refreshedAtMs) FROM remote_objects")
    abstract fun observeLastRefresh(): Flow<Long?>

    @Query("SELECT MAX(refreshedAtMs) FROM remote_objects")
    abstract suspend fun lastRefresh(): Long?
}

@Dao
abstract class TransferLogDao {
    @Insert
    abstract suspend fun insert(entry: TransferLogEntity)

    @Query("SELECT * FROM transfer_log ORDER BY timestampMs DESC LIMIT :limit")
    abstract fun observeRecent(limit: Int): Flow<List<TransferLogEntity>>

    @Query("DELETE FROM transfer_log WHERE id NOT IN (SELECT id FROM transfer_log ORDER BY timestampMs DESC LIMIT :keep)")
    abstract suspend fun trim(keep: Int)

    @Query("DELETE FROM transfer_log")
    abstract suspend fun clear()
}
