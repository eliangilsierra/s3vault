package com.s3vault.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.s3vault.app.domain.SyncState
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_items WHERE present = 1 AND (:includeVideos = 1 OR kind = 'IMAGE') ORDER BY dateTakenMs DESC, mediaStoreId DESC")
    fun observeGallery(includeVideos: Boolean): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE mediaStoreId = :id")
    suspend fun get(id: Long): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE mediaStoreId = :id")
    fun observe(id: Long): Flow<MediaItemEntity?>

    @Query("SELECT * FROM media_items WHERE mediaStoreId IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<MediaItemEntity>

    @Query("SELECT * FROM media_items")
    suspend fun getEverything(): List<MediaItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MediaItemEntity>)

    @Update
    suspend fun updateAll(items: List<MediaItemEntity>)

    @Update
    suspend fun update(item: MediaItemEntity)

    @Query("DELETE FROM media_items WHERE mediaStoreId IN (:ids)")
    suspend fun deleteLocalRows(ids: List<Long>)

    @Query("SELECT syncState, COUNT(*) AS n FROM media_items WHERE present = 1 OR syncState != 'LOCAL_ONLY' GROUP BY syncState")
    fun observeCounts(): Flow<List<StateCount>>

    @Query("SELECT * FROM media_items WHERE syncState IN (:states) AND present = 1 ORDER BY dateTakenMs DESC")
    fun observeByStates(states: List<SyncState>): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE syncState = 'QUEUED' AND present = 1 ORDER BY queuedAtMs ASC, mediaStoreId ASC LIMIT :limit")
    suspend fun nextQueued(limit: Int): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE syncState = 'QUEUED' AND present = 1 AND mediaStoreId NOT IN (:excluded) ORDER BY queuedAtMs ASC, mediaStoreId ASC LIMIT :limit")
    suspend fun nextQueuedExcluding(excluded: List<Long>, limit: Int): List<MediaItemEntity>

    @Query("SELECT remoteKey FROM media_items WHERE remoteKey IS NOT NULL")
    fun observeBackedUpKeys(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM media_items WHERE contentSha256 = :sha AND present = 1")
    suspend fun countLocalWithHash(sha: String): Int

    @Query("SELECT COUNT(*) FROM media_items WHERE syncState = 'QUEUED' AND present = 1")
    suspend fun queuedCount(): Int

    @Query("SELECT * FROM media_items WHERE syncState = 'LOCAL_ONLY' AND present = 1 AND (:includeVideos = 1 OR kind = 'IMAGE')")
    suspend fun pendingLocalOnly(includeVideos: Boolean): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE syncState = 'FAILED' AND present = 1")
    suspend fun failed(): List<MediaItemEntity>

    /**
     * Optimistic state change: succeeds only if the row is still in [from]. Callers validate the pair with
     * SyncStateMachine first.
     */
    @Query(
        "UPDATE media_items SET syncState = :to, lastError = :error, lastErrorPermanent = :permanent, " +
            "queuedAtMs = CASE WHEN :to = 'QUEUED' THEN COALESCE(queuedAtMs, :nowMs) ELSE NULL END " +
            "WHERE mediaStoreId = :id AND syncState = :from",
    )
    suspend fun transition(id: Long, from: SyncState, to: SyncState, error: String?, permanent: Boolean, nowMs: Long): Int

    @Query("UPDATE media_items SET attempts = attempts + 1 WHERE mediaStoreId = :id")
    suspend fun incrementAttempts(id: Long)

    @Query("UPDATE media_items SET contentSha256 = :sha, hashSizeBytes = :size, hashDateModifiedSec = :mtime WHERE mediaStoreId = :id")
    suspend fun saveHash(id: Long, sha: String, size: Long, mtime: Long)

    @Query(
        "UPDATE media_items SET syncState = 'BACKED_UP', remoteKey = :key, remoteVersionId = :version, lastBackupAtMs = :nowMs, " +
            "backedUpSizeBytes = :size, backedUpDateModifiedSec = :mtime, lastError = NULL, lastErrorPermanent = 0, attempts = 0, queuedAtMs = NULL " +
            "WHERE mediaStoreId = :id AND syncState = 'VERIFYING'",
    )
    suspend fun markBackedUp(id: Long, key: String, version: String, nowMs: Long, size: Long, mtime: Long): Int

    @Query("UPDATE media_items SET syncState = 'QUEUED', queuedAtMs = :nowMs, lastError = NULL, lastErrorPermanent = 0, attempts = 0 WHERE mediaStoreId = :id AND syncState IN ('LOCAL_ONLY', 'FAILED')")
    suspend fun enqueue(id: Long, nowMs: Long): Int

    /** Process-restart recovery: nothing can be mid-transfer when no worker is running. */
    @Query("UPDATE media_items SET syncState = 'QUEUED' WHERE syncState IN ('UPLOADING', 'VERIFYING')")
    suspend fun recoverInterrupted(): Int

    @Query("UPDATE media_items SET syncState = 'LOCAL_ONLY', queuedAtMs = NULL WHERE syncState = 'QUEUED'")
    suspend fun dequeueAll(): Int

    @Query("SELECT MAX(lastBackupAtMs) FROM media_items")
    fun observeLastBackup(): Flow<Long?>
}

@Dao
interface RemoteDao {
    @Query("SELECT * FROM remote_objects ORDER BY lastModifiedMs DESC")
    fun observeAll(): Flow<List<RemoteObjectEntity>>

    @Query("SELECT * FROM remote_objects WHERE key = :key")
    suspend fun get(key: String): RemoteObjectEntity?

    @Query("SELECT * FROM remote_objects")
    suspend fun getAll(): List<RemoteObjectEntity>

    @Query("SELECT * FROM remote_objects WHERE versionId = :versionId AND sizeBytes = :size LIMIT 1")
    suspend fun findByContent(versionId: String, size: Long): RemoteObjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<RemoteObjectEntity>)

    @Query("DELETE FROM remote_objects WHERE key IN (:keys)")
    suspend fun forget(keys: List<String>)

    @Query("UPDATE remote_objects SET state = :to, lastError = :error WHERE key = :key AND state = :from")
    suspend fun transition(key: String, from: SyncState, to: SyncState, error: String?): Int

    @Query("UPDATE remote_objects SET restoredUri = :uri, state = 'REMOTE_ONLY', lastError = NULL WHERE key = :key")
    suspend fun markRestored(key: String, uri: String)

    @Query("UPDATE remote_objects SET state = 'REMOTE_ONLY' WHERE state = 'RESTORING'")
    suspend fun recoverInterrupted(): Int

    @Query("DELETE FROM remote_objects")
    suspend fun clear()
}

@Dao
interface AlbumDao {
    @Query(
        "SELECT a.id AS id, a.name AS name, COUNT(m.mediaStoreId) AS itemCount, " +
            "(SELECT m2.mediaStoreId FROM album_items ai2 JOIN media_items m2 ON m2.mediaStoreId = ai2.mediaStoreId " +
            " WHERE ai2.albumId = a.id AND m2.present = 1 ORDER BY ai2.addedAtMs DESC LIMIT 1) AS coverMediaStoreId, " +
            "(SELECT m3.uri FROM album_items ai3 JOIN media_items m3 ON m3.mediaStoreId = ai3.mediaStoreId " +
            " WHERE ai3.albumId = a.id AND m3.present = 1 ORDER BY ai3.addedAtMs DESC LIMIT 1) AS coverUri " +
            "FROM albums a LEFT JOIN album_items ai ON ai.albumId = a.id " +
            "LEFT JOIN media_items m ON m.mediaStoreId = ai.mediaStoreId AND m.present = 1 " +
            "GROUP BY a.id ORDER BY a.name COLLATE NOCASE",
    )
    fun observeAlbums(): Flow<List<AlbumRow>>

    @Query("SELECT * FROM albums WHERE id = :id")
    fun observeAlbum(id: Long): Flow<AlbumEntity?>

    @Query(
        "SELECT m.* FROM media_items m JOIN album_items ai ON ai.mediaStoreId = m.mediaStoreId " +
            "WHERE ai.albumId = :albumId AND m.present = 1 ORDER BY m.dateTakenMs DESC",
    )
    fun observeAlbumMedia(albumId: Long): Flow<List<MediaItemEntity>>

    @Query("SELECT albumId FROM album_items WHERE mediaStoreId = :mediaId")
    suspend fun albumsOf(mediaId: Long): List<Long>

    @Insert
    suspend fun insert(album: AlbumEntity): Long

    @Query("UPDATE albums SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    /** Deletes the album definition only; media files are untouched. */
    @Query("DELETE FROM albums WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItems(items: List<AlbumItemEntity>)

    @Query("DELETE FROM album_items WHERE albumId = :albumId AND mediaStoreId IN (:ids)")
    suspend fun removeItems(albumId: Long, ids: List<Long>)

    @Query("DELETE FROM album_items WHERE mediaStoreId IN (:ids)")
    suspend fun removeMediaEverywhere(ids: List<Long>)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM transfer_events ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransferEventEntity>>

    @Insert
    suspend fun insert(e: TransferEventEntity)

    @Query("DELETE FROM transfer_events WHERE id NOT IN (SELECT id FROM transfer_events ORDER BY timestampMs DESC, id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Transaction
    suspend fun log(e: TransferEventEntity, keep: Int = 200) {
        insert(e)
        trim(keep)
    }

    @Query("DELETE FROM transfer_events")
    suspend fun clear()
}
