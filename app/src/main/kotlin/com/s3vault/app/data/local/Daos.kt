package com.s3vault.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import com.s3vault.app.domain.SyncState
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

data class HashedItem(val mediaStoreId: Long, val contentSha256: String?, val remoteVersionId: String?)

data class RemoteDateRow(val key: String, val effectiveDateMs: Long, val lastModifiedMs: Long, val dateSource: com.s3vault.app.domain.library.DateSource)

data class LocalDateRow(val mediaStoreId: Long, val dateTakenMs: Long)

@Dao
interface MediaDao {
    /** Local files whose content is known (hashed before an upload, or linked to a remote version). */
    @Query("SELECT mediaStoreId, contentSha256, remoteVersionId FROM media_items WHERE present = 1 AND (contentSha256 IS NOT NULL OR remoteVersionId IS NOT NULL)")
    suspend fun hashedItems(): List<HashedItem>

    @Query("SELECT * FROM media_items WHERE present = 1 AND (:includeVideos = 1 OR kind = 'IMAGE') ORDER BY dateTakenMs DESC, mediaStoreId DESC")
    fun observeGallery(includeVideos: Boolean): Flow<List<MediaItemEntity>>

    // ---- Smart gallery: dynamic queries built by LocalLibrarySql (every value bound, never concatenated).

    @RawQuery(observedEntities = [MediaItemEntity::class])
    fun observeDayCounts(query: SupportSQLiteQuery): Flow<List<DayCount>>

    @RawQuery(observedEntities = [MediaItemEntity::class])
    fun observeCount(query: SupportSQLiteQuery): Flow<Int>

    @RawQuery
    suspend fun rows(query: SupportSQLiteQuery): List<MediaItemEntity>

    @RawQuery
    suspend fun idList(query: SupportSQLiteQuery): List<Long>

    @RawQuery
    suspend fun countOnce(query: SupportSQLiteQuery): Int

    @Query("SELECT COUNT(*) FROM media_items WHERE present = 1")
    fun observePresentCount(): Flow<Int>

    /** Photos that sit on the timeline by their file date because no capture date exists. */
    @Query("SELECT COUNT(*) FROM media_items WHERE present = 1 AND dateSource = 'FALLBACK'")
    fun observeUndatedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media_items WHERE mediaStoreId IN (:ids) AND remoteKey IS NOT NULL")
    suspend fun countWithRemote(ids: List<Long>): Int

    @Query(LocalLibrarySql.FOLDERS)
    fun observeFolders(): Flow<List<FolderCount>>

    @Query("SELECT * FROM media_items WHERE mediaStoreId = :id")
    suspend fun get(id: Long): MediaItemEntity?

    @Query("SELECT mediaStoreId, dateTakenMs FROM media_items")
    suspend fun datesForZoneChange(): List<LocalDateRow>

    @Query("UPDATE media_items SET dayKey = :day WHERE mediaStoreId = :id")
    suspend fun setDayKey(id: Long, day: Int)

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

    /** S3 objects that still have their file on this device (rows whose file is gone only point to S3). */
    @Query("SELECT remoteKey FROM media_items WHERE remoteKey IS NOT NULL AND present = 1")
    fun observeBackedUpKeys(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM media_items WHERE contentSha256 = :sha AND present = 1")
    suspend fun countLocalWithHash(sha: String): Int

    @Query("SELECT COUNT(*) FROM media_items WHERE syncState = 'QUEUED' AND present = 1")
    suspend fun queuedCount(): Int

    @Query("SELECT * FROM media_items WHERE syncState = 'LOCAL_ONLY' AND present = 1 AND backupExcluded = 0 AND (:includeVideos = 1 OR kind = 'IMAGE')")
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
            "backedUpSizeBytes = :size, backedUpDateModifiedSec = :mtime, lastError = NULL, lastErrorPermanent = 0, attempts = 0, queuedAtMs = NULL, " +
            "thumbnailUploaded = 0, backupExcluded = 0 " +
            "WHERE mediaStoreId = :id AND syncState = 'VERIFYING'",
    )
    suspend fun markBackedUp(id: Long, key: String, version: String, nowMs: Long, size: Long, mtime: Long): Int

    @Query("UPDATE media_items SET syncState = 'QUEUED', queuedAtMs = :nowMs, lastError = NULL, lastErrorPermanent = 0, attempts = 0, backupExcluded = 0 WHERE mediaStoreId = :id AND syncState IN ('LOCAL_ONLY', 'FAILED')")
    suspend fun enqueue(id: Long, nowMs: Long): Int

    @Query("UPDATE media_items SET thumbnailUploaded = 1 WHERE mediaStoreId = :id AND remoteVersionId = :versionId")
    suspend fun markThumbnailUploaded(id: Long, versionId: String)

    /** Backed-up files on this device whose S3 preview has not been created yet (older backups, or a failed preview upload). */
    @Query(
        "SELECT * FROM media_items WHERE syncState = 'BACKED_UP' AND present = 1 AND thumbnailUploaded = 0 " +
            "AND remoteVersionId IS NOT NULL ORDER BY dateTakenMs DESC LIMIT :limit",
    )
    suspend fun thumbnailBacklog(limit: Int): List<MediaItemEntity>

    @Query("SELECT COUNT(*) FROM media_items WHERE syncState = 'BACKED_UP' AND present = 1 AND thumbnailUploaded = 0 AND remoteVersionId IS NOT NULL")
    fun observeThumbnailBacklog(): Flow<Int>

    @Query("SELECT * FROM media_items WHERE remoteKey IN (:keys)")
    suspend fun withRemoteKeys(keys: List<String>): List<MediaItemEntity>

    /**
     * The S3 copy of these files was deleted on purpose: forget the link, flag them so automatic backup does not
     * upload them again, and show them as "only on this device". In-flight rows keep their state (a worker owns them).
     */
    @Query(
        "UPDATE media_items SET remoteKey = NULL, remoteVersionId = NULL, lastBackupAtMs = NULL, backedUpSizeBytes = NULL, " +
            "backedUpDateModifiedSec = NULL, thumbnailUploaded = 0, backupExcluded = 1, lastError = NULL, lastErrorPermanent = 0, attempts = 0, " +
            "syncState = CASE WHEN syncState IN ('QUEUED', 'UPLOADING', 'VERIFYING') THEN syncState ELSE 'LOCAL_ONLY' END " +
            "WHERE present = 1 AND remoteKey IN (:keys)",
    )
    suspend fun detachRemote(keys: List<String>): Int

    /** Rows that existed only as a pointer to an S3 copy (the file is gone from the device). */
    @Query("SELECT mediaStoreId FROM media_items WHERE present = 0 AND remoteKey IN (:keys)")
    suspend fun orphanIdsOfRemoteKeys(keys: List<String>): List<Long>

    /** Files that left the device through a confirmed deletion: keep a pointer to the S3 copy, if there is one. */
    @Query("UPDATE media_items SET present = 0, syncState = 'REMOTE_ONLY', queuedAtMs = NULL WHERE mediaStoreId IN (:ids) AND remoteKey IS NOT NULL AND syncState NOT IN ('UPLOADING', 'VERIFYING')")
    suspend fun markGoneKeepingRemote(ids: List<Long>): Int

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

    // ---- Smart gallery over the cached listing (nothing here talks to S3).

    @RawQuery(observedEntities = [RemoteObjectEntity::class, MediaItemEntity::class])
    fun observeDayCounts(query: SupportSQLiteQuery): Flow<List<DayCount>>

    @RawQuery(observedEntities = [RemoteObjectEntity::class, MediaItemEntity::class])
    fun observeCount(query: SupportSQLiteQuery): Flow<Int>

    @RawQuery
    suspend fun rows(query: SupportSQLiteQuery): List<RemoteObjectEntity>

    @RawQuery
    suspend fun keyList(query: SupportSQLiteQuery): List<String>

    @RawQuery
    suspend fun countOnce(query: SupportSQLiteQuery): Int

    @Query("SELECT COUNT(*) FROM remote_objects")
    fun observeObjectCount(): Flow<Int>

    /** Objects listed apart because they have no capture date. */
    @Query("SELECT COUNT(*) FROM remote_objects WHERE dayKey = 0")
    fun observeUndatedCount(): Flow<Int>

    @Query("SELECT * FROM remote_objects")
    suspend fun getAll(): List<RemoteObjectEntity>

    @Query("SELECT `key` FROM remote_objects")
    suspend fun allKeys(): List<String>

    /**
     * Objects backed by a local file that has a real capture date take it from there: free, exact and without a request.
     * Objects whose date is already a capture date are left alone.
     */
    @Query(
        "UPDATE remote_objects SET " +
            "effectiveDateMs = (SELECT m.dateTakenMs FROM media_items m WHERE m.remoteKey = remote_objects.`key` AND m.dateSource = 'CAPTURE' LIMIT 1), " +
            "dayKey = (SELECT m.dayKey FROM media_items m WHERE m.remoteKey = remote_objects.`key` AND m.dateSource = 'CAPTURE' LIMIT 1), " +
            "dateSource = 'CAPTURE', metaChecked = 1 " +
            "WHERE dateSource != 'CAPTURE' AND EXISTS (SELECT 1 FROM media_items m WHERE m.remoteKey = remote_objects.`key` AND m.dateSource = 'CAPTURE')",
    )
    suspend fun applyLocalCaptureDates(): Int

    /** Objects whose date is not a capture date and whose metadata was never asked for: the candidates of the opt-in HEAD pass. */
    @Query("SELECT * FROM remote_objects WHERE dateSource != 'CAPTURE' AND metaChecked = 0 LIMIT :limit")
    suspend fun pendingMeta(limit: Int): List<RemoteObjectEntity>

    @Query("SELECT COUNT(*) FROM remote_objects WHERE dateSource != 'CAPTURE' AND metaChecked = 0")
    fun observePendingMetaCount(): Flow<Int>

    @Query("UPDATE remote_objects SET effectiveDateMs = :ms, dateSource = 'CAPTURE', dayKey = :day, metaChecked = 1 WHERE `key` = :key")
    suspend fun applyCapture(key: String, ms: Long, day: Int)

    @Query("UPDATE remote_objects SET metaChecked = 1 WHERE `key` = :key")
    suspend fun markMetaChecked(key: String)

    /** Rewrites the day keys after the device time zone changed. Capture dates only: an unknown date has no key. */
    @Query("UPDATE remote_objects SET dayKey = :day WHERE `key` = :key")
    suspend fun setDayKey(key: String, day: Int)

    @Query("UPDATE remote_objects SET uploadDayKey = :day WHERE `key` = :key")
    suspend fun setUploadDayKey(key: String, day: Int)

    @Query("SELECT `key`, effectiveDateMs, lastModifiedMs, dateSource FROM remote_objects")
    suspend fun datesForZoneChange(): List<RemoteDateRow>

    @Query("SELECT * FROM remote_objects WHERE key IN (:keys)")
    suspend fun getByKeys(keys: List<String>): List<RemoteObjectEntity>

    /** Versions (content hashes) that some object outside [excludedKeys] still uses, so their shared preview must stay. */
    @Query("SELECT DISTINCT versionId FROM remote_objects WHERE versionId IN (:versionIds) AND key NOT IN (:excludedKeys)")
    suspend fun versionsStillUsed(versionIds: List<String>, excludedKeys: List<String>): List<String>

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

    @Query("SELECT * FROM albums")
    suspend fun all(): List<AlbumEntity>

    @Query("SELECT mediaStoreId FROM album_items WHERE albumId = :albumId")
    suspend fun memberIds(albumId: Long): List<Long>

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

    @Query("SELECT COUNT(*) FROM transfer_events WHERE type = 'UPLOADED' AND timestampMs > :sinceMs")
    fun observeUploadsSince(sinceMs: Long): Flow<Int>

    /** Objects deleted from S3 since [sinceMs]; each event is named "N elementos", and SQLite reads the leading number. */
    @Query("SELECT COALESCE(SUM(CAST(name AS INTEGER)), 0) FROM transfer_events WHERE type = 'CLOUD_DELETED' AND timestampMs > :sinceMs")
    fun observeCloudDeletionsSince(sinceMs: Long): Flow<Int>

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
